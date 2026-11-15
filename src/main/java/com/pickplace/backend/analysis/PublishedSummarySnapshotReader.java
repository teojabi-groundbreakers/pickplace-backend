package com.pickplace.backend.analysis;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pickplace.backend.summary.SummaryContextService;

/** 게시된 선택 기준과 요약본을 읽되, 아직 승인되지 않은 매출 이력은 만들지 않는다. */
@Component
public final class PublishedSummarySnapshotReader implements AnalysisOrchestrator.SnapshotReader {
    private final SummaryContextService summaries;

    public PublishedSummarySnapshotReader(SummaryContextService summaries) {
        this.summaries = summaries;
    }

    @Override
    public AnalysisOrchestrator.Snapshot read(AnalysisOrchestrator.Query query) {
        // 읽기 트랜잭션 안에서 불변 결과를 완성하고, 종료된 뒤에만 AI 호출 단계로 넘어간다.
        return summaries.withContext(query.regionCode(), query.businessTypeCode(), context -> {
            var published = context.summary();
            String version = "national-summary-" + published.referenceMonth() + "-r" + published.revision();
            var statistics = new AnalysisOrchestrator.Statistics(version, Map.of());
            // 승인된 분기 매출·서울 CS 업종 대응은 현재 DB에 없으므로 명시적으로 비워 둔다.
            return new AnalysisOrchestrator.Snapshot(statistics, null, List.of());
        });
    }
}
