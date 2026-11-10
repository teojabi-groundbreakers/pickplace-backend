package com.pickplace.backend.summary;

import java.time.Instant;
import java.time.LocalDate;

/** 한 조회에서 고정한 선택 기준·DEFAULT·출처다. 내부 ID는 공개 선택 토큰으로 노출하지 않는다. */
public record SummaryReadContext(Selection selection, PublishedSummary summary, Source population, Source stores) {
    public boolean classificationMatches() { return selection.classificationId() == summary.classificationId(); }

    public record Selection(long releaseId, long classificationId, String regionCode, String regionName,
            String businessTypeCode, String businessTypeName) {}
    public record PublishedSummary(long id, String referenceMonth, long revision, Instant publishedAt,
            long mappingReleaseId, long classificationId) {}
    public record Source(long batchId, String datasetCode, String sourceRegionVersion, LocalDate referenceOn,
            String snapshotLabel, Instant collectedAt) {}
}
