package com.pickplace.backend.region;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

// 전국 지역 기준정보(V3·V4 테이블)를 읽기만 하는 조회 전용 저장소다.
// 키가 (release_id, region_code) 두 칸이고 매번 세 테이블을 이어 붙이므로, JPA Entity 대신 SQL을 그대로 쓴다.
@Repository
public class RegionQueryRepository {

	// 전국 지역 선택 목록의 게시 포인터 이름이다. DB CHECK 제약상 이 값 하나만 존재한다.
	private static final String PUBLICATION_KEY = "NATIONWIDE_SELECTION";

	// 출처 meta는 공식 이력 파일(H_HISTORY_ZIP)의 게시글 기준일과 첨부 파일 버전을 쓴다.
	private static final String SOURCE_ARTIFACT_ROLE = "H_HISTORY_ZIP";

	// 게시 포인터 → 릴리스 상태 → 출처 파일 정보를 한 번에 읽는다. 게시 포인터가 없으면 0행이다.
	private static final String PUBLISHED_RELEASE_SQL = """
			SELECT p.release_id, r.status, a.notice_reference_on, a.attachment_version
			FROM region_publication p
			JOIN region_release r ON r.release_id = p.release_id
			LEFT JOIN region_source_artifact a
			  ON a.release_id = p.release_id AND a.artifact_role = ?
			WHERE p.publication_key = ?
			""";

	// 화면에 보여도 되는 지역의 조건이다. 출장소·말소·날짜 오류·검토 전·선택 불가 노드를 모두 걸러낸다.
	// 두 테이블을 release_id와 region_code 둘 다로 이어야 다른 릴리스의 노드가 섞이지 않는다.
	private static final String SELECTABLE_FROM_WHERE = """
			FROM region_navigation n
			JOIN official_region_node o
			  ON o.release_id = n.release_id AND o.region_code = n.region_code
			WHERE n.release_id = ?
			  AND n.selection_role = ?
			  AND n.selectable = TRUE
			  AND n.review_status = 'VERIFIED'
			  AND o.official_status = 'CURRENT'
			  AND o.date_quality_status = 'VALID'
			""";

	private static final String EXISTS_SQL = "SELECT COUNT(*) " + SELECTABLE_FROM_WHERE + " AND n.region_code = ?";

	private static final String LIST_COLUMNS =
			"SELECT n.region_code, n.display_name, n.selection_role, n.selection_parent_code, n.auto_select_group ";

	private static final String FIND_SQL = LIST_COLUMNS + SELECTABLE_FROM_WHERE + " AND n.region_code = ?";

	// 시·도는 코드순이 계약이다.
	private static final String PROVINCES_SQL = LIST_COLUMNS + SELECTABLE_FROM_WHERE + " ORDER BY n.region_code";

	// 그룹·읍면동은 이름순, 같은 이름은 코드순이다. 하위 목록은 공식 부모가 아닌 화면 선택 부모로 찾는다.
	// 이름 정렬은 컬럼의 MySQL 정렬 규칙(utf8mb4_0900_ai_ci)을 그대로 따른다.
	private static final String CHILDREN_SQL = LIST_COLUMNS + SELECTABLE_FROM_WHERE
			+ " AND n.selection_parent_code = ? ORDER BY n.display_name, n.region_code";

	private static final RowMapper<RegionItem> REGION_ITEM_MAPPER = (rs, rowNum) -> new RegionItem(
			rs.getString("region_code"),
			rs.getString("display_name"),
			SelectionLevel.valueOf(rs.getString("selection_role")),
			rs.getString("selection_parent_code"),
			rs.getBoolean("auto_select_group"));

	private final JdbcTemplate jdbc;

	public RegionQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 지금 게시된 릴리스와 출처 정보를 돌려준다. 아직 게시된 적이 없으면 비어 있다.
	public Optional<PublishedRegionRelease> findPublishedRelease() {
		List<PublishedRegionRelease> rows = jdbc.query(PUBLISHED_RELEASE_SQL, (rs, rowNum) -> {
			Date referenceOn = rs.getDate("notice_reference_on");
			return new PublishedRegionRelease(rs.getLong("release_id"), rs.getString("status"),
					referenceOn == null ? null : referenceOn.toLocalDate(), rs.getString("attachment_version"));
		}, SOURCE_ARTIFACT_ROLE, PUBLICATION_KEY);
		// publication_key가 기본키라 0행 또는 1행이다.
		return rows.stream().findFirst();
	}

	// 해당 릴리스에서 이 코드가 그 단계로 화면에 보이는 지역인지 확인한다.
	public boolean existsSelectableRegion(long releaseId, String regionCode, SelectionLevel level) {
		Integer count = jdbc.queryForObject(EXISTS_SQL, Integer.class, releaseId, level.name(), regionCode);
		return count != null && count > 0;
	}

	// 해당 릴리스에서 이 코드가 그 단계로 화면에 보이면 표시명까지 돌려준다. 아니면 비어 있다.
	public Optional<RegionItem> findSelectableRegion(long releaseId, String regionCode, SelectionLevel level) {
		// (release_id, region_code)가 기본키라 0행 또는 1행이다.
		return jdbc.query(FIND_SQL, REGION_ITEM_MAPPER, releaseId, level.name(), regionCode).stream().findFirst();
	}

	// 해당 릴리스의 시·도 목록을 코드순으로 돌려준다.
	public List<RegionItem> findProvinces(long releaseId) {
		return jdbc.query(PROVINCES_SQL, REGION_ITEM_MAPPER, releaseId, SelectionLevel.PROVINCE.name());
	}

	// 해당 릴리스에서 화면 부모가 parentCode인 하위 지역을 이름순(같은 이름은 코드순)으로 돌려준다.
	public List<RegionItem> findChildren(long releaseId, SelectionLevel level, String parentCode) {
		return jdbc.query(CHILDREN_SQL, REGION_ITEM_MAPPER, releaseId, level.name(), parentCode);
	}

}
