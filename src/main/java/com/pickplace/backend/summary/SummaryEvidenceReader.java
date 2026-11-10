package com.pickplace.backend.summary;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pickplace.backend.summary.MetricEvidence.Population;
import com.pickplace.backend.summary.MetricEvidence.PopulationRow;
import com.pickplace.backend.summary.MetricEvidence.PopulationStatus;
import com.pickplace.backend.summary.MetricEvidence.RegionState;
import com.pickplace.backend.summary.MetricEvidence.StoreAggregate;
import com.pickplace.backend.summary.MetricEvidence.Stores;

// 고정된 현황 묶음의 배치에서 한 지역·업종의 판정 근거를 읽는다. 공개 숫자·상태는 MetricAvailabilityPolicy가 정한다.
// 반드시 SummaryContextService.withContext 콜백 안에서 불러야 선택 기준·DEFAULT와 같은 읽기 시점을 본다.
// 게시 포인터를 다시 읽지 않고 context에 고정된 배치·릴리스·분류 번호만 쓴다.
@Repository
public class SummaryEvidenceReader {

	private static final String POPULATION_SYSTEM = "MOIS_RESIDENT";
	private static final String STORE_SYSTEM = "SEMAS_STORE";

	// 현황 묶음의 대응 릴리스에도 이 지역이 현행·유효 날짜 노드로 있는지 확인한다.
	private static final String COMPATIBLE_REGION_SQL = """
			SELECT COUNT(*) FROM official_region_node
			WHERE release_id = ? AND region_code = ? AND official_status = 'CURRENT' AND date_quality_status = 'VALID'
			""";

	private static final String POPULATION_ROW_SQL = """
			SELECT row_status, total_population, temporal_region_status
			FROM resident_population_month
			WHERE batch_id = ? AND region_code = ?
			""";

	// 주민등록 인구는 원천 코드가 H10 그대로라 원천 지역 기록을 같은 코드로 찾는다.
	private static final String POPULATION_SOURCE_SQL = """
			SELECT COUNT(*) FROM source_region_reference
			WHERE source_system = ? AND source_region_code = ? AND source_region_version = ?
			""";

	private static final String POPULATION_MAPPING_SQL = """
			SELECT review_status FROM region_stat_mapping
			WHERE release_id = ? AND source_system = ? AND source_region_code = ? AND source_region_version = ? AND region_code = ?
			""";

	// 수치 컬럼이 정상이어도 기준일 불일치처럼 집계에 영향을 주는 문제가 기록됐을 수 있다.
	private static final String POPULATION_ISSUE_SQL = """
			SELECT COUNT(*) FROM national_quality_issue
			WHERE batch_id = ? AND affects_count = TRUE AND affected_region_code = ?
			""";

	// 이 H10으로 대응되는 업소 원천 지역마다, 실제 배포본에서 관측된 수록 범위 행을 붙인다.
	// 수록 범위 행의 region_code는 대응표 FK에 묶여 있어, 대응표에서 출발하면 이 지역의 수록 범위를 빠짐없이 찾는다.
	private static final String STORE_COVERAGE_SQL = """
			SELECT m.review_status, c.source_region_code AS observed, c.region_code AS covered_region,
			    c.temporal_region_status, c.coverage_status
			FROM region_stat_mapping m
			LEFT JOIN store_region_coverage c
			  ON c.batch_id = ? AND c.source_region_code = m.source_region_code
			  AND c.source_region_version = m.source_region_version
			WHERE m.release_id = ? AND m.source_system = ? AND m.source_region_version = ? AND m.region_code = ?
			""";

	// 지역 전체(REGION) 또는 이 지역×업종(REGION_INDUSTRY)에 영향을 주는 품질 문제다. 집계 행이 없어도 확인한다.
	private static final String STORE_ISSUE_SQL = """
			SELECT COUNT(*) FROM national_quality_issue
			WHERE batch_id = ? AND affects_count = TRUE AND affected_region_code = ?
			  AND (impact_scope = 'REGION'
			    OR (impact_scope = 'REGION_INDUSTRY' AND classification_id = ? AND affected_industry_code = ?))
			""";

	private static final String STORE_COUNT_SQL = """
			SELECT accepted_store_count, count_quality_status
			FROM store_industry_count
			WHERE batch_id = ? AND region_code = ? AND classification_id = ? AND industry_code = ?
			""";

	private final JdbcTemplate jdbc;

	public SummaryEvidenceReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 주민등록 인구 판정 근거를 만든다.
	public Population population(SummaryReadContext context) {
		String regionCode = context.selection().regionCode();
		long mappingReleaseId = context.summary().mappingReleaseId();
		String sourceVersion = context.population().sourceRegionVersion();
		Optional<PopulationFact> fact = jdbc.query(POPULATION_ROW_SQL, (rs, rowNum) -> new PopulationFact(
				PopulationStatus.valueOf(rs.getString("row_status")), rs.getObject("total_population", Long.class),
				rs.getString("temporal_region_status")), context.population().batchId(), regionCode).stream().findFirst();
		boolean sourceRegionPresent = count(POPULATION_SOURCE_SQL, POPULATION_SYSTEM, regionCode, sourceVersion) > 0;

		RegionState state;
		if (!compatibleRegion(context)) {
			state = RegionState.BOUNDARY_MISMATCH;
		} else if (fact.isPresent()) {
			// 인구 행은 대응이 확정된 원천만 저장되므로, 행이 있으면 시점 판정만 보면 된다.
			state = temporalState(fact.get().temporalStatus());
		} else if (sourceRegionPresent && !verifiedPopulationMapping(mappingReleaseId, regionCode, sourceVersion)) {
			// 원천 지역은 있는데 이 H10으로의 대응이 확인되지 않았다. 행이 없다고 결측으로 숨기지 않는다.
			state = RegionState.MAPPING_UNRESOLVED;
		} else {
			state = RegionState.VALID;
		}

		PopulationRow row = fact.map(f -> new PopulationRow(
				publicPopulationStatus(f, context.population().batchId(), regionCode), f.total())).orElse(null);
		return new Population(state, sourceRegionPresent, row);
	}

	// 수록 업소 수 판정 근거를 만든다. 분류는 현황 묶음의 분류 번호로 읽는다(현재 선택 분류와 다르면 판정기가 보류한다).
	public Stores stores(SummaryReadContext context) {
		String regionCode = context.selection().regionCode();
		String industryCode = context.selection().businessTypeCode();
		long batchId = context.stores().batchId();
		long classificationId = context.summary().classificationId();
		List<CoverageFact> coverages = jdbc.query(STORE_COVERAGE_SQL, (rs, rowNum) -> new CoverageFact(
				rs.getString("review_status"), rs.getString("observed") != null, rs.getString("covered_region"),
				rs.getString("temporal_region_status"), rs.getString("coverage_status")),
				batchId, context.summary().mappingReleaseId(), STORE_SYSTEM, context.stores().sourceRegionVersion(), regionCode);

		List<CoverageFact> observed = coverages.stream().filter(CoverageFact::observed).toList();
		RegionState state = compatibleRegion(context) ? storeState(coverages) : RegionState.BOUNDARY_MISMATCH;
		boolean sourceRegionPresent = !observed.isEmpty();
		boolean coverageValid = sourceRegionPresent && observed.stream()
				.allMatch(c -> "VALID".equals(c.coverageStatus()) && regionCode.equals(c.coveredRegion()));
		boolean countAffected = count(STORE_ISSUE_SQL, batchId, regionCode, classificationId, industryCode) > 0;
		StoreAggregate aggregate = jdbc.query(STORE_COUNT_SQL, (rs, rowNum) -> new StoreAggregate(
				rs.getLong("accepted_store_count"), "HOLD".equals(rs.getString("count_quality_status"))),
				batchId, regionCode, classificationId, industryCode).stream().findFirst().orElse(null);
		return new Stores(state, sourceRegionPresent, coverageValid, countAffected, aggregate);
	}

	// 선택 기준 릴리스와 현황 묶음의 대응 릴리스가 같으면 이미 현행 지역으로 확인됐다. 다르면 대응 릴리스에서도 현행인지 본다.
	// 대응 릴리스에 없는 지역이면 그 시점 통계를 이 지역 값으로 쓸 수 없으므로 경계 불일치다.
	private boolean compatibleRegion(SummaryReadContext context) {
		long mappingReleaseId = context.summary().mappingReleaseId();
		if (mappingReleaseId == context.selection().releaseId()) {
			return true;
		}
		return count(COMPATIBLE_REGION_SQL, mappingReleaseId, context.selection().regionCode()) > 0;
	}

	// 이 H10으로의 인구 원천 대응이 검토 완료(VERIFIED) 상태인지 본다.
	private boolean verifiedPopulationMapping(long mappingReleaseId, String regionCode, String sourceVersion) {
		return jdbc.queryForList(POPULATION_MAPPING_SQL, String.class, mappingReleaseId, POPULATION_SYSTEM, regionCode,
				sourceVersion, regionCode).stream().anyMatch("VERIFIED"::equals);
	}

	// 수치가 AVAILABLE이고 시점도 VALID인데 집계 영향 문제가 기록됐으면 품질 실패로 본다.
	// 비공개·결측·오류는 이미 row_status에, 경계 보류는 temporal 상태에 반영돼 있어 판정기가 먼저 처리한다.
	private PopulationStatus publicPopulationStatus(PopulationFact fact, long batchId, String regionCode) {
		if (fact.status() == PopulationStatus.AVAILABLE && "VALID".equals(fact.temporalStatus())
				&& count(POPULATION_ISSUE_SQL, batchId, regionCode) > 0) {
			return PopulationStatus.INVALID;
		}
		return fact.status();
	}

	// 대응된 업소 원천 지역들 중 가장 우선순위가 높은 대응·경계 상태를 고른다.
	// 순서는 명세의 공개 사유 우선순위(대응 미확정 → 경계 검토 → 경계 불일치)를 따른다.
	private static RegionState storeState(List<CoverageFact> coverages) {
		boolean unresolved = false;
		boolean pending = false;
		boolean mismatch = false;
		for (CoverageFact coverage : coverages) {
			unresolved |= !"VERIFIED".equals(coverage.reviewStatus())
					|| coverage.observed() && coverage.coveredRegion() == null;
			if (coverage.observed()) {
				pending |= "PENDING".equals(coverage.temporalStatus());
				mismatch |= "BOUNDARY_MISMATCH".equals(coverage.temporalStatus());
			}
		}
		if (unresolved) {
			return RegionState.MAPPING_UNRESOLVED;
		}
		if (pending) {
			return RegionState.BOUNDARY_REVIEW_PENDING;
		}
		return mismatch ? RegionState.BOUNDARY_MISMATCH : RegionState.VALID;
	}

	// DB의 시점 상태(VALID/PENDING/BOUNDARY_MISMATCH)를 판정기 입력으로 바꾼다.
	private static RegionState temporalState(String temporalStatus) {
		return switch (temporalStatus) {
			case "VALID" -> RegionState.VALID;
			case "BOUNDARY_MISMATCH" -> RegionState.BOUNDARY_MISMATCH;
			// 대응이 확정된 행의 PENDING은 생성·말소일이나 변경 당일처럼 시점 검토가 끝나지 않은 경우다.
			case "PENDING" -> RegionState.BOUNDARY_REVIEW_PENDING;
			default -> throw new IllegalStateException("알 수 없는 시점 상태입니다: " + temporalStatus);
		};
	}

	// COUNT(*) 한 값을 돌려준다.
	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private record PopulationFact(PopulationStatus status, Long total, String temporalStatus) {
	}

	private record CoverageFact(String reviewStatus, boolean observed, String coveredRegion, String temporalStatus,
			String coverageStatus) {
	}

}
