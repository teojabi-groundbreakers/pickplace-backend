package com.pickplace.backend.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** 승인 근거를 전달받아 서울 게시 원천과 보조 지표를 하나의 읽기 시점에서 조립한다. */
@Service
public class SeoulAiInputPreparationService {
    private final SeoulPublishedStatisticsRepository source;
    private final SeoulAiSourceDecoder decoder;
    private final SeoulAiHistoryPlanner planner = new SeoulAiHistoryPlanner();

    public SeoulAiInputPreparationService(SeoulPublishedStatisticsRepository source, JsonMapper json) {
        this.source = source;
        this.decoder = new SeoulAiSourceDecoder(json);
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
