package com.pickplace.backend.analysis;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pickplace.backend.summary.SummaryContextService;
import com.pickplace.backend.summary.SummaryService;

/** 게시된 선택 기준과 요약본을 읽되, 아직 승인되지 않은 매출 이력은 만들지 않는다. */
@Component
public final class PublishedSummarySnapshotReader implements AnalysisOrchestrator.SnapshotReader {
    static final String REGISTERED_POPULATION = "registeredPopulation";
    static final String LISTED_STORE_COUNT = "listedStoreCount";

    private final SummaryContextService summaries;
    private final SummaryService summaryService;

    public PublishedSummarySnapshotReader(SummaryContextService summaries, SummaryService summaryService) {
        this.summaries = summaries;
        this.summaryService = summaryService;
    }

    @Override
    public AnalysisOrchestrator.Snapshot read(AnalysisOrchestrator.Query query) {
        // 읽기 트랜잭션 안에서 불변 결과를 완성하고, 종료된 뒤에만 AI 호출 단계로 넘어간다.
        return summaries.withContext(query.regionCode(), query.businessTypeCode(), context -> {
            var published = context.summary();
            String version = "national-summary-" + published.referenceMonth() + "-r" + published.revision();
            // 현황 API와 같은 판정을 같은 읽기 시점에서 계산한다. AI가 호출되지 않거나 실패해도 이 값은 그대로 응답한다.
            var summary = summaryService.decide(context);
            var statistics = new AnalysisOrchestrator.Statistics(version,
                    Map.of(REGISTERED_POPULATION, summary.population(), LISTED_STORE_COUNT, summary.stores()), summary);
            // 승인된 분기 매출·서울 CS 업종 대응은 현재 DB에 없으므로 명시적으로 비워 둔다.
            return new AnalysisOrchestrator.Snapshot(statistics, null, List.of());
        });
    }
}
