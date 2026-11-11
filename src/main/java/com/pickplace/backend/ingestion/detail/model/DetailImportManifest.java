package com.pickplace.backend.ingestion.detail.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 원천 파일·분류·정의를 한 번에 고정한다. 파일 경로는 이 명세 파일 기준 상대 경로다. */
public record DetailImportManifest(
        String version,
        String sourceSystem,
        String datasetCode,
        String adapter,
        String periodType,
        List<String> periods,
        String regionScheme,
        String industryScheme,
        String referenceVersion,
        String referenceSha256,
        String mappingFile,
        String mappingSha256,
        String sourceUrl,
        String collectedAt,
        String rawFile,
        String rawSha256,
        String encoding,
        long expectedRows,
        Map<String, String> aliases,
        List<Metric> metrics,
        Map<String, Object> metadata) {
    public record Metric(String field, String unit, String aggregation, String definitionStatus) {}

    public record RegionMapping(
            String sourceCode, String sourceName, String targetCode, String evidence) {}

    public record Mappings(
            String referenceVersion, String referenceSha256, List<RegionMapping> regions) {}

    public Instant collectionTime() {
        return Instant.parse(collectedAt);
    }
}
