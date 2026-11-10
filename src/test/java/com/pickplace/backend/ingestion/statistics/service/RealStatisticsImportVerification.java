package com.pickplace.backend.ingestion.statistics.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import com.pickplace.backend.PickplaceBackendApplication;
import com.pickplace.backend.businesstype.BusinessTypeService;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 실행하는 실데이터 검수다. 일반 test에는 포함하지 않고 전용 MySQL 컨테이너를 사용한다. */
public final class RealStatisticsImportVerification {
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]), workspace = project.getParent(), output = Path.of(args[1]);
        var mapper = JsonMapper.builder().build(); var record = new LinkedHashMap<String, Object>();
        record.put("verifiedAt", Instant.now()); record.put("scope", "Isolated MySQL; no development DB changes");
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("pickplace_test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci", "--innodb-buffer-pool-size=4294967296")) {
            mysql.start();
            String url = mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "rewriteBatchedStatements=true&connectionTimeZone=UTC";
            try (var context = new SpringApplicationBuilder(PickplaceBackendApplication.class).web(WebApplicationType.NONE).profiles("test")
                    .properties(Map.of("spring.datasource.url", url, "spring.datasource.username", mysql.getUsername(),
                            "spring.datasource.password", mysql.getPassword(), "spring.main.banner-mode", "off",
                            "spring.datasource.hikari.maximum-pool-size", "4")).run()) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var loader = context.getBean(StatisticsInputLoader.class); var importer = context.getBean(NationalStatisticsImporter.class);
                var publisher = context.getBean(NationalSummaryPublisher.class);
                Path snapshot = project.resolve("data/reference/national-202609-sejong-hint-v1/snapshot.json");
                var loaded = context.getBean(NationalReferenceLoader.class).load(snapshot, NationalRawValidator.sha256(snapshot));
                var ref = context.getBean(NationalReferenceImporter.class).importSnapshot(loaded);
                var refPublisher = context.getBean(NationalReferencePublisher.class);
                refPublisher.approve(loaded.snapshot().version(), "t14-verification");
                refPublisher.publish(loaded.snapshot().version(), 0, "t14-verification", "isolated test", false);
                record.put("reference", Map.of("mappingId", ref.releaseId(), "classificationId", ref.classificationId(), "snapshotSha256", loaded.sha256()));
                Path t13 = workspace.resolve("data-validation/2026-11-09/t13-national/final-v2");
                Path rawRoot = workspace.resolve("data-validation/2026-11-09/t12-national/final/adopted/national");
                Path popRaw = rawRoot.resolve("population_month/202609/23a269d2fd754f27bab32c9a26c711c3e49e65aee7169dc3184d8b5a548cd36d");
                Path storeRaw = rawRoot.resolve("store_snapshot/202609/c83b30e7a1d1144864a91d0e1818abf360f565972878d6e3c88d75ceafe2286a");
                long started = System.nanoTime();
                var population = importer.importData(loader.load(t13.resolve("population"), popRaw.resolve("raw.csv"), popRaw.resolve("metadata.json"), snapshot));
                record.put("populationImportMillis", (System.nanoTime() - started) / 1_000_000);
                started = System.nanoTime();
                var input = loader.load(t13.resolve("stores"), storeRaw.resolve("raw.zip"), storeRaw.resolve("metadata.json"), snapshot);
                var stores = importer.importData(input);
                record.put("storeImportMillis", (System.nanoTime() - started) / 1_000_000);
                var popBatch = jdbc.queryForMap("SELECT batch_id,status,source_row_count,accepted_row_count,held_row_count,rejected_row_count,duplicate_row_count FROM national_import_batch WHERE batch_id=?", population.batchId());
                var storeBatch = jdbc.queryForMap("SELECT batch_id,status,source_row_count,accepted_row_count,held_row_count,rejected_row_count,duplicate_row_count FROM national_import_batch WHERE batch_id=?", stores.batchId());
                assertNumber(popBatch, "source_row_count", 3619); assertNumber(storeBatch, "source_row_count", 2_753_778);
                assertNumber(popBatch, "held_row_count", 1); assertNumber(storeBatch, "held_row_count", 674);
                record.put("populationBatch", popBatch); record.put("storeBatch", storeBatch);
                record.put("counts", jdbc.queryForMap("""
                        SELECT (SELECT COUNT(*) FROM resident_population_month) AS population_facts,
                            (SELECT COUNT(*) FROM store_snapshot_entry) AS store_facts,
                            (SELECT COUNT(*) FROM national_quality_issue) AS quality_issues,
                            (SELECT COUNT(*) FROM store_region_coverage) AS coverage_rows,
                            (SELECT COUNT(*) FROM store_industry_count) AS industry_counts,
                            (SELECT SUM(accepted_store_count) FROM store_industry_count) AS accepted_count_sum
                        """));
                long summary = publisher.prepare("national-summary-202609-verification-v1", population.batchId(), stores.batchId());
                publisher.approve("national-summary-202609-verification-v1", "t14-verification");
                publisher.publishMonth("national-summary-202609-verification-v1", new Expected(0, null), null, "t14-verification", "first publication", false);
                publisher.setDefault("national-summary-202609-verification-v1", new Expected(0, null), "t14-verification", "explicit default");
                record.put("summaryId", summary); record.put("pointers", publisher.pointers());
                // 같은 원본 재실행으로 사실·게시 이력이 늘지 않는지도 실제 전체 입력으로 확인한다.
                if (!importer.importData(input).status().equals("REUSED")) throw new IllegalStateException("same input was not reused");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM national_import_batch", Long.class) != 2) throw new IllegalStateException("unexpected batch count");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM national_summary_publication_event", Long.class) != 2) throw new IllegalStateException("unexpected publication events");
                record.put("idempotentReuse", true);
                record.put("businessTypes", context.getBean(BusinessTypeService.class).getSupportedBusinessTypes());
                String lookup = "SELECT accepted_store_count,count_quality_status FROM store_industry_count WHERE batch_id=? AND region_code=? AND classification_id=? AND industry_code=?";
                record.put("explain", jdbc.queryForList("EXPLAIN " + lookup, stores.batchId(), "1111051500", ref.classificationId(), "I21201"));
                started = System.nanoTime();
                record.put("sampleCount", jdbc.queryForMap(lookup, stores.batchId(), "1111051500", ref.classificationId(), "I21201"));
                record.put("singleSqlLookupMicros", (System.nanoTime() - started) / 1000);
                record.put("storeBoundaryHolds", jdbc.queryForList("SELECT region_code, SUM(count_affecting_issue_count) AS affected_rows FROM store_industry_count WHERE batch_id=? AND count_quality_status='HOLD' GROUP BY region_code", stores.batchId()));
                record.put("result", "PASSED");
                record.put("verificationDatabaseBufferPoolMiB", 4096);
                Files.createDirectories(output.getParent());
                Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
                System.out.println("Real statistics verification PASSED: " + output.toAbsolutePath());
            }
        }
    }

    private static void assertNumber(Map<String, Object> row, String field, long expected) {
        if (((Number) row.get(field)).longValue() != expected) throw new IllegalStateException(field + " mismatch");
    }
}
