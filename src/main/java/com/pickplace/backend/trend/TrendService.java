package com.pickplace.backend.trend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.businesstype.BusinessTypeService;
import com.pickplace.backend.region.RegionService;
import com.pickplace.backend.region.SelectedLocality;
import com.pickplace.backend.trend.QuarterlyChangeCalculator.Observation;
import com.pickplace.backend.trend.QuarterlyChangeCalculator.ObservationStatus;
import com.pickplace.backend.trend.TrendDataset.IndustryKind;
import com.pickplace.backend.trend.TrendQueryRepository.Publication;
import com.pickplace.backend.trend.TrendQueryRepository.PublishedRow;
import com.pickplace.backend.trend.TrendQueryRepository.WithheldRow;
import com.pickplace.backend.trend.TrendResult.MetricDefinition;
import com.pickplace.backend.trend.TrendResult.MetricValue;
import com.pickplace.backend.trend.TrendResult.PeriodStatus;
import com.pickplace.backend.trend.TrendResult.QuarterTrend;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 한 지역(·업종)의 상세 통계를 요청 범위의 분기마다 만들고, 값이 있는 지표에 전 분기·전년 동기 대비 증감을 붙인다.
// 형식 검사는 Controller가 먼저 한다.
// 지역·업종 확인, 게시 포인터, 값을 같은 읽기 시점에서 보도록 REPEATABLE_READ 읽기 전용 트랜잭션 하나로 묶는다.
// 그래야 조회 도중 새 분기가 게시돼도 한 응답 안에서 포인터와 값이 서로 다른 배치를 가리키지 않는다.
@Service
public class TrendService {

	// 전년 동기와 비교하려면 범위 첫 분기보다 4분기 앞까지 읽어야 한다. 이 분기들은 비교 기준으로만 쓰고 응답에는 넣지 않는다.
	private static final int LOOKBACK_QUARTERS = 4;

	private static final String APPROVED = "APPROVED";
	private static final String CURRENT = "CURRENT";
	private static final String VALID = "VALID";
	private static final String METRIC_AVAILABLE = "AVAILABLE";
	private static final String METRIC_NO_DATA = "NO_DATA";

	static final String NOT_PUBLISHED = "NOT_PUBLISHED";
	static final String SOURCE_REGION_ABSENT = "SOURCE_REGION_ABSENT";
	static final String SOURCE_ROW_ABSENT = "SOURCE_ROW_ABSENT";
	static final String SOURCE_FIELD_ABSENT = "SOURCE_FIELD_ABSENT";
	static final String SOURCE_VALUE_MISSING = "SOURCE_VALUE_MISSING";
	static final String MAPPING_UNRESOLVED = "MAPPING_UNRESOLVED";
	static final String BOUNDARY_REVIEW_PENDING = "BOUNDARY_REVIEW_PENDING";
	static final String BOUNDARY_MISMATCH = "BOUNDARY_MISMATCH";
	static final String QUALITY_CHECK_FAILED = "QUALITY_CHECK_FAILED";

	// 보류 사유가 여러 개면 앞쪽을 알린다. 현황 API의 사유 우선순위와 같은 순서다.
	private static final List<String> WITHHELD_PRIORITY = List.of(
			MAPPING_UNRESOLVED, BOUNDARY_REVIEW_PENDING, BOUNDARY_MISMATCH, QUALITY_CHECK_FAILED, SOURCE_VALUE_MISSING);

	private final RegionService regionService;
	private final BusinessTypeService businessTypeService;
	private final TrendQueryRepository repository;
	private final JsonMapper jsonMapper;

	public TrendService(RegionService regionService, BusinessTypeService businessTypeService, TrendQueryRepository repository,
			JsonMapper jsonMapper) {
		this.regionService = regionService;
		this.businessTypeService = businessTypeService;
		this.repository = repository;
		this.jsonMapper = jsonMapper;
	}

	// 지역·업종을 확인하고, 요청 범위의 모든 분기를 시간순으로 값(과 증감) 또는 상태·사유로 채워 돌려준다.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TrendResult getTrend(TrendRequest request) {
		// 현재 선택 목록의 읍·면·동이 아니면 여기서 404, 지역 기준정보 미게시면 503이다.
		SelectedLocality region = regionService.getSelectableLocality(request.regionCode());
		// 소진공 업종은 업종 목록 API와 같은 기준으로 확인한다. 분류에 없으면 404, 지원하지 않으면 422다.
		// 서울 CS 업종은 공개 업종 목록이 따로 없어 형식만 검사하고, 원천에 없으면 분기마다 행 없음으로 나온다.
		String businessTypeName = request.dataset().industryKind() == IndustryKind.SEMAS
				? businessTypeService.getSupportedBusinessType(request.industryCode()).businessTypeName()
				: null;

		List<String> requested = request.quarters();
		String from = Quarters.shift(requested.getFirst(), -LOOKBACK_QUARTERS);
		String to = requested.getLast();
		List<String> quarters = Quarters.between(from, to);
		String industryCode = request.storedIndustryCode();
		TrendDataset dataset = request.dataset();

		Map<String, Publication> publications = new HashMap<>();
		for (Publication publication : repository.findPublications(dataset, region.regionCode(), from, to)) {
			checkPublication(dataset, publication);
			publications.put(publication.quarter(), publication);
		}
		Map<String, PublishedRow> publishedRows = new HashMap<>();
		for (PublishedRow row : repository.findPublishedRows(dataset, region.regionCode(), industryCode, from, to)) {
			// 원천 지역과 H10은 1:1로 대응돼야 한다. 한 분기에 정상 행이 둘이면 어느 값을 보여줄지 정할 수 없다.
			if (publishedRows.put(row.quarter(), row) != null) {
				throw new IllegalStateException("한 지역·업종·분기에 게시된 정상 행이 여러 개입니다. quarter=" + row.quarter());
			}
		}
		Map<String, List<String>> withheldReasons = new HashMap<>();
		for (WithheldRow row : repository.findWithheldRows(dataset, region.regionCode(), industryCode, from, to)) {
			withheldReasons.computeIfAbsent(row.quarter(), quarter -> new ArrayList<>()).add(row.reasonCode());
		}

		// 분기마다 상태와 지표 정의·저장 값을 먼저 정한다. 비교 기준 분기도 같은 규칙으로 판정해야 증감이 공정하다.
		Map<Long, Boolean> compatibleReleases = new HashMap<>();
		Map<String, QuarterState> states = new HashMap<>();
		for (String quarter : quarters) {
			states.put(quarter, quarterState(quarter, region, publications.get(quarter), publishedRows.get(quarter),
					withheldReasons.getOrDefault(quarter, List.of()), compatibleReleases));
		}

		List<MetricDefinition> metrics = metricDefinitions(requested, states);
		List<QuarterTrend> periods = new ArrayList<>();
		for (String quarter : requested) {
			periods.add(quarterTrend(quarter, states, metrics, dataset, industryCode));
		}
		return new TrendResult(region, request, businessTypeName, metrics, periods);
	}

	// 한 분기의 상태를 정한다. 미게시 → 경계 불일치 → 정상 값 → 보류 사유 → 원천 지역 밖 → 행 없음 순서로 본다.
	private QuarterState quarterState(String quarter, SelectedLocality region, Publication publication, PublishedRow row,
			List<String> withheld, Map<Long, Boolean> compatibleReleases) {
		// 게시되지 않은 분기는 원천에 자료가 없는 것인지 아직 게시 전인지 알 수 없으므로 행 없음과 구분한다.
		if (publication == null) {
			return QuarterState.unavailable(NOT_PUBLISHED);
		}
		boolean compatible = compatibleReleases.computeIfAbsent(publication.mappingReleaseId(),
				id -> compatibleRegion(id, region));
		if (!compatible) {
			return QuarterState.unavailable(BOUNDARY_MISMATCH);
		}
		if (row != null) {
			if (row.batchId() != publication.batchId() || !withheld.isEmpty()) {
				// 같은 지역에 정상 행과 보류 행이 함께 있으면 원천 지역 둘이 한 H10에 붙은 것이다. 일부 값만 보여주지 않는다.
				throw new IllegalStateException("게시 행과 포인터·보류 기록이 맞지 않습니다. quarter=" + quarter);
			}
			return new QuarterState(PeriodStatus.AVAILABLE, null, publication, definitions(publication),
					jsonMapper.readTree(row.metrics()));
		}
		if (!withheld.isEmpty()) {
			String reason = withheldReason(withheld);
			// 값이 비어 보류된 행은 원천 결측이고, 대응·경계·품질 문제는 숫자를 확정할 수 없는 상태다.
			PeriodStatus status = SOURCE_VALUE_MISSING.equals(reason) ? PeriodStatus.NO_DATA : PeriodStatus.UNAVAILABLE;
			return new QuarterState(status, reason, publication, definitions(publication), null);
		}
		// 원천 대응표에 없는 지역(예: 서울 자료의 서울 밖 지역)은 원천이 수록하지 않는 지역이다. 값 0을 만들지 않는다.
		String reason = publication.regionCovered() ? SOURCE_ROW_ABSENT : SOURCE_REGION_ABSENT;
		return new QuarterState(PeriodStatus.NO_DATA, reason, publication, definitions(publication), null);
	}

	// 요청 분기 하나를 응답용 결과로 만든다. 값이 있으면 지표마다 증감을 계산해 붙인다.
	private static QuarterTrend quarterTrend(String quarter, Map<String, QuarterState> states, List<MetricDefinition> metrics,
			TrendDataset dataset, String industryCode) {
		QuarterState state = states.get(quarter);
		if (state.status() != PeriodStatus.AVAILABLE) {
			return new QuarterTrend(quarter, state.status(), state.reasonCode(), null);
		}
		Map<String, MetricValue> values = new LinkedHashMap<>();
		for (MetricDefinition metric : metrics) {
			JsonNode item = state.storedMetric(metric.metricCode());
			var changes = QuarterlyChangeCalculator.calculate(Quarters.toCalculatorQuarter(quarter),
					observations(metric.metricCode(), states, dataset, industryCode));
			if (item == null) {
				values.put(metric.metricCode(), new MetricValue(null, METRIC_NO_DATA, SOURCE_FIELD_ABSENT, changes));
				continue;
			}
			values.put(metric.metricCode(), new MetricValue(number(item.path("value")), text(item, "status"),
					nullableText(item, "reasonCode"), changes));
		}
		return new QuarterTrend(quarter, PeriodStatus.AVAILABLE, null, values);
	}

	// 증감 계산기에 넘길 분기별 관측값을 만든다. 범위 앞 비교 기준 분기도 포함한다.
	private static Map<QuarterlyChangeCalculator.Quarter, Observation> observations(String metricCode,
			Map<String, QuarterState> states, TrendDataset dataset, String industryCode) {
		Map<QuarterlyChangeCalculator.Quarter, Observation> observations = new HashMap<>();
		states.forEach((quarter, state) -> observations.put(Quarters.toCalculatorQuarter(quarter),
				observation(metricCode, state, dataset, industryCode)));
		return observations;
	}

	// 한 분기·지표의 관측 상태를 정한다.
	// 정의가 확인된(AVAILABLE) 값만 비교에 쓴다. 정의 미확인(UNVERIFIED) 값으로 증감을 만들면 의미를 단정하게 되기 때문이다.
	// 비교 범위에는 원천·자료·업종 체계·업종·대응 릴리스(지역 경계)·지표 정의·단위를 넣어, 하나라도 다르면 비교하지 않게 한다.
	private static Observation observation(String metricCode, QuarterState state, TrendDataset dataset, String industryCode) {
		if (state.status() == PeriodStatus.NO_DATA) {
			return new Observation(null, ObservationStatus.MISSING, null);
		}
		if (state.status() == PeriodStatus.UNAVAILABLE) {
			return new Observation(null, ObservationStatus.UNAVAILABLE, null);
		}
		JsonNode item = state.storedMetric(metricCode);
		MetricDefinition definition = state.definitions().get(metricCode);
		if (item == null || definition == null) {
			return new Observation(null, ObservationStatus.MISSING, null);
		}
		BigDecimal value = number(item.path("value"));
		String status = nullableText(item, "status");
		if (value == null || METRIC_NO_DATA.equals(status)) {
			return new Observation(null, ObservationStatus.MISSING, null);
		}
		if (!METRIC_AVAILABLE.equals(status)) {
			return new Observation(null, ObservationStatus.UNAVAILABLE, null);
		}
		String scope = String.join("|", dataset.sourceSystem(), dataset.storedDatasetCode(), dataset.industryKind().scheme(),
				industryCode, String.valueOf(state.publication().mappingReleaseId()), definition.metricCode(), definition.unit(),
				definition.definitionStatus());
		return new Observation(value, ObservationStatus.AVAILABLE, scope);
	}

	// 게시 포인터가 승인된 배치, 그리고 이 자료에 맞는 업종 체계를 가리키는지 확인한다.
	// 적재·게시 절차상 일어나면 안 되는 내부 정합성 오류라 500으로 알린다. 다른 배치로 대신 읽지 않는다.
	private static void checkPublication(TrendDataset dataset, Publication publication) {
		if (!APPROVED.equals(publication.batchStatus())) {
			throw new IllegalStateException("게시 포인터가 승인되지 않은 배치를 가리킵니다. batchId=" + publication.batchId());
		}
		if (!dataset.industryKind().scheme().equals(publication.industryScheme())) {
			throw new IllegalStateException("게시 배치의 업종 체계가 자료와 다릅니다. batchId=" + publication.batchId());
		}
	}

	// 대응표를 만든 기준정보 릴리스가 지금 선택 릴리스와 다르면, 그 릴리스에서도 이 지역이 현행인지 확인한다.
	// 현황 API와 같은 기준이다. 현행이 아니면 원천 지역과 지금 선택한 지역의 경계가 같다고 볼 수 없다.
	private boolean compatibleRegion(long mappingReleaseId, SelectedLocality region) {
		if (mappingReleaseId == region.releaseId()) {
			return true;
		}
		return repository.findNode(mappingReleaseId, region.regionCode())
				.map(node -> CURRENT.equals(node.officialStatus()) && VALID.equals(node.dateQualityStatus()))
				.orElse(false);
	}

	// 요청 범위 안 게시 배치들의 지표 정의를 분기 순서대로 모은다. 게시된 분기가 없으면 빈 목록이다.
	// 같은 지표의 단위·정의 상태가 분기마다 다르면 한 추이로 비교할 수 없으므로 내부 오류로 알린다.
	// 범위 앞 비교 기준 분기는 응답에 나오지 않으므로 여기서 모으지 않고, 정의가 다르면 증감만 비교 불가가 된다.
	private static List<MetricDefinition> metricDefinitions(List<String> requested, Map<String, QuarterState> states) {
		Map<String, MetricDefinition> definitions = new LinkedHashMap<>();
		for (String quarter : requested) {
			for (MetricDefinition definition : states.get(quarter).definitions().values()) {
				MetricDefinition previous = definitions.putIfAbsent(definition.metricCode(), definition);
				if (previous != null && !previous.equals(definition)) {
					throw new IllegalStateException("분기마다 지표 정의가 다릅니다. metric=" + definition.metricCode());
				}
			}
		}
		return List.copyOf(definitions.values());
	}

	// 게시 배치에 고정된 지표 정의(JSON 배열)를 지표 코드 순서대로 읽는다.
	private Map<String, MetricDefinition> definitions(Publication publication) {
		JsonNode list = jsonMapper.readTree(publication.metricDefinitions());
		if (list == null || !list.isArray() || list.isEmpty()) {
			throw new IllegalStateException("게시 배치에 지표 정의가 없습니다. batchId=" + publication.batchId());
		}
		Map<String, MetricDefinition> definitions = new LinkedHashMap<>();
		for (JsonNode item : list) {
			MetricDefinition definition = new MetricDefinition(text(item, "field"), text(item, "unit"), text(item, "definitionStatus"));
			definitions.put(definition.metricCode(), definition);
		}
		return definitions;
	}

	// 여러 보류 사유 중 우선순위가 가장 높은 것을 고른다. 알 수 없는 사유·중복 충돌·키 오류는 품질 검증 실패로 묶는다.
	private static String withheldReason(List<String> reasons) {
		return reasons.stream()
				.map(reason -> WITHHELD_PRIORITY.contains(reason) ? reason : QUALITY_CHECK_FAILED)
				.min((a, b) -> Integer.compare(WITHHELD_PRIORITY.indexOf(a), WITHHELD_PRIORITY.indexOf(b)))
				.orElseThrow();
	}

	// 저장된 숫자를 그대로 옮긴다. 정수는 정수로, 비율 같은 소수는 소수로 둔다. 값이 없으면 null이다.
	private static BigDecimal number(JsonNode value) {
		if (value == null || value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isNumber()) {
			throw new IllegalStateException("지표 값이 숫자가 아닙니다.");
		}
		return value.isIntegralNumber() ? BigDecimal.valueOf(value.longValue()) : value.decimalValue();
	}

	// 반드시 있어야 하는 문자열 항목을 꺼낸다. 없으면 적재 정의가 깨진 것이다.
	private static String text(JsonNode node, String field) {
		String value = nullableText(node, field);
		if (value == null) {
			throw new IllegalStateException("저장된 지표 정보에 " + field + " 항목이 없습니다.");
		}
		return value;
	}

	// 문자열 항목을 꺼내고, 없거나 null이면 null을 돌려준다.
	private static String nullableText(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : null;
	}

	// 한 분기의 판정 결과다. 게시된 분기면 그 배치와 지표 정의를, 정상 행이 있으면 저장된 지표 JSON을 함께 둔다.
	private record QuarterState(PeriodStatus status, String reasonCode, Publication publication,
			Map<String, MetricDefinition> definitions, JsonNode storedMetrics) {

		// 값을 확정할 수 없는 분기(미게시·경계 불일치)의 판정을 만든다. 지표 정의도 쓰지 않는다.
		static QuarterState unavailable(String reason) {
			return new QuarterState(PeriodStatus.UNAVAILABLE, reason, null, Map.of(), null);
		}

		// 정상 행의 지표 JSON에서 지표 하나를 꺼낸다. 행이 없거나 그 지표가 없으면 null이다.
		JsonNode storedMetric(String metricCode) {
			if (storedMetrics == null) {
				return null;
			}
			JsonNode item = storedMetrics.path(metricCode);
			return item.isMissingNode() || item.isNull() ? null : item;
		}
	}

}
