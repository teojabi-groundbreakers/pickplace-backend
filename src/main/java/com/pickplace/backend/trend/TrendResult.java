package com.pickplace.backend.trend;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.pickplace.backend.region.SelectedLocality;

// 추이 조회 결과다. 응답 JSON으로 바꾸는 일은 TrendResponse가 한다.
// businessTypeName은 전국 수록 업소(소진공 업종)일 때만 있고, 그 밖에는 null이다.
record TrendResult(
		SelectedLocality region,
		TrendRequest request,
		String businessTypeName,
		List<MetricDefinition> metrics,
		List<QuarterTrend> periods) {

	// 지표 하나의 단위와 정의 확인 상태다. 정의는 적재 배치에 고정된 값을 그대로 옮긴다.
	record MetricDefinition(String metricCode, String unit, String definitionStatus) {
	}

	// 한 분기의 결과다. AVAILABLE일 때만 values가 있고, 그 밖에는 null이며 reasonCode로 이유를 알린다.
	record QuarterTrend(String quarter, PeriodStatus status, String reasonCode, Map<String, MetricValue> values) {
	}

	// 지표 값 하나와 그 값의 전 분기·전년 동기 대비 증감이다. 실제 0은 0, 원천 값이 비어 있으면 null과 상태·사유다.
	record MetricValue(BigDecimal value, String status, String reasonCode, QuarterlyChangeCalculator.Changes changes) {
	}

	// 분기 단위 상태다. 지표별 상태와 별개로, 그 분기에 이 지역 값이 있는지를 나타낸다.
	enum PeriodStatus {
		AVAILABLE, NO_DATA, UNAVAILABLE
	}

}
