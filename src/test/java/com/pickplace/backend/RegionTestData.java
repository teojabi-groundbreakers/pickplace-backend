package com.pickplace.backend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

// 전국 지역 기준정보(V3·V4) 테스트 데이터를 SQL로 직접 넣는다.
// 실제 적재 명령은 검증 규칙상 "선택 가능한 과거 지역" 같은 잘못된 행을 막는데,
// 조회 API가 그런 행을 스스로 한 번 더 걸러내는지도 확인해야 하므로 적재 명령을 거치지 않는다.
final class RegionTestData {

	static final String SOURCE_REFERENCE_ON = "2026-10-30";
	static final String SOURCE_FILE_VERSION = "20261026";

	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 10, 30, 0, 0);
	private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 30, 1, 0);
	private static final String HASH = "a".repeat(64);

	private final JdbcTemplate jdbc;
	private long sourceRowNumber = 1;

	RegionTestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 전국 기준정보 테이블을 FK 순서대로 비운다.
	void clear() {
		for (String table : List.of("region_publication_event", "region_publication", "region_stat_mapping",
				"region_navigation", "official_region_node", "region_source_artifact", "region_release",
				"source_region_reference", "service_region")) {
			jdbc.update("DELETE FROM " + table);
		}
	}

	// 릴리스 한 개와 출처 파일 기록을 넣고 release_id를 돌려준다. status는 APPROVED 또는 READY다.
	long release(String version, String status) {
		boolean approved = "APPROVED".equals(status);
		jdbc.update("""
				INSERT INTO region_release (release_version, status, parser_version, policy_version, started_at, finished_at,
				    approved_at, approved_by, expected_counts, actual_counts, validation_report)
				VALUES (?, ?, 'parser-test', 'policy-test', ?, ?, ?, ?, ?, ?, ?)
				""", version, status, STARTED_AT, STARTED_AT,
				approved ? APPROVED_AT : null, approved ? "tester" : null,
				approved ? "{}" : null, approved ? "{}" : null, approved ? "{}" : null);
		long releaseId = jdbc.queryForObject("SELECT release_id FROM region_release WHERE release_version = ?", Long.class, version);
		artifact(releaseId, "H_CURRENT_ZIP");
		artifact(releaseId, "H_HISTORY_ZIP");
		return releaseId;
	}

	// 공식 원본 파일 기록 한 행을 넣는다. 응답 meta의 출처 정보는 H_HISTORY_ZIP 행에서 나온다.
	private void artifact(long releaseId, String role) {
		jdbc.update("""
				INSERT INTO region_source_artifact (release_id, artifact_role, notice_url, download_url, storage_key, sha256,
				    byte_count, collected_at, notice_reference_on, attachment_version, encoding)
				VALUES (?, ?, 'https://example.test/notice', 'https://example.test/file.zip', 'file.zip', ?, 100, ?, ?, ?, 'xlsx')
				""", releaseId, role, HASH, STARTED_AT, LocalDate.parse(SOURCE_REFERENCE_ON), SOURCE_FILE_VERSION);
	}

	// 공식 이력 파일 기록을 지워 "게시본에 출처 정보가 없는" 비정상 상태를 만든다.
	void deleteHistoryArtifact(long releaseId) {
		jdbc.update("DELETE FROM region_source_artifact WHERE release_id = ? AND artifact_role = 'H_HISTORY_ZIP'", releaseId);
	}

	// 게시 포인터가 이 릴리스를 가리키게 한다. 이미 게시된 것이 있으면 바꾼다.
	void publish(long releaseId) {
		jdbc.update("""
				INSERT INTO region_publication (publication_key, release_id, release_status, revision, published_at)
				VALUES ('NATIONWIDE_SELECTION', ?, 'APPROVED', 1, ?)
				ON DUPLICATE KEY UPDATE release_id = VALUES(release_id), revision = revision + 1
				""", releaseId, APPROVED_AT);
	}

	// 게시 포인터를 지워 "아직 게시 전" 상태를 만든다.
	void unpublish() {
		jdbc.update("DELETE FROM region_publication");
	}

	// 지역들을 한 릴리스에 넣는다. 부모를 참조하는 FK 때문에 공식 노드를 모두 넣은 뒤 화면 계층을 넣는다.
	void insert(long releaseId, Region... regions) {
		for (Region region : regions) {
			jdbc.update("INSERT IGNORE INTO service_region (region_code, created_at) VALUES (?, ?)", region.code(), STARTED_AT);
			insertOfficialNode(releaseId, region);
		}
		for (Region region : regions) {
			insertNavigation(releaseId, region);
		}
	}

	// 공식 행정기관 노드 한 행을 넣는다. 과거 지역은 말소일, 날짜 오류 지역은 오류 사유를 함께 넣는다.
	private void insertOfficialNode(long releaseId, Region region) {
		boolean legacy = "LEGACY".equals(region.officialStatus());
		jdbc.update("""
				INSERT INTO official_region_node (release_id, region_code, province_name, raw_created_on, raw_abolished_on,
				    created_on, abolished_on, date_quality_status, date_issue_code, official_status, artifact_role, source_row_number)
				VALUES (?, ?, ?, '20000101', ?, ?, ?, ?, ?, ?, 'H_CURRENT_ZIP', ?)
				""", releaseId, region.code(), region.name(),
				legacy ? "20250701" : null,
				region.validDate() ? LocalDate.of(2000, 1, 1) : null,
				region.validDate() && legacy ? LocalDate.of(2025, 7, 1) : null,
				region.validDate() ? "VALID" : "INVALID",
				region.validDate() ? null : "CREATED_AFTER_ABOLISHED",
				region.officialStatus(), sourceRowNumber++);
	}

	// 화면 선택 계층 한 행을 넣는다.
	private void insertNavigation(long releaseId, Region region) {
		jdbc.update("""
				INSERT INTO region_navigation (release_id, region_code, node_kind, hierarchy_parent_code, province_code,
				    selection_parent_code, display_name, selection_role, selectable, auto_select_group, review_status, review_evidence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test review')
				""", releaseId, region.code(), region.kind(), region.hierarchyParent(), region.provinceCode(),
				region.selectionParent(), region.name(), region.role(), region.selectable(), region.autoSelect(),
				region.reviewStatus());
	}

	// 테스트용 지역 한 개다. 기본값은 "화면에 보이는 현행 지역"이고, 아래 메서드로 숨김·과거 등 예외 상태를 만든다.
	record Region(String code, String name, String kind, String role, String provinceCode, String hierarchyParent,
			String selectionParent, boolean selectable, boolean autoSelect, String reviewStatus,
			String officialStatus, boolean validDate) {

		// 화면에 보이는 시·도다.
		static Region province(String code, String name) {
			return new Region(code, name, "PROVINCE", "PROVINCE", code, null, null, true, false, "VERIFIED", "CURRENT", true);
		}

		// 화면에 보이는 그룹이다. 기본으로 공식 부모와 화면 부모 모두 시·도다.
		static Region group(String code, String name, String provinceCode) {
			return new Region(code, name, "GROUP", "GROUP", provinceCode, provinceCode, provinceCode, true, false,
					"VERIFIED", "CURRENT", true);
		}

		// 화면에 보이는 읍·면·동이다. 기본으로 공식 부모와 화면 부모 모두 그룹이다.
		static Region locality(String code, String name, String provinceCode, String groupCode) {
			return new Region(code, name, "DONG", "LOCALITY", provinceCode, groupCode, groupCode, true, false,
					"VERIFIED", "CURRENT", true);
		}

		// 공식 부모만 바꾼다. 예: 수원시 장안구의 공식 부모는 수원시, 화면 부모는 경기도다.
		Region officialParent(String parentCode) {
			return new Region(code, name, kind, role, provinceCode, parentCode, selectionParent, selectable, autoSelect,
					reviewStatus, officialStatus, validDate);
		}

		// 세종처럼 화면에서 자동으로 고르는 그룹으로 만든다.
		Region autoSelected() {
			return new Region(code, name, kind, role, provinceCode, hierarchyParent, selectionParent, selectable, true,
					reviewStatus, officialStatus, validDate);
		}

		// 출장소·도시 컨테이너처럼 공식 노드는 있지만 화면 선택 대상이 아닌 지역으로 만든다.
		Region hidden(String hiddenKind) {
			return new Region(code, name, hiddenKind, "NONE", provinceCode, hierarchyParent, null, false, false,
					"BLOCKED", officialStatus, validDate);
		}

		// 검토가 끝나지 않아 선택할 수 없는 지역으로 만든다.
		Region pendingReview() {
			return new Region(code, name, kind, role, provinceCode, hierarchyParent, selectionParent, false, false,
					"PENDING", officialStatus, validDate);
		}

		// 말소된 과거 지역으로 만든다. 선택 가능 표시는 그대로 두어 조회 API의 현행 필터를 확인한다.
		Region legacy() {
			return new Region(code, name, kind, role, provinceCode, hierarchyParent, selectionParent, selectable,
					autoSelect, reviewStatus, "LEGACY", validDate);
		}

		// 공식 날짜에 오류가 있는 지역으로 만든다. 선택 가능 표시는 그대로 두어 날짜 품질 필터를 확인한다.
		Region invalidDate() {
			return new Region(code, name, kind, role, provinceCode, hierarchyParent, selectionParent, selectable,
					autoSelect, reviewStatus, officialStatus, false);
		}

	}

}
