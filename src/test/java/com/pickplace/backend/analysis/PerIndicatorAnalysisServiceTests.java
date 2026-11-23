package com.pickplace.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.pickplace.backend.ai.AiPredictClient;
import com.pickplace.backend.ai.AiPredictionRequest;
import com.pickplace.backend.ai.AiPredictionResult;
import tools.jackson.databind.json.JsonMapper;

class PerIndicatorAnalysisServiceTests {
    private final SeoulAiInputPreparationService inputs = mock(SeoulAiInputPreparationService.class);
    private final AiPredictClient client = mock(AiPredictClient.class);
    private final PerIndicatorAnalysisService service = new PerIndicatorAnalysisService(inputs, client);
    private final LocalDate asOf = LocalDate.parse("2026-11-19");

    private AiPredictionRequest request(String quarter) {
        var row = new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100010", quarter,
                LocalDate.parse("2026-10-01"), "fixture", BigDecimal.TEN, 10L, null, 1L,
                BigDecimal.valueOf(100), null, null, BigDecimal.ONE);
        return new AiPredictionRequest("11110515", "seoul-adstrd", "CS100010", quarter, asOf, true, List.of(row));
    }

    @Test
    void keeps_latest_fit_when_older_risk_call_fails() {
        var risk = request("2026Q2");
        var fit = request("2026Q3");
        var scope = new SeoulAiInputPreparationService.AnalysisScope("11110515", "CS100010", "커피 전문점",
                AiInputApprovalRepository.IndustryMatch.PARTIAL);
        when(inputs.prepareApproved("1111051500", "I21201", "20251", "20263", asOf)).thenReturn(
                new SeoulAiInputPreparationService.PreparedAnalysis(scope, Map.of(
                SeoulAiHistoryPlanner.Metric.GROWTH, new SeoulAiHistoryPlanner.Selection("2026Q2", "2026Q3", "target_not_future", null),
                SeoulAiHistoryPlanner.Metric.RISK, new SeoulAiHistoryPlanner.Selection("2026Q2", null, null, risk),
                SeoulAiHistoryPlanner.Metric.FIT, new SeoulAiHistoryPlanner.Selection("2026Q3", null, null, fit))));
        when(client.predict(risk)).thenReturn(AiPredictionResult.unavailable(AiPredictionResult.Status.TIMEOUT, "ai_timeout"));
        var payload = JsonMapper.builder().build().readTree("{\"fit\":{\"status\":\"available\",\"score\":65},\"warnings\":[\"draft\"]}");
        when(client.predict(fit)).thenReturn(new AiPredictionResult(AiPredictionResult.Status.AVAILABLE, payload, null));
        var result = service.analyze("1111051500", "I21201", "20251", "20263", asOf);
        assertThat(result.state()).isEqualTo("AVAILABLE");
        assertThat(result.analysisScope()).isEqualTo(scope);
        assertThat(JsonMapper.builder().build().valueToTree(result.analysisScope()).path("referenceOnly").asBoolean()).isTrue();
        assertThat(result.metrics().risk().reason()).isEqualTo("ai_timeout");
        assertThat(result.metrics().fit().basePeriod()).isEqualTo("2026Q3");
        assertThat(result.metrics().fit().value()).isEqualTo(65);
        assertThat(result.metrics().growth().targetPeriod()).isEqualTo("2026Q3");
        assertThat(result.metrics().growth().comparisonPeriod()).isEqualTo("2025Q3");
        verify(client, times(2)).predict(any());
    }

    @Test
    void skips_ai_when_all_inputs_are_unapproved() {
        var unavailable = new SeoulAiHistoryPlanner.Selection(null, null, "mapping_unverified", null);
        when(inputs.prepareApproved("1111051500", "I21201", "20251", "20263", asOf)).thenReturn(
                new SeoulAiInputPreparationService.PreparedAnalysis(null, Map.of(
                SeoulAiHistoryPlanner.Metric.GROWTH, unavailable, SeoulAiHistoryPlanner.Metric.RISK, unavailable,
                SeoulAiHistoryPlanner.Metric.FIT, unavailable)));
        var result = service.analyze("1111051500", "I21201", "20251", "20263", asOf);
        assertThat(result.state()).isEqualTo("SKIPPED");
        assertThat(result.analysisScope()).isNull();
        assertThat(result.metrics().fit().reason()).isEqualTo("mapping_unverified");
        verifyNoInteractions(client);
    }
}
