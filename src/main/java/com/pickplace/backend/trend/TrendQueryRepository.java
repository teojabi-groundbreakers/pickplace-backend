package com.pickplace.backend.trend;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 상세 통계(V7 regional_statistic_*)를 읽기만 하는 조회 전용 저장소다. 원천·자료는 TrendDataset이 정한다.
// 배치 번호는 환경마다 다르므로 고정하지 않고, 매 요청 분기별 게시 포인터에서 찾는다.
@Repository
public class TrendQueryRepository {

	private static final String PERIOD_TYPE = "QUARTER";

	// 요청 범위의 분기별 게시 포인터와 그 배치의 상태·업종 체계·대응 릴리스·지표 정의를 읽는다.
	// region_covered는 그 배치의 원천 지역 대응표에 이 지역이 들어 있는지(1/0)다. 대응표 밖이면 원천이 수록하지 않는 지역이다.
	// 분기 코드는 YYYYQ 다섯 자리라 문자열 범위 비교가 시간 순서와 같다.
	private static final String PUBLICATIONS_SQL = """
			SELECT p.period_key, p.batch_id, b.status, b.industry_scheme, b.mapping_release_id,
			       JSON_EXTRACT(b.definition_json, '$.metrics') AS metric_definitions,
			       COALESCE(JSON_CONTAINS(JSON_EXTRACT(b.mapping_json, '$.regions[*].targetCode'), JSON_QUOTE(?)), 0) AS region_covered
			FROM regional_statistic_publication p
			JOIN regional_statistic_batch b ON b.batch_id = p.batch_id
			WHERE p.source_system = ? AND p.dataset_code = ? AND p.period_type = ?
			  AND p.period_key BETWEEN ? AND ?
			""";

	// 값은 인계받은 공개 읽기 경로인 published_regional_statistic 뷰에서 읽는다.
	// 이 뷰는 승인된 배치의 게시된 분기 중 정상(VALID) 행만 보여준다.
	private static final String PUBLISHED_ROWS_SQL = """
			SELECT period_key, batch_id, metrics
			FROM published_regional_statistic
			WHERE source_system = ? AND dataset_code = ? AND period_type = ?
			  AND period_key BETWEEN ? AND ? AND region_code = ? AND source_industry_code = ?
			""";

	// 같은 게시 배치에서 이 지역·업종 행이 보류·오류로 빠졌는지와 그 사유를 읽는다.
	// 뷰에는 정상 행만 있어서 "행 없음"과 "보류돼서 빠짐"을 구분하려면 원천 행 테이블을 직접 봐야 한다.
	private static final String WITHHELD_ROWS_SQL = """
			SELECT r.period_key, r.reason_code
			FROM regional_statistic_publication p
			JOIN regional_statistic_row r ON r.batch_id = p.batch_id AND r.period_key = p.period_key
			WHERE p.source_system = ? AND p.dataset_code = ? AND p.period_type = ?
			  AND p.period_key BETWEEN ? AND ? AND r.region_code = ? AND r.source_industry_code = ?
			  AND r.row_status <> 'VALID'
			""";

	private static final String NODE_SQL = """
			SELECT official_status, date_quality_status
			FROM official_region_node
			WHERE release_id = ? AND region_code = ?
			""";

	private final JdbcTemplate jdbc;

	public TrendQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 요청 범위에서 게시된 분기의 포인터·배치 정보를 돌려준다. 게시되지 않은 분기는 목록에 없다.
	public List<Publication> findPublications(TrendDataset dataset, String regionCode, String from, String to) {
		return jdbc.query(PUBLICATIONS_SQL, (rs, rowNum) -> new Publication(
				rs.getString("period_key"),
				rs.getLong("batch_id"),
				rs.getString("status"),
				rs.getString("industry_scheme"),
				rs.getLong("mapping_release_id"),
				rs.getString("metric_definitions"),
				rs.getInt("region_covered") == 1),
				regionCode, dataset.sourceSystem(), dataset.storedDatasetCode(), PERIOD_TYPE, from, to);
	}

	// 요청 범위에서 이 지역·업종의 게시된 정상 행을 돌려준다.
	public List<PublishedRow> findPublishedRows(TrendDataset dataset, String regionCode, String industryCode, String from, String to) {
		return jdbc.query(PUBLISHED_ROWS_SQL, (rs, rowNum) -> new PublishedRow(
				rs.getString("period_key"),
				rs.getLong("batch_id"),
				rs.getString("metrics")),
				dataset.sourceSystem(), dataset.storedDatasetCode(), PERIOD_TYPE, from, to, regionCode, industryCode);
	}

	// 요청 범위의 게시 배치에서 이 지역·업종 행이 보류·오류로 빠진 기록을 돌려준다.
	public List<WithheldRow> findWithheldRows(TrendDataset dataset, String regionCode, String industryCode, String from, String to) {
		return jdbc.query(WITHHELD_ROWS_SQL, (rs, rowNum) -> new WithheldRow(
				rs.getString("period_key"),
				rs.getString("reason_code")),
				dataset.sourceSystem(), dataset.storedDatasetCode(), PERIOD_TYPE, from, to, regionCode, industryCode);
	}

	// 대응표를 만든 기준정보 릴리스에서 이 지역 노드의 공식 상태를 돌려준다. 노드가 없으면 비어 있다.
	public Optional<NodeState> findNode(long releaseId, String regionCode) {
		return jdbc.query(NODE_SQL, (rs, rowNum) -> new NodeState(
				rs.getString("official_status"),
				rs.getString("date_quality_status")),
				releaseId, regionCode).stream().findFirst();
	}

	// 한 분기의 게시 포인터와 그 배치 정보다. metricDefinitions는 배치에 고정된 지표 정의(JSON 배열)다.
	public record Publication(
			String quarter,
			long batchId,
			String batchStatus,
			String industryScheme,
			long mappingReleaseId,
			String metricDefinitions,
			boolean regionCovered) {
	}

	// 게시된 정상 행 하나다. metrics는 지표별 값·상태·사유를 담은 JSON이다.
	public record PublishedRow(String quarter, long batchId, String metrics) {
	}

	// 게시 배치에 있지만 보류·오류로 공개 읽기 경로에서 빠진 행의 사유다.
	public record WithheldRow(String quarter, String reasonCode) {
	}

	// 기준정보 노드의 공식 상태(CURRENT/LEGACY)와 날짜 검증 상태다.
	public record NodeState(String officialStatus, String dateQualityStatus) {
	}

}
