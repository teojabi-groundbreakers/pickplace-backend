package com.pickplace.backend.ingestion.statistics.service;

import com.pickplace.backend.PickplaceBackendApplication;
import com.pickplace.backend.ingestion.detail.service.*;
import com.pickplace.backend.reference.nationwide.*;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 명시적으로 실행하는 전체 보유 원본 검수. 결과는 DB가 종료되기 전에 파일로 남긴다. */
public final class RealDetailImportVerification {
    public static void main(String[] args) throws Exception {
        Path catalog = Path.of(args[0]).toAbsolutePath(),
                output = Path.of(args[1]).toAbsolutePath();
        var mapper = JsonMapper.builder().build();
        var catalogJson = mapper.readTree(Files.readAllBytes(catalog));
        var report = new LinkedHashMap<String, Object>();
        report.put("startedAt", Instant.now());
        report.put("scope", "isolated MySQL; existing development DB unchanged");
        report.put("result", "RUNNING");
        var results = new ArrayList<Object>();
        report.put("files", results);
        save(output, mapper, report);
        try (var mysql =
                new MySQLContainer("mysql:8.4.11")
                        .withDatabaseName("pickplace_test")
                        .withCommand(
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_ai_ci",
                                "--innodb-buffer-pool-size=4294967296")) {
            mysql.start();
            String url =
                    mysql.getJdbcUrl() + "?rewriteBatchedStatements=true&connectionTimeZone=UTC";
            try (var context =
                    new SpringApplicationBuilder(PickplaceBackendApplication.class)
                            .web(WebApplicationType.NONE)
                            .profiles("test")
                            .properties(
                                    Map.of(
                                            "spring.datasource.url",
                                            url,
                                            "spring.datasource.username",
                                            mysql.getUsername(),
                                            "spring.datasource.password",
                                            mysql.getPassword(),
                                            "spring.main.banner-mode",
                                            "off"))
                            .run()) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var loader = context.getBean(DetailInputLoader.class);
                var importer = context.getBean(RegionalDetailImporter.class);
                var publisher = context.getBean(DetailBatchService.class);
                Path reference = Path.of(catalogJson.path("reference").asText());
                var loaded =
                        context.getBean(NationalReferenceLoader.class)
                                .load(reference, catalogJson.path("referenceSha256").asText());
                context.getBean(NationalReferenceImporter.class).importSnapshot(loaded);
                context.getBean(NationalReferencePublisher.class)
                        .approve(loaded.snapshot().version(), "T20-isolated-verification");
                for (var file : catalogJson.path("files")) {
                    Path manifest = catalog.getParent().resolve(file.path("manifest").asText());
                    System.out.println("T20 importing " + manifest.getFileName());
                    var input = loader.load(manifest);
                    long started = System.nanoTime();
                    var result = importer.importData(input);
                    var counts =
                            jdbc.queryForMap(
                                    "SELECT"
                                        + " batch_id,dataset_code,status,source_row_count,accepted_row_count,held_row_count,invalid_row_count,duplicate_row_count"
                                        + " FROM regional_statistic_batch WHERE batch_id=?",
                                    result.batchId());
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("manifest", manifest.getFileName().toString());
                    entry.put("counts", counts);
                    entry.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
                    results.add(entry);
                    publisher.approve(result.batchId(), "T20-isolated-verification");
                    for (String period : input.manifest().periods())
                        publisher.publish(
                                result.batchId(),
                                period,
                                0,
                                null,
                                "T20-isolated-verification",
                                "real original verification");
                    entry.put("internalPublication", "APPROVED_AND_PUBLISHED");
                    report.put("completedFiles", results.size());
                    save(output, mapper, report);
                }
                var first = catalogJson.path("files").get(0);
                if (!importer.importData(
                                loader.load(
                                        catalog.getParent()
                                                .resolve(first.path("manifest").asText())))
                        .status()
                        .equals("REUSED")) throw new IllegalStateException("same input not reused");
                report.put("idempotentReuse", true);
                report.put(
                        "counts",
                        jdbc.queryForMap(
                                """
                                SELECT (SELECT COUNT(*) FROM regional_statistic_batch) AS batches,
                                    (SELECT COUNT(*) FROM regional_statistic_row) AS facts,
                                    (SELECT COUNT(*) FROM regional_statistic_issue) AS issues,
                                    (SELECT COUNT(*) FROM regional_statistic_publication) AS publications,
                                    (SELECT COUNT(*) FROM regional_statistic_publication_event) AS events,
                                    (SELECT COUNT(*) FROM published_regional_statistic) AS readable_rows
                                """));
                report.put(
                        "byDataset",
                        jdbc.queryForList(
                                "SELECT dataset_code,SUM(source_row_count) AS"
                                    + " source_rows,SUM(accepted_row_count) AS"
                                    + " accepted,SUM(held_row_count) AS held,SUM(invalid_row_count)"
                                    + " AS invalid,SUM(duplicate_row_count) AS duplicates FROM"
                                    + " regional_statistic_batch GROUP BY dataset_code"));
                report.put(
                        "holds",
                        jdbc.queryForList(
                                "SELECT b.dataset_code,r.reason_code,COUNT(*) AS rows_count FROM"
                                    + " regional_statistic_row r JOIN regional_statistic_batch b ON"
                                    + " b.batch_id=r.batch_id WHERE row_status<>'VALID' GROUP BY"
                                    + " b.dataset_code,r.reason_code"));
                report.put(
                        "samples",
                        jdbc.queryForList(
                                """
                                SELECT dataset_code,region_code,period_key,source_industry_code,metrics FROM published_regional_statistic
                                WHERE region_code='1111051500' AND period_key='20262'
                                    AND (source_industry_code='' OR source_industry_code='CS100010') ORDER BY dataset_code
                                """));
                long accepted =
                        jdbc.queryForObject(
                                "SELECT SUM(accepted_row_count) FROM regional_statistic_batch",
                                Long.class);
                if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM published_regional_statistic", Long.class)
                        != accepted) throw new IllegalStateException("published rows mismatch");
                if (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM national_summary_publication",
                                        Long.class)
                                != 0
                        || jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM dataset_publication", Long.class)
                                != 0)
                    throw new IllegalStateException("existing publication modified");
                report.put("nationalAndLegacyPublicationsUnchanged", true);
                report.put("result", "PASSED");
                report.put("finishedAt", Instant.now());
                save(output, mapper, report);
                System.out.println("T20 real import PASSED: " + output);
            }
        } catch (Exception failure) {
            report.put("result", "FAILED");
            report.put("failureType", failure.getClass().getName());
            report.put("failureMessage", failure.getMessage());
            save(output, mapper, report);
            throw failure;
        }
    }

    private static void save(Path output, JsonMapper mapper, Map<String, Object> report)
            throws Exception {
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }
}
