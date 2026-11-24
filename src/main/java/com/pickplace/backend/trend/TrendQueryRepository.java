package com.pickplace.backend.trend;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 상세 통계의 배치는 현재 분기별 게시 포인터에서 찾는다.
@Repository
public class TrendQueryRepository {

	private static final String PERIOD_TYPE = "QUARTER";

	// 정상 행과 보류 행을 같은 인덱스 탐색으로 가져온다. 행이 없을 때만 배치 JSON에서 지역 수록 여부를 확인한다.
	// 포인터가 비정상이면 배치 상태를 그대로 돌려주고 서비스에서 오류를 처리한다.
	private static final String PERIOD_ROWS_SQL = """
			SELECT p.period_key, p.batch_id, b.status, b.industry_scheme, b.mapping_release_id,
			       JSON_EXTRACT(b.definition_json, '$.metrics') AS metric_definitions,
			       CASE WHEN r.batch_id IS NULL AND b.status = 'APPROVED'
			            THEN COALESCE(JSON_CONTAINS(JSON_EXTRACT(b.mapping_json, '$.regions[*].targetCode'), JSON_QUOTE(?)), 0)
			            ELSE 0 END AS region_covered,
			       CASE WHEN r.row_status = 'VALID' THEN r.metrics END AS metrics,
			       r.reason_code
			FROM regional_statistic_publication p
			JOIN regional_statistic_batch b ON b.batch_id = p.batch_id
			LEFT JOIN regional_statistic_row r ON r.batch_id = p.batch_id AND r.period_key = p.period_key
			  AND r.region_code = ? AND r.source_industry_code = ? AND b.status = 'APPROVED'
			WHERE p.source_system = ? AND p.dataset_code = ? AND p.period_type = ?
			  AND p.period_key BETWEEN ? AND ?
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

	// 게시된 분기에 행이 없어도 포인터는 반환한다. 미게시 분기는 결과에 없다.
	public List<PeriodRow> findPeriodRows(TrendDataset dataset, String regionCode, String industryCode, String from, String to) {
		return jdbc.query(PERIOD_ROWS_SQL, (rs, rowNum) -> new PeriodRow(
				new Publication(rs.getString("period_key"), rs.getLong("batch_id"), rs.getString("status"),
						rs.getString("industry_scheme"), rs.getLong("mapping_release_id"),
						rs.getString("metric_definitions"), rs.getInt("region_covered") == 1),
				rs.getString("metrics"), rs.getString("reason_code")),
				regionCode, regionCode, industryCode, dataset.sourceSystem(), dataset.storedDatasetCode(), PERIOD_TYPE, from, to);
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

	// 정상 행은 지표 JSON, 보류·오류 행은 사유를 담는다.
	public record PeriodRow(Publication publication, String metrics, String withheldReason) {
	}

	// 기준정보 노드의 공식 상태(CURRENT/LEGACY)와 날짜 검증 상태다.
	public record NodeState(String officialStatus, String dateQualityStatus) {
	}

}
