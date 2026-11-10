package com.pickplace.backend.summary;

import com.pickplace.backend.common.web.RequestIds;
import com.pickplace.backend.summary.SummaryReadContext.Source;
import com.pickplace.backend.summary.SummaryService.SummaryResult;

// 현황 응답 {data: {region, businessType, metrics}, meta: {requestId, referenceMonth, publishedAt}}이다.
// 값이 없는 키(null)도 지우지 않고 그대로 내보낸다. FE가 키 존재 여부로 분기하지 않게 하기 위해서다.
public record SummaryResponse(Data data, Meta meta) {

	static final String PERSON = "PERSON";
	static final String STORE = "STORE";

	public record Data(Region region, BusinessType businessType, Metrics metrics) {
	}

	public record Region(String regionCode, String regionName) {
	}

	public record BusinessType(String businessTypeCode, String businessTypeName) {
	}

	public record Metrics(Metric registeredPopulation, Metric listedStoreCount) {
	}

	// value는 AVAILABLE일 때만 숫자이고(0 포함), 그 밖에는 null이며 reasonCode로 이유를 알린다.
	public record Metric(Long value, String unit, MetricDecision.Status status, MetricDecision.Reason reasonCode,
			MetricSource source) {
	}

	// 결측·보류여도 선택된 배치의 출처는 그대로 보여준다. 수집일·게시일을 원천 기준일로 바꾸지 않는다.
	public record MetricSource(String datasetCode, String referenceOn, String snapshotLabel, String collectedAt) {
	}

	// referenceMonth는 서버가 고른 DEFAULT 묶음의 월이다. 사용자가 고른 월이 아니다.
	public record Meta(String requestId, String referenceMonth, String publishedAt) {
	}

	// 판정 결과를 응답 형식으로 바꾼다.
	// 날짜·시각은 JSON 설정에 따라 숫자로 바뀔 수 있어 "2026-09-30", "2026-11-09T03:52:31Z" 문자열로 고정한다.
	public static SummaryResponse of(SummaryResult result) {
		SummaryReadContext context = result.context();
		var selection = context.selection();
		var data = new Data(
				new Region(selection.regionCode(), selection.regionName()),
				new BusinessType(selection.businessTypeCode(), selection.businessTypeName()),
				new Metrics(metric(result.population(), PERSON, context.population()),
						metric(result.stores(), STORE, context.stores())));
		var meta = new Meta(RequestIds.current(), context.summary().referenceMonth(), context.summary().publishedAt().toString());
		return new SummaryResponse(data, meta);
	}

	// 지표 판정 하나를 단위·출처와 함께 응답 항목으로 바꾼다.
	private static Metric metric(MetricDecision decision, String unit, Source source) {
		var metricSource = new MetricSource(source.datasetCode(), source.referenceOn().toString(), source.snapshotLabel(),
				source.collectedAt().toString());
		return new Metric(decision.value(), unit, decision.status(), decision.reasonCode(), metricSource);
	}

}
