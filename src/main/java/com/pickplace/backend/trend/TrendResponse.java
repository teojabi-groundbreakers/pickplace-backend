package com.pickplace.backend.trend;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pickplace.backend.common.web.RequestIds;
import com.pickplace.backend.trend.TrendResult.MetricDefinition;
import com.pickplace.backend.trend.TrendResult.MetricValue;
import com.pickplace.backend.trend.TrendResult.PeriodStatus;
import com.pickplace.backend.trend.TrendResult.QuarterTrend;

// 추이 응답 {data: {region, dataset, metrics, periods}, meta: {requestId}}이다. 값이 있는 지표에는 증감(changes)이 붙는다.
// 값이 없는 키(null)도 지우지 않고 그대로 내보낸다. FE가 키 존재 여부로 분기하지 않게 하기 위해서다.
public record TrendResponse(Data data, Meta meta) {

	static final String PERIOD_TYPE = "QUARTER";

	public record Data(Region region, Dataset dataset, List<Metric> metrics, List<Period> periods) {
	}

	public record Region(String regionCode, String regionName) {
	}

	// 어떤 원천·자료·업종의 추이인지 알려준다. 업종은 자료의 업종 체계에 맞는 쪽 하나만 있고 나머지는 null이다.
	// industryCode는 서울 CS 업종, businessTypeCode·businessTypeName은 소진공 업종(현재 승인 분류의 표시명)이다.
	public record Dataset(String sourceSystem, String datasetCode, String industryCode, String businessTypeCode,
			String businessTypeName, String periodType) {
	}

	// 지표 코드는 원천 필드 이름 그대로다. definitionStatus가 VERIFIED가 아니면 원천 집계 정의를 아직 확인하지 않은 지표다.
	public record Metric(String metricCode, String unit, String definitionStatus) {
	}

	// 분기 하나다. AVAILABLE일 때만 values가 지표 코드별 값이고, 그 밖에는 null이며 reasonCode로 이유를 알린다.
	public record Period(String quarter, PeriodStatus status, String reasonCode, Map<String, Value> values) {
	}

	// 지표 값 하나와 증감이다. 실제 0은 0이고, 원천 값이 비어 있으면 null과 상태·사유다.
	public record Value(BigDecimal value, String status, String reasonCode, Changes changes) {
	}

	// 전 분기 대비·전년 동기 대비 증감이다.
	public record Changes(Change previousQuarter, Change sameQuarterLastYear) {
	}

	// 증감 하나다. 비교할 수 없으면 두 값이 null이고 status로 이유를 알린다. 기준값이 0이면 증감률만 null이다.
	public record Change(BigDecimal absoluteChange, BigDecimal changeRatePercent, QuarterlyChangeCalculator.ChangeStatus status) {
	}

	public record Meta(String requestId) {
	}

	// 조회 결과를 응답 형식으로 바꾼다.
	static TrendResponse of(TrendResult result) {
		var data = new Data(
				new Region(result.region().regionCode(), result.region().regionName()),
				dataset(result),
				result.metrics().stream().map(TrendResponse::metric).toList(),
				result.periods().stream().map(TrendResponse::period).toList());
		return new TrendResponse(data, new Meta(RequestIds.current()));
	}

	// 자료·업종 정보를 만든다. 업종 코드는 자료의 업종 체계에 맞는 필드에만 넣는다.
	private static Dataset dataset(TrendResult result) {
		TrendRequest request = result.request();
		TrendDataset dataset = request.dataset();
		boolean semas = dataset.industryKind() == TrendDataset.IndustryKind.SEMAS;
		return new Dataset(dataset.sourceSystem(), dataset.name(), semas ? null : request.industryCode(),
				semas ? request.industryCode() : null, result.businessTypeName(), PERIOD_TYPE);
	}

	// 지표 정의 하나를 응답 항목으로 바꾼다.
	private static Metric metric(MetricDefinition definition) {
		return new Metric(definition.metricCode(), definition.unit(), definition.definitionStatus());
	}

	// 계산기 결과를 응답 항목으로 바꾼다.
	private static Changes changes(QuarterlyChangeCalculator.Changes changes) {
		return new Changes(change(changes.previousQuarter()), change(changes.sameQuarterLastYear()));
	}

	// 증감 하나를 응답 항목으로 바꾼다.
	private static Change change(QuarterlyChangeCalculator.Change change) {
		return new Change(change.absoluteChange(), change.changeRatePercent(), change.status());
	}

	// 분기 결과 하나를 응답 항목으로 바꾼다. 지표 순서는 metrics 목록 순서를 따른다.
	private static Period period(QuarterTrend trend) {
		Map<String, Value> values = null;
		if (trend.values() != null) {
			values = new LinkedHashMap<>();
			for (Map.Entry<String, MetricValue> entry : trend.values().entrySet()) {
				MetricValue value = entry.getValue();
				values.put(entry.getKey(), new Value(value.value(), value.status(), value.reasonCode(), changes(value.changes())));
			}
		}
		return new Period(trend.quarter(), trend.status(), trend.reasonCode(), values);
	}

}
