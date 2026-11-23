package com.pickplace.backend.analysis;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서울 게시 행에서 AI 담당자가 지정한 원천 필드만 읽는다. 사용 승인은 별도로 전달해야 한다. */
public final class SeoulAiSourceDecoder {
    private final JsonMapper json;

    public SeoulAiSourceDecoder(JsonMapper json) { this.json = json; }

    public List<SeoulAiHistoryPlanner.Value> decode(SeoulPublishedStatisticsRepository.Row row,
            SeoulAiHistoryPlanner.Mapping mapping,
            Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence> approvedEvidence) {
        if (row == null || mapping == null || approvedEvidence == null || !"VALID".equals(row.rowStatus())
                || !mapping.regionApproved() || !mapping.industryApproved()
                || !row.regionCode().equals(mapping.regionCode())
                || !row.sourceRegionCode().equals(mapping.sourceRegionCode())
                || (!("RESIDENT".equals(row.dataset()) && "".equals(row.sourceIndustryCode()))
                    && !row.sourceIndustryCode().equals(mapping.sourceIndustryCode()))
                || !"VERIFIED".equals(row.mappingStatus()) || !"VALID".equals(row.temporalStatus())) return List.of();
        JsonNode metrics = json.readTree(row.metrics());
        if (!metrics.isObject()) throw new IllegalArgumentException("서울 원천 지표 형식 오류");
        var values = new ArrayList<SeoulAiHistoryPlanner.Value>();
        switch (row.dataset()) {
            case "SALES" -> add(values, row, metrics, approvedEvidence, SeoulAiHistoryPlanner.Kind.SALES, "thsmon_selng_amt", true);
            case "STORE" -> {
                add(values, row, metrics, approvedEvidence, SeoulAiHistoryPlanner.Kind.STORE_COUNT, "similr_induty_stor_co", false);
                add(values, row, metrics, approvedEvidence, SeoulAiHistoryPlanner.Kind.CLOSING_COUNT, "clsbiz_stor_co", false);
            }
            case "RESIDENT" -> add(values, row, metrics, approvedEvidence, SeoulAiHistoryPlanner.Kind.RESIDENT_POPULATION, "tot_repop_co", true);
            default -> { return List.of(); }
        }
        return List.copyOf(values);
    }

    private static void add(List<SeoulAiHistoryPlanner.Value> values, SeoulPublishedStatisticsRepository.Row row,
            JsonNode metrics, Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence> proofs,
            SeoulAiHistoryPlanner.Kind kind, String field, boolean rawSalesAllowed) {
        var proof = proofs.get(kind);
        // 다른 DB 배치에 대한 승인이나 테스트용 가정은 이 행의 승인 근거가 될 수 없다.
        if (proof == null || !proof.aiApproved() || !("batch:" + row.batchId()).equals(proof.version())
                || proof.approvedAt() == null || proof.publishedAt() == null || proof.availableAt() == null
                || row.approvedAt() == null || row.publishedAt() == null || row.collectedAt() == null
                || proof.approvedAt().isBefore(row.approvedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                || proof.publishedAt().isBefore(row.publishedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                || proof.availableAt().isBefore(row.collectedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())) return;
        JsonNode metric = metrics.path(field);
        String status = metric.path("status").asText();
        if (!"AVAILABLE".equals(status) && !(rawSalesAllowed && "UNVERIFIED".equals(status)
                && "SOURCE_DEFINITION_UNREVIEWED".equals(metric.path("reasonCode").asText()))) return;
        JsonNode amount = metric.path("value");
        if (!amount.isNumber()) return;
        BigDecimal number = amount.decimalValue();
        if (number.signum() < 0) return;
        String period = row.quarter().substring(0, 4) + "Q" + row.quarter().substring(4);
        values.add(new SeoulAiHistoryPlanner.Value(kind, period, row.sourceRegionCode(),
                kind == SeoulAiHistoryPlanner.Kind.RESIDENT_POPULATION ? null : row.sourceIndustryCode(), number, proof));
    }
}
