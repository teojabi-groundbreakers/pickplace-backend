package com.pickplace.backend;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

// 전국 업종 분류(V4 national_industry_*) 테스트 데이터를 SQL로 직접 넣는다.
// 지역 릴리스와의 연결(검증 보고서의 classificationId)도 여기서 만든다.
final class IndustryTestData {

	// 소분류는 중분류, 중분류는 대분류를 부모로 가져야 하므로(DB 제약) 분류본마다 이 두 행을 먼저 넣는다.
	static final String LARGE_CODE = "I2";
	static final String MIDDLE_CODE = "I21";

	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 30, 0, 0);
	private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 30, 1, 0);
	private static final String HASH = "b".repeat(64);

	private final JdbcTemplate jdbc;

	IndustryTestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 업종 분류 테이블을 비운다. 소분류가 중분류를, 중분류가 대분류를 참조하므로 아래 단계부터 지운다.
	void clear() {
		for (String level : new String[] {"SMALL", "MIDDLE", "LARGE"}) {
			jdbc.update("DELETE FROM national_industry_code WHERE industry_level = ?", level);
		}
		jdbc.update("DELETE FROM national_industry_release");
	}

	// 분류본 한 개와 대·중분류 행을 넣고 classification_id를 돌려준다. status는 APPROVED 또는 READY다.
	long classification(String version, String status) {
		boolean approved = "APPROVED".equals(status);
		jdbc.update("""
				INSERT INTO national_industry_release (version, source_url, source_sha256, reference_snapshot_on,
				    collected_at, status, validation_report, approved_at, approved_by)
				VALUES (?, 'https://example.test/semas', ?, ?, ?, ?, ?, ?, ?)
				""", version, HASH, LocalDate.of(2026, 9, 30), COLLECTED_AT, status,
				approved ? "{}" : null, approved ? APPROVED_AT : null, approved ? "tester" : null);
		long classificationId = jdbc.queryForObject(
				"SELECT classification_id FROM national_industry_release WHERE version = ?", Long.class, version);
		insertParent(classificationId, LARGE_CODE, "음식", "LARGE", null, null);
		insertParent(classificationId, MIDDLE_CODE, "음식점업", "MIDDLE", LARGE_CODE, "LARGE");
		return classificationId;
	}

	// 소분류 한 행을 넣는다. 원천명과 표시명을 따로 받아 API가 표시명을 쓰는지 확인할 수 있게 한다.
	void small(long classificationId, String code, String sourceName, String displayName, boolean supported, int displayOrder) {
		jdbc.update("""
				INSERT INTO national_industry_code (classification_id, industry_code, industry_name, industry_level,
				    parent_code, parent_level, display_name, is_supported, support_release_status, display_order)
				VALUES (?, ?, ?, 'SMALL', ?, 'MIDDLE', ?, ?, ?, ?)
				""", classificationId, code, sourceName, MIDDLE_CODE, displayName, supported,
				supported ? "APPROVED" : null, displayOrder);
	}

	// 지역 릴리스의 검증 보고서에 업종 분류 번호를 적어 둘을 연결한다. 실제 적재 명령과 같은 키 이름을 쓴다.
	void link(long releaseId, long classificationId) {
		jdbc.update("UPDATE region_release SET validation_report = JSON_OBJECT('classificationId', ?) WHERE release_id = ?",
				classificationId, releaseId);
	}

	// 검증 보고서를 원하는 JSON으로 바꾼다. 분류 번호가 없거나 형식이 깨진 비정상 상태를 만들 때 쓴다.
	void report(long releaseId, String json) {
		jdbc.update("UPDATE region_release SET validation_report = ? WHERE release_id = ?", json, releaseId);
	}

	// 대분류 또는 중분류 행 한 개를 넣는다. 지원 표시는 소분류에만 붙을 수 있으므로(DB 제약) 항상 미지원이다.
	private void insertParent(long classificationId, String code, String name, String level, String parentCode,
			String parentLevel) {
		jdbc.update("""
				INSERT INTO national_industry_code (classification_id, industry_code, industry_name, industry_level,
				    parent_code, parent_level, display_name, is_supported, support_release_status, display_order)
				VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, NULL, 0)
				""", classificationId, code, name, level, parentCode, parentLevel, name);
	}

}
