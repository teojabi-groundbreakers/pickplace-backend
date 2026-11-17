package com.pickplace.backend.ai;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pickplace.backend.analysis.AnalysisConfiguration;
import com.pickplace.backend.analysis.AnalysisOrchestrator;
import com.pickplace.backend.analysis.AnalysisOrchestrator.PublishedRow;
import com.pickplace.backend.analysis.AnalysisOrchestrator.ScopeMapping;
import com.pickplace.backend.analysis.AnalysisOrchestrator.Snapshot;
import com.pickplace.backend.analysis.AnalysisOrchestrator.Statistics;
import com.pickplace.backend.common.web.NoStoreCacheControlFilter;
import com.pickplace.backend.summary.MetricDecision;
import com.pickplace.backend.summary.SummaryReadContext;
import com.pickplace.backend.summary.SummaryService.SummaryResult;

import tools.jackson.databind.json.JsonMapper;

// AI만 실패하거나 지연될 때 공개 분석 응답이 통계를 유지하고 AI 상태만 실패로 알리는지 확인한다.
// 실제 매출·코드 대응 근거가 아직 없으므로, AI 호출까지 가는 승인 입력은 이 테스트 안에서만 만든다(운영 경로에는 없음).
@WebMvcTest(AiAnalysisController.class)
@Import({AnalysisConfiguration.class, NoStoreCacheControlFilter.class, AnalysisFallbackApiTests.Fixture.class})
class AnalysisFallbackApiTests {

	private static final String URL = "/api/ai/analyses";
	private static final String REGION = "1111051500";
	private static final String INDUSTRY = "I21201";

	@Autowired
	private MockMvc http;

	// AI 서버 대신 원하는 통신 결과를 돌려주는 가짜 호출기다.
	@MockitoBean
	private AiPredictClient aiClient;

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixture {

		// 2026-11-13이면 분석 기준 분기는 직전 완료 분기인 2026Q3이다.
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC);
		}

		// 승인된 대응·이력과 현황 판정을 담은 고정 스냅샷이다. 테스트 전용 값이며 실제 자료가 아니다.
		@Bean
		AnalysisOrchestrator.SnapshotReader testSnapshotReader() {
			return query -> snapshot();
		}
	}

	// AI 통신이 실패하면 200 응답에서 통계는 그대로 두고, AI는 FAILED·사유·통신 결과만 알리며 결과는 null인지 확인한다.
	@ParameterizedTest(name = "{0}")
	@MethodSource("failures")
	void aiFailureKeepsStatisticsAndMarksOnlyAiAsFailed(AiPredictionResult.Status failure, String reason) throws Exception {
		when(aiClient.predict(any())).thenReturn(AiPredictionResult.unavailable(failure, reason));

		analyze()
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(statisticsKept())
				.andExpect(jsonPath("$.data.ai.state").value("FAILED"))
				.andExpect(jsonPath("$.data.ai.reason").value(reason))
				.andExpect(jsonPath("$.data.ai.transportStatus").value(failure.name()))
				.andExpect(jsonPath("$.data.ai.result").value(nullValue()));
	}

	// 실패 사례: AI 꺼짐, 인증 실패, 입력 거부, 서버 장애, 시간 초과, 응답 형식 오류.
	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of(AiPredictionResult.Status.DISABLED, "ai_disabled"),
				Arguments.of(AiPredictionResult.Status.AUTH_FAILURE, "ai_authentication_failed"),
				Arguments.of(AiPredictionResult.Status.INVALID_AI_REQUEST, "ai_request_rejected"),
				Arguments.of(AiPredictionResult.Status.UPSTREAM_UNAVAILABLE, "ai_server_unavailable"),
				Arguments.of(AiPredictionResult.Status.TIMEOUT, "ai_timeout"),
				Arguments.of(AiPredictionResult.Status.INVALID_RESPONSE, "ai_response_invalid"));
	}

	// 호출기 자체에서 예상하지 못한 예외가 나도 500이 아니라 통계를 유지한 200과 AI 실패로 응답하는지 확인한다.
	// 예외 메시지(내부 주소 등)는 응답에 나오지 않아야 한다.
	@Test
	void unexpectedAiErrorKeepsStatistics() throws Exception {
		when(aiClient.predict(any())).thenThrow(new IllegalStateException("http://internal-ai:8000 connection reset"));

		analyze()
				.andExpect(status().isOk())
				.andExpect(statisticsKept())
				.andExpect(jsonPath("$.data.ai.state").value("FAILED"))
				.andExpect(jsonPath("$.data.ai.reason").value("ai_request_failed"))
				.andExpect(jsonPath("$.data.ai.transportStatus").value(nullValue()))
				.andExpect(jsonPath("$.data.ai.result").value(nullValue()))
				.andExpect(content().string(not(containsString("internal-ai"))));
	}

	// AI가 정상 응답하면 검증된 결과와 함께 통계도 그대로 나오는지 확인한다(성장 불가·위험도 가능 같은 부분 결과 포함).
	@Test
	void availableAiResultIsReturnedWithStatistics() throws Exception {
		var json = JsonMapper.builder().build().readTree(
				"{\"growth\":{\"status\":\"unavailable\",\"reason\":\"target_not_future\"},\"risk\":{\"status\":\"available\"}}");
		when(aiClient.predict(any())).thenReturn(new AiPredictionResult(AiPredictionResult.Status.AVAILABLE, json, null));

		analyze()
				.andExpect(status().isOk())
				.andExpect(statisticsKept())
				.andExpect(jsonPath("$.data.ai.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.ai.reason").value(nullValue()))
				.andExpect(jsonPath("$.data.ai.transportStatus").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.ai.result.growth.reason").value("target_not_future"))
				.andExpect(jsonPath("$.data.ai.result.risk.status").value("available"));
	}

	// 분석을 요청한다.
	private ResultActions analyze() throws Exception {
		return http.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
				.content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"" + INDUSTRY + "\"}"));
	}

	// 고정 스냅샷의 통계(인구 100명, 업소 수 보류)가 응답에 그대로 있는지 확인한다.
	private static org.springframework.test.web.servlet.ResultMatcher statisticsKept() {
		return result -> {
			jsonPath("$.data.statistics.referenceMonth").value("202610").match(result);
			jsonPath("$.data.statistics.metrics.registeredPopulation.value").value(100).match(result);
			jsonPath("$.data.statistics.metrics.registeredPopulation.status").value("AVAILABLE").match(result);
			jsonPath("$.data.statistics.metrics.listedStoreCount.value").value(nullValue()).match(result);
			jsonPath("$.data.statistics.metrics.listedStoreCount.status").value("UNAVAILABLE").match(result);
			jsonPath("$.data.statistics.metrics.listedStoreCount.reasonCode").value("COUNT_INCOMPLETE").match(result);
		};
	}

	// 현황 판정과 승인된 대응·2026Q3 이력 한 건으로 AI 호출까지 가는 스냅샷을 만든다.
	private static Snapshot snapshot() {
		var context = new SummaryReadContext(
				new SummaryReadContext.Selection(1, 2, REGION, "청운효자동", INDUSTRY, "카페"),
				new SummaryReadContext.PublishedSummary(3, "202610", 2, Instant.parse("2026-11-12T03:00:00Z"), 1, 2),
				new SummaryReadContext.Source(4, "POPULATION_MONTH", "v1", LocalDate.of(2026, 10, 31), null,
						Instant.parse("2026-11-11T03:00:00Z")),
				new SummaryReadContext.Source(5, "STORE_SNAPSHOT", "v1", LocalDate.of(2026, 10, 31), "202610",
						Instant.parse("2026-11-11T03:00:00Z")));
		var population = MetricDecision.available(100);
		var stores = MetricDecision.withoutValue(MetricDecision.Reason.COUNT_INCOMPLETE);
		var statistics = new Statistics("national-summary-202610-r2",
				Map.of("registeredPopulation", population, "listedStoreCount", stores),
				new SummaryResult(context, population, stores));
		var mapping = new ScopeMapping(REGION, INDUSTRY, "11110515", "seoul-adstrd", "CS100010", "test-only-mapping",
				true, true, LocalDate.of(2026, 10, 31), LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
		var observation = new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100010", "2026Q3",
				LocalDate.of(2026, 11, 1), "test-only-sales", BigDecimal.TEN, null, null, null, null, null, null, null);
		var row = new PublishedRow(observation, true, true, LocalDate.of(2026, 10, 31), true, LocalDate.of(2026, 10, 31),
				true, Map.of());
		return new Snapshot(statistics, mapping, List.of(row));
	}

}
