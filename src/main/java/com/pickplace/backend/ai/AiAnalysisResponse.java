package com.pickplace.backend.ai;

import java.time.LocalDate;
import java.util.Map;

import com.pickplace.backend.analysis.AnalysisOrchestrator;
import com.pickplace.backend.summary.MetricDecision;

import tools.jackson.databind.JsonNode;

/** 요청 범위와 통계·AI의 개별 가용성을 함께 전달한다. */
public record AiAnalysisResponse(Request request, LocalDate asOf, String basePeriod,
        Statistics statistics, Ai ai) {

    public record Request(String regionCode, String industryCode) {}

    public record Statistics(String version, Map<String, MetricDecision> metrics) {}

    public record Ai(AnalysisOrchestrator.AiState state, String reason,
            AiPredictionResult.Status transportStatus, JsonNode result) {}

    static AiAnalysisResponse from(AnalysisOrchestrator.Result value) {
        var query = value.request();
        var statistics = value.statistics();
        var outcome = value.ai();
        var prediction = outcome.prediction();
        return new AiAnalysisResponse(
                new Request(query.regionCode(), query.businessTypeCode()),
                query.asOf(), query.basePeriod(),
                new Statistics(statistics.version(), statistics.metrics()),
                new Ai(outcome.state(), outcome.reason(),
                        prediction == null ? null : prediction.status(),
                        prediction == null ? null : prediction.response()));
    }
}
