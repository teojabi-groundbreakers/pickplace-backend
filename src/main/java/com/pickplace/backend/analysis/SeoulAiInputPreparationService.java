package com.pickplace.backend.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

/** 승인 근거를 전달받아 서울 게시 원천과 보조 지표를 하나의 읽기 시점에서 조립한다. */
@Service
public class SeoulAiInputPreparationService {
    private final SeoulPublishedStatisticsRepository source;
    private final AiInputApprovalRepository approvals;
    private final SeoulAiSourceDecoder decoder;
    private final SeoulAiHistoryPlanner planner = new SeoulAiHistoryPlanner();

    public record AnalysisScope(String sourceRegionCode, String sourceIndustryCode, String sourceIndustryName,
            AiInputApprovalRepository.IndustryMatch industryMatch) {
        @JsonProperty("referenceOnly")
        public boolean referenceOnly() {
            return industryMatch == AiInputApprovalRepository.IndustryMatch.PARTIAL;
        }
    }

    public record PreparedAnalysis(AnalysisScope scope, Map<SeoulAiHistoryPlanner.Metric, SeoulAiHistoryPlanner.Selection> selections) {}

    public SeoulAiInputPreparationService(SeoulPublishedStatisticsRepository source, AiInputApprovalRepository approvals, JsonMapper json) {
        this.source = source;
        this.approvals = approvals;
        this.decoder = new SeoulAiSourceDecoder(json);
    }

    /** 현재 게시된 기준정보에 연결된 별도 AI 승인 기록이 없으면 항상 보류한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreparedAnalysis prepareApproved(
            String regionCode, String businessTypeCode, String fromQuarter, String toQuarter, LocalDate asOf) {
        // 공개 API는 H10으로 지역을 조회한다. 현재 상세 AI 입력 범위는 서울(시도 코드 11)이다.
        if (regionCode != null && regionCode.matches("[0-9]{10}") && !regionCode.startsWith("11")) {
            var skipped = new SeoulAiHistoryPlanner.Selection(null, null, "region_not_supported", null);
            return new PreparedAnalysis(null, Map.of(
                    SeoulAiHistoryPlanner.Metric.GROWTH, skipped,
                    SeoulAiHistoryPlanner.Metric.RISK, skipped,
                    SeoulAiHistoryPlanner.Metric.FIT, skipped));
        }
        var approved = approvals.findScope(regionCode, businessTypeCode, asOf);
        if (approved.isEmpty()) return new PreparedAnalysis(null, planner.prepare(null, List.of(), asOf));
        var review = approved.get();
        var mapping = review.mapping();
        var selections = prepare(mapping, fromQuarter, toQuarter, asOf,
                approvals.findMetricEvidence(review.reviewId(), asOf),
                approvals.findAreaEvidence(review, fromQuarter, toQuarter, asOf));
        var scope = new AnalysisScope(mapping.sourceRegionCode(), mapping.sourceIndustryCode(),
                review.sourceIndustryName(), review.industryMatch());
        return new PreparedAnalysis(scope, selections);
    }

    /** 승인 근거는 이 서비스가 생성하지 않는다. 준비된 요청의 AI 호출은 트랜잭션 종료 후 수행한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<SeoulAiHistoryPlanner.Metric, SeoulAiHistoryPlanner.Selection> prepare(
            SeoulAiHistoryPlanner.Mapping mapping, String fromQuarter, String toQuarter, LocalDate asOf,
            Map<Long, Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence>> batchEvidence,
            List<SeoulAiHistoryPlanner.Value> supplementalValues) {
        Objects.requireNonNull(batchEvidence, "batchEvidence");
        Objects.requireNonNull(supplementalValues, "supplementalValues");
        if (mapping == null || !mapping.regionApproved() || !mapping.industryApproved()) {
            return planner.prepare(mapping, List.of(), asOf);
        }
        if (fromQuarter == null || !fromQuarter.matches("[0-9]{4}[1-4]")
                || toQuarter == null || !toQuarter.matches("[0-9]{4}[1-4]")
                || fromQuarter.compareTo(toQuarter) > 0) throw new IllegalArgumentException("조회 분기 오류");
        for (var value : supplementalValues) {
            if (value == null || (value.kind() != SeoulAiHistoryPlanner.Kind.RESIDENT_POPULATION
                    && value.kind() != SeoulAiHistoryPlanner.Kind.AREA_SQUARE_METERS)
                    || value.period() == null || !value.period().matches("[0-9]{4}Q[1-4]")) {
                throw new IllegalArgumentException("상주인구·면적 이외의 보조 입력은 허용하지 않습니다");
            }
            String quarter = value.period().substring(0, 4) + value.period().substring(5);
            if (quarter.compareTo(fromQuarter) < 0 || quarter.compareTo(toQuarter) > 0)
                throw new IllegalArgumentException("보조 입력이 조회 범위를 벗어났습니다");
        }
        var values = new ArrayList<>(supplementalValues);
        for (var row : source.find(mapping.regionCode(), mapping.sourceRegionCode(), mapping.sourceIndustryCode(),
                fromQuarter, toQuarter, asOf)) {
            values.addAll(decoder.decode(row, mapping, batchEvidence.getOrDefault(row.batchId(), Map.of())));
        }
        return planner.prepare(mapping, values, asOf);
    }
}
