package com.pickplace.backend.ingestion.statistics.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.ingestion.statistics.service.StatisticsBatchService.Batch;
import com.pickplace.backend.ingestion.validation.service.ValidationReference;
import tools.jackson.databind.json.JsonMapper;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 실제 수록 지역과 업종별 내부 채택 수를 만든다. 공개 숫자·상태 변환은 조회 서비스의 책임이다. */
@Service
public class StatisticsAggregationService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final StatisticsBatchService batches;

    public StatisticsAggregationService(JdbcTemplate jdbc, JsonMapper mapper, StatisticsBatchService batches) {
        this.jdbc = jdbc; this.mapper = mapper; this.batches = batches;
    }

    void aggregate(Batch batch, StatisticsImportInput input, StatisticsImportLedger ledger, ValidationReference reference) {
        if (!input.source().dataset().equals("STORE_SNAPSHOT")) return;
        var r = input.report();
        var coverages = new ArrayList<Coverage>();
        var validRegions = new HashMap<String, Boolean>();
        for (var entry : ledger.coverage.entrySet()) {
            if (!input.sourceCodes().contains(entry.getKey())) continue; // 미등록 원천은 GLOBAL 이슈로만 보존한다.
            var resolved = reference.resolve("SEMAS_STORE", entry.getKey(), r.sourceRegionVersion(), LocalDate.parse(r.sourceReferenceOn()));
            coverages.add(new Coverage(entry.getKey(), resolved, entry.getValue(), ledger.distribution.getOrDefault(entry.getKey(), Map.of())));
            if (resolved.regionCode() != null) validRegions.merge(resolved.regionCode(), resolved.reason() == null, (a, b) -> a && b);
        }
        for (int offset = 0; offset < coverages.size(); offset += 200) {
            var chunk = coverages.subList(offset, Math.min(offset + 200, coverages.size()));
            batches.inTransaction(() -> {
                batches.lockWritable(batch.id(), "VALIDATING");
                jdbc.batchUpdate("""
                        INSERT INTO store_region_coverage (batch_id, source_period_key, source_region_code, source_region_version,
                            mapping_release_id, region_code, source_row_count, accepted_row_count, held_row_count,
                            rejected_row_count, duplicate_row_count, industry_distribution, temporal_region_status, coverage_status, reason_code)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, chunk, 200, (ps, c) -> {
                    Object[] values = {batch.id(), r.periodKey(), c.source(), r.sourceRegionVersion(), batch.mappingId(), c.resolved().regionCode(),
                            c.counts().values().stream().mapToLong(Long::longValue).sum(), c.counts().getOrDefault("ACCEPTED", 0L),
                            c.counts().getOrDefault("HOLD", 0L), c.counts().getOrDefault("REJECTED", 0L), c.counts().getOrDefault("DUPLICATE", 0L),
                            mapper.writeValueAsString(c.distribution()), c.resolved().temporalStatus(), c.resolved().reason() == null ? "VALID" : "HOLD", c.resolved().reason()};
                    for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
                });
                return null;
            });
        }
        var smallCodes = input.snapshot().industries().stream().filter(i -> i.level().equals("SMALL")).map(i -> i.code()).sorted().toList();
        var regionCodes = validRegions.keySet().stream().sorted().toList();
        // 전체 업소 대신 20개 지역의 집계만 메모리로 읽는다. 나중에 업종 지원 범위가 늘어도 원천 행은 보존된다.
        for (int offset = 0; offset < regionCodes.size(); offset += 20) {
            var regions = regionCodes.subList(offset, Math.min(offset + 20, regionCodes.size()));
            aggregateRegions(batch, input, regions, smallCodes, validRegions);
        }
    }

    private void aggregateRegions(Batch b, StatisticsImportInput input, List<String> regions,
            List<String> smallCodes, Map<String, Boolean> validRegions) {
        String placeholders = String.join(",", java.util.Collections.nCopies(regions.size(), "?"));
        var args = new ArrayList<Object>(); args.add(b.id()); args.addAll(regions);
        Map<String, Long> accepted = new HashMap<>(), affected = new HashMap<>();
        jdbc.query("""
                SELECT region_code, industry_small_code, COUNT(*) AS n FROM store_snapshot_entry
                WHERE batch_id=? AND region_code IN (%s) AND row_status='ACCEPTED'
                GROUP BY region_code, industry_small_code
                """.formatted(placeholders), rs -> {
            accepted.put(key(rs.getString(1), rs.getString(2)), rs.getLong(3));
        }, args.toArray());
        // 행 검증 단계에서 영향 범위를 행의 확정된 지역/업종과 대조한다.
        // 따라서 같은 원천행이 REGION과 REGION_INDUSTRY 양쪽에 중복 가산되지 않는다.
        jdbc.query("""
                SELECT affected_region_code, affected_industry_code,
                    COUNT(DISTINCT source_artifact_no, source_row_number) AS n
                FROM national_quality_issue WHERE batch_id=? AND affects_count=TRUE AND affected_region_code IN (%s)
                GROUP BY affected_region_code, affected_industry_code
                """.formatted(placeholders), rs -> {
            affected.put(key(rs.getString(1), rs.getString(2)), rs.getLong(3));
        }, args.toArray());
        var counts = new ArrayList<Count>();
        for (String region : regions) for (String industry : smallCodes) {
            long n = accepted.getOrDefault(key(region, industry), 0L);
            long bad = affected.getOrDefault(key(region, null), 0L) + affected.getOrDefault(key(region, industry), 0L);
            // 유효한 수록 범위에서만 0을 만든다. 경계 보류 지역에는 관측 수·영향이 있는 업종만 남긴다.
            if (validRegions.get(region) || n > 0 || bad > 0) counts.add(new Count(region, industry, n, bad));
        }
        for (int offset = 0; offset < counts.size(); offset += 500) {
            var chunk = counts.subList(offset, Math.min(offset + 500, counts.size()));
            batches.inTransaction(() -> {
                batches.lockWritable(b.id(), "VALIDATING");
                jdbc.batchUpdate("""
                        INSERT INTO store_industry_count (batch_id, source_period_key, source_region_version, mapping_release_id,
                            region_code, classification_id, industry_code, accepted_store_count, count_quality_status,
                            count_quality_reason, count_affecting_issue_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, chunk, 500, (ps, c) -> {
                    Object[] values = {b.id(), input.report().periodKey(), input.report().sourceRegionVersion(), b.mappingId(), c.region(),
                            b.classificationId(), c.industry(), c.accepted(), c.affected() == 0 ? "VALID" : "HOLD",
                            c.affected() == 0 ? null : "COUNT_INCOMPLETE", c.affected()};
                    for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
                });
                return null;
            });
        }
    }

    void complete(Batch batch, StatisticsImportInput input, StatisticsImportLedger ledger) {
        batches.inTransaction(() -> {
            batches.lockWritable(batch.id(), "VALIDATING");
            String table = input.source().dataset().equals("POPULATION_MONTH") ? "resident_population_month" : "store_snapshot_entry";
            require(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE batch_id=?", Long.class, batch.id()) == ledger.factRows,
                    "DB 사실 행 회계 불일치");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM national_quality_issue WHERE batch_id=?", Long.class, batch.id()) == ledger.issueRows,
                    "DB 품질 이슈 회계 불일치");
            if (table.equals("store_snapshot_entry")) require(jdbc.queryForObject(
                    "SELECT COALESCE(SUM(accepted_store_count),0) FROM store_industry_count WHERE batch_id=?", Long.class, batch.id())
                    .equals(ledger.dispositions.get("ACCEPTED")), "집계 수와 채택 행 수가 다릅니다.");
            String report = mapper.writeValueAsString(Map.of("t13", input.report(), "inputReportSha256", input.reportSha256(),
                    "importVerified", true, "writerVersion", StatisticsBatchService.WRITER_VERSION,
                    "factRows", ledger.factRows, "issueRows", ledger.issueRows));
            jdbc.update("""
                    UPDATE national_import_batch SET status='SUCCEEDED', finished_at=UTC_TIMESTAMP(6),
                        source_row_count=?, accepted_row_count=?, held_row_count=?, rejected_row_count=?, duplicate_row_count=?, validation_report=?
                    WHERE batch_id=?
                    """, input.report().sourceRows(), ledger.dispositions.get("ACCEPTED"), ledger.dispositions.get("HOLD"),
                    ledger.dispositions.get("REJECTED"), ledger.dispositions.get("DUPLICATE"), report, batch.id());
            return null;
        });
    }

    private static String key(String region, String industry) { return region + ":" + industry; }
    private record Coverage(String source, ValidationReference.Resolution resolved, Map<String, Long> counts, Map<String, Long> distribution) {}
    private record Count(String region, String industry, long accepted, long affected) {}
}
