package com.pickplace.backend.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pickplace.backend.ai.AiPredictClient;
import com.pickplace.backend.ai.AiPredictionRequest;
import com.pickplace.backend.ai.AiPredictionResult;
import tools.jackson.databind.JsonNode;

/** 검증된 입력을 지표별로 호출하고 결과를 서로 독립적으로 보존한다. */
@Service
public class PerIndicatorAnalysisService {
    private final SeoulAiInputPreparationService inputs;
    private final AiPredictClient client;

    public PerIndicatorAnalysisService(SeoulAiInputPreparationService inputs, AiPredictClient client) {
        this.inputs = inputs;
        this.client = client;
    }

    public record Metric(String status, String basePeriod, Double value, String reason) {}
    public record Growth(String status, String basePeriod, Double value, String reason, String targetPeriod, String comparisonPeriod) {}
    public record Metrics(Growth growth, Metric risk, Metric fit) {}
    public record Result(String state, String reason, String transportStatus, Metrics metrics, List<String> warnings,
            SeoulAiInputPreparationService.AnalysisScope analysisScope) {}

    public Result analyze(String region, String industry, String fromQuarter, String toQuarter, LocalDate asOf) {
        var prepared = inputs.prepareApproved(region, industry, fromQuarter, toQuarter, asOf);
        var selections = prepared.selections();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("AI 호출 전에 조회 트랜잭션이 종료되어야 합니다");
        var responses = new HashMap<AiPredictionRequest, AiPredictionResult>();
        var warnings = new LinkedHashSet<String>();
        var results = new HashMap<SeoulAiHistoryPlanner.Metric, Metric>();
        var failures = new ArrayList<String>();
        var transports = new LinkedHashSet<String>();
        for (var type : SeoulAiHistoryPlanner.Metric.values()) {
            var selection = selections.get(type);
            if (selection == null || !selection.available()) {
                results.put(type, new Metric("unavailable", selection == null ? null : selection.basePeriod(), null,
                        selection == null ? "insufficient_verified_data" : selection.reason()));
                continue;
            }
            AiPredictionResult response = responses.computeIfAbsent(selection.request(), request -> {
                try { return client.predict(request); }
                catch (RuntimeException failure) { return AiPredictionResult.unavailable(AiPredictionResult.Status.UPSTREAM_UNAVAILABLE, "ai_request_failed"); }
            });
            if (response == null || response.status() == null || response.status() != AiPredictionResult.Status.AVAILABLE || response.response() == null) {
                String reason = response == null || response.reason() == null ? "invalid_ai_response" : response.reason();
                results.put(type, new Metric("unavailable", selection.basePeriod(), null, reason));
                failures.add(reason);
                if (response != null && response.status() != null) transports.add(response.status().name());
                continue;
            }
            transports.add(response.status().name());
            JsonNode payload = response.response();
            for (JsonNode warning : payload.path("warnings")) if (warning.isString() && warnings.size() < 32) warnings.add(warning.asText());
            String field = switch (type) { case GROWTH -> "growth"; case RISK -> "risk"; case FIT -> "fit"; };
            JsonNode item = payload.path(field);
            String itemStatus = item.path("status").asText();
            JsonNode value = item.path(type == SeoulAiHistoryPlanner.Metric.GROWTH ? "estimated_yoy_growth" : "score");
            if ("available".equals(itemStatus) && value.isNumber() && Double.isFinite(value.asDouble())) {
                results.put(type, new Metric("available", selection.basePeriod(), value.asDouble(), null));
            } else if ("unavailable".equals(itemStatus) && item.path("reason").isString()
                    && !item.path("reason").asText().isBlank()) {
                results.put(type, new Metric("unavailable", selection.basePeriod(), null, item.path("reason").asText()));
            } else {
                results.put(type, new Metric("unavailable", selection.basePeriod(), null, "invalid_ai_response"));
                failures.add("invalid_ai_response");
            }
        }
        var growthMetric = results.get(SeoulAiHistoryPlanner.Metric.GROWTH);
        var growthSelection = selections.get(SeoulAiHistoryPlanner.Metric.GROWTH);
        String target = growthSelection == null ? null : growthSelection.targetPeriod();
        String comparison = target == null ? null : shift(target, -4);
        var metrics = new Metrics(new Growth(growthMetric.status(), growthMetric.basePeriod(), growthMetric.value(),
                growthMetric.reason(), target, comparison), results.get(SeoulAiHistoryPlanner.Metric.RISK),
                results.get(SeoulAiHistoryPlanner.Metric.FIT));
        boolean available = results.values().stream().anyMatch(item -> "available".equals(item.status()));
        String state = available ? "AVAILABLE" : failures.isEmpty() ? "SKIPPED" : "FAILED";
        String reason = available ? null : failures.isEmpty() ? results.get(SeoulAiHistoryPlanner.Metric.FIT).reason() : failures.getFirst();
        return new Result(state, reason, transports.size() == 1 ? transports.iterator().next() : null,
                metrics, List.copyOf(warnings), available ? prepared.scope() : null);
    }

    private static String shift(String period, int offset) {
        int index = Integer.parseInt(period.substring(0, 4)) * 4 + period.charAt(5) - '1' + offset;
        return (index / 4) + "Q" + (index % 4 + 1);
    }
}
