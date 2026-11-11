package com.pickplace.backend.ingestion.statistics.service;

import static org.assertj.core.api.Assertions.*;

import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.ingestion.detail.cli.RegionalDetailRunner;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.*;
import com.pickplace.backend.ingestion.detail.service.*;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.reference.nationwide.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class RegionalDetailImportTests {
    @Autowired DetailInputLoader loader;
    @Autowired RegionalDetailImporter importer;
    @Autowired DetailBatchService batches;
    @Autowired NationalReferenceLoader references;
    @Autowired NationalReferenceImporter referenceImporter;
    @Autowired NationalReferencePublisher referencePublisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DetailCatalogImporter catalogs;
    @TempDir Path temp;
    String referenceVersion, referenceSha;

    @BeforeEach
    void prepare() throws Exception {
        for (String table :
                List.of(
                        "regional_statistic_publication_event",
                        "regional_statistic_publication",
                        "regional_statistic_issue",
                        "regional_statistic_row",
                        "regional_statistic_artifact",
                        "regional_statistic_batch")) jdbc.update("DELETE FROM " + table);
        var fixture =
                StatisticsTestFixture.prepare(
                        temp.resolve("reference"),
                        "202609",
                        List.of(StatisticsTestFixture.store("S1")));
        referenceSha = NationalRawValidator.sha256(fixture.reference());
        var loaded = references.load(fixture.reference(), referenceSha);
        referenceImporter.importSnapshot(loaded);
        referenceVersion = loaded.snapshot().version();
        referencePublisher.approve(referenceVersion, "detail-test");
    }

    @Test
    void importsPublishesAndReusesWithoutChangingNationalPointers() throws Exception {
        long before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM national_summary_publication", Long.class);
        Path manifest = manifest(List.of(row("20262", "11110515", 10)), "VERIFIED");
        var result = importer.importData(loader.load(manifest));
        assertThat(result.status()).isEqualTo("READY");
        assertThat(count("published_regional_statistic")).isZero();
        assertThatThrownBy(
                        () ->
                                batches.publish(
                                        result.batchId(),
                                        "20262",
                                        0,
                                        null,
                                        "test",
                                        "before approval"))
                .isInstanceOf(IllegalArgumentException.class);
        batches.approve(result.batchId(), "test");
        assertThat(batches.publish(result.batchId(), "20262", 0, null, "test", "first"))
                .isEqualTo(1);
        assertThat(count("published_regional_statistic")).isEqualTo(1);
        assertThat(importer.importData(loader.load(manifest)).status()).isEqualTo("REUSED");
        assertThat(count("regional_statistic_batch")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM national_summary_publication", Long.class))
                .isEqualTo(before);
        assertThatThrownBy(
                        () -> batches.publish(result.batchId(), "20262", 0, null, "test", "stale"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("regional_statistic_publication_event")).isEqualTo(1);
        assertThat(batches.publish(result.batchId(), "20262", 1, result.batchId(), "test", "same"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "0,VALID,AVAILABLE",
        "-1,INVALID,INVALID",
        "9007199254740992,INVALID,INVALID",
        "null,HOLD,NO_DATA"
    })
    void preservesZeroMissingAndInvalid(String value, String state, String metricStatus)
            throws Exception {
        Object number = value.equals("null") ? null : value;
        var result =
                importer.importData(
                        loader.load(
                                manifest(List.of(row("20262", "11110515", number)), "VERIFIED")));
        var stored =
                jdbc.queryForMap(
                        "SELECT row_status,metrics,raw_payload FROM regional_statistic_row WHERE"
                                + " batch_id=?",
                        result.batchId());
        assertThat(stored.get("row_status")).isEqualTo(state);
        var metric = mapper.readTree(stored.get("metrics").toString()).path("tot_wrc_popltn_co");
        assertThat(metric.path("status").asText()).isEqualTo(metricStatus);
        if (value.equals("0")) assertThat(metric.path("value").asInt()).isZero();
        else assertThat(metric.path("value").isNull()).isTrue();
    }

    @Test
    void sourceOnlyDefinitionIsStoredWithoutInventingAnInterpretation() throws Exception {
        var r =
                importer.importData(
                        loader.load(manifest(List.of(row("20262", "11110515", 7)), "SOURCE_ONLY")));
        var metric =
                mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT metrics FROM regional_statistic_row WHERE"
                                                + " batch_id=?",
                                        String.class,
                                        r.batchId()))
                        .path("tot_wrc_popltn_co");
        assertThat(metric.path("value").asInt()).isEqualTo(7);
        assertThat(metric.path("status").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    void unknownMappingIsHeldAndNeverPublished() throws Exception {
        var result =
                importer.importData(
                        loader.load(
                                manifest(
                                        List.of(
                                                row("20262", "99999999", 12),
                                                row("20262", "11110515", 1)),
                                        "VERIFIED")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason_code FROM regional_statistic_row WHERE"
                                        + " source_region_code='99999999'",
                                String.class))
                .isEqualTo("MAPPING_UNRESOLVED");
        batches.approve(result.batchId(), "test");
        batches.publish(result.batchId(), "20262", 0, null, "test", "partial holds");
        assertThat(count("published_regional_statistic")).isEqualTo(1);
    }

    @Test
    void regionNameMismatchIsNotMatchedByCodeAlone() throws Exception {
        var row = row("20262", "11110515", 12);
        row.put("adstrd_cd_nm", "다른동");
        importer.importData(loader.load(manifest(List.of(row), "VERIFIED")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT row_status FROM regional_statistic_row", String.class))
                .isEqualTo("HOLD");
    }

    @Test
    void rowsBeforeRecordedCreationAreHeld() throws Exception {
        Path p = manifest(List.of(row("20071", "11110515", 12)), "VERIFIED");
        rewrite(p, "periods", List.of("20071"));
        importer.importData(loader.load(p));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason_code FROM regional_statistic_row", String.class))
                .isEqualTo("BOUNDARY_MISMATCH");
    }

    @Test
    void duplicateConflictAcrossChunksInvalidatesTheFirstValue() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) rows.add(row("20262", "11110515", 10));
        rows.add(row("20262", "11110515", 20));
        var result = importer.importData(loader.load(manifest(rows, "VERIFIED")));
        var batch =
                jdbc.queryForMap(
                        "SELECT"
                            + " source_row_count,accepted_row_count,invalid_row_count,duplicate_row_count"
                            + " FROM regional_statistic_batch WHERE batch_id=?",
                        result.batchId());
        assertThat(((Number) batch.get("source_row_count")).intValue()).isEqualTo(501);
        assertThat(((Number) batch.get("accepted_row_count")).intValue()).isZero();
        assertThat(((Number) batch.get("invalid_row_count")).intValue()).isEqualTo(1);
        assertThat(((Number) batch.get("duplicate_row_count")).intValue()).isEqualTo(500);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason_code FROM regional_statistic_row", String.class))
                .isEqualTo("DUPLICATE_CONFLICT");
    }

    @Test
    void failedChunkIsRetainedAndRetryCreatesANewAttempt() throws Exception {
        var stable =
                importer.importData(
                        loader.load(manifest(List.of(row("20262", "11110515", 42)), "VERIFIED")));
        batches.approve(stable.batchId(), "test");
        batches.publish(stable.batchId(), "20262", 0, null, "test", "stable before failure");
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) rows.add(row("20262", "11110515", 10));
        rows.add(row("20261", "11110515", 11));
        Path p = manifest(rows, "VERIFIED");
        for (int i = 0; i < 2; i++)
            assertThatThrownBy(() -> importer.importData(loader.load(p)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("regional_statistic_batch")).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT MAX(attempt_no) FROM regional_statistic_batch",
                                Integer.class))
                .isEqualTo(2);
        assertThat(count("regional_statistic_row")).isEqualTo(3);
        assertThat(count("published_regional_statistic")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT batch_id FROM regional_statistic_publication", Long.class))
                .isEqualTo(stable.batchId());
    }

    @Test
    void rawHashTamperFailsBeforeStartingBatch() throws Exception {
        Path p = manifest(List.of(row("20262", "11110515", 1)), "VERIFIED");
        Files.writeString(p.getParent().resolve("raw.json"), "{}");
        assertThatThrownBy(() -> loader.load(p)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("regional_statistic_batch")).isZero();
    }

    @Test
    void databaseRejectsValidRowsWithoutVerifiedRegion() throws Exception {
        var result =
                importer.importData(
                        loader.load(manifest(List.of(row("20262", "99999999", 1)), "VERIFIED")));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE regional_statistic_row SET"
                                                + " row_status='VALID',reason_code=NULL WHERE"
                                                + " batch_id=?",
                                        result.batchId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void zipCsvUsesTheSameValidationAndPreservesSourcePayload() throws Exception {
        Path p = manifest(List.of(row("20262", "11110515", 1)), "VERIFIED");
        Path zip = p.getParent().resolve("raw.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("sample.csv"));
            output.write(
                    "stdr_yyqu_cd,adstrd_cd,adstrd_cd_nm,tot_wrc_popltn_co\n20262,11110515,청운효자동,0\n"
                            .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        rewrite(p, "rawFile", "raw.zip");
        rewrite(p, "rawSha256", NationalRawValidator.sha256(zip));
        importer.importData(loader.load(p));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT row_status FROM regional_statistic_row", String.class))
                .isEqualTo("VALID");
    }

    @Test
    void cliRejectsUnknownInputsBeforeDatabaseBoot() {
        assertThatThrownBy(
                        () ->
                                RegionalDetailRunner.validateArguments(
                                        new DefaultApplicationArguments(
                                                "--spring.profiles.active=regional-detail,local",
                                                "--action=import",
                                                "--input=file",
                                                "--confirm=true")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RegionalDetailRunner.validateArguments(
                                        new DefaultApplicationArguments(
                                                "--spring.profiles.active=regional-detail",
                                                "--action=import",
                                                "--input=file")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogVerifiesItsEntriesAndReusesCompletedFiles() throws Exception {
        Path input = manifest(List.of(row("20262", "11110515", 3)), "VERIFIED");
        Path named = input.resolveSibling("worker.manifest.json");
        Files.move(input, named);
        var m = loader.load(named).manifest();
        Path catalog = named.resolveSibling("catalog.json");
        Files.write(
                catalog,
                mapper.writeValueAsBytes(
                        Map.of(
                                "referenceSha256",
                                referenceSha,
                                "files",
                                List.of(
                                        Map.of(
                                                "manifest",
                                                "worker.manifest.json",
                                                "dataset",
                                                "worker",
                                                "sha256",
                                                m.rawSha256(),
                                                "rows",
                                                1,
                                                "periods",
                                                List.of("20262"))))));
        assertThat(catalogs.importCatalog(catalog).getFirst().status()).isEqualTo("READY");
        assertThat(catalogs.importCatalog(catalog).getFirst().status()).isEqualTo("REUSED");
        assertThat(count("regional_statistic_batch")).isEqualTo(1);
        rewrite(named, "regionScheme", "UNREVIEWED_OTHER_CODES");
        assertThatThrownBy(() -> loader.load(named)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedPublicationEventRollsBackPointerReplacement() throws Exception {
        var first =
                importer.importData(
                        loader.load(manifest(List.of(row("20262", "11110515", 1)), "VERIFIED")));
        var second =
                importer.importData(
                        loader.load(manifest(List.of(row("20262", "11110515", 2)), "VERIFIED")));
        batches.approve(first.batchId(), "test");
        batches.approve(second.batchId(), "test");
        batches.publish(first.batchId(), "20262", 0, null, "test", "initial");
        // 이벤트 TEXT 컬럼의 한도를 넘겨 DB 저장 실패를 만들고, 먼저 쓴 포인터도 롤백되는지 본다.
        assertThatThrownBy(
                        () ->
                                batches.publish(
                                        second.batchId(),
                                        "20262",
                                        1,
                                        first.batchId(),
                                        "test",
                                        "x".repeat(70_000)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT batch_id FROM regional_statistic_publication", Long.class))
                .isEqualTo(first.batchId());
        assertThat(count("regional_statistic_publication_event")).isEqualTo(1);
    }

    @Test
    void physicalPathAndReceiptChangesReuseButDefinitionChangesDoNot() throws Exception {
        Path manifest = manifest(List.of(row("20262", "11110515", 10)), "VERIFIED");
        var original = importer.importData(loader.load(manifest));
        rewrite(
                manifest,
                "rawFile",
                manifest.resolveSibling("raw.json").toAbsolutePath().toString());
        rewrite(
                manifest,
                "mappingFile",
                manifest.resolveSibling("mapping.json").toAbsolutePath().toString());
        rewrite(manifest, "collectedAt", "2026-11-11T01:00:00Z");
        rewrite(manifest, "metadata", Map.of("newReceipt", true));
        var reused = importer.importData(loader.load(manifest));
        assertThat(reused.status()).isEqualTo("REUSED");
        assertThat(reused.batchId()).isEqualTo(original.batchId());
        rewrite(
                manifest,
                "metrics",
                List.of(
                        Map.of(
                                "field",
                                "tot_wrc_popltn_co",
                                "unit",
                                "COUNT",
                                "aggregation",
                                "SOURCE_DEFINED",
                                "definitionStatus",
                                "SOURCE_ONLY")));
        assertThat(importer.importData(loader.load(manifest)).batchId())
                .isNotEqualTo(original.batchId());
        assertThat(count("regional_statistic_batch")).isEqualTo(2);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Map<String, Object> row(String quarter, String region, Object value) {
        var r = new LinkedHashMap<String, Object>();
        r.put("stdr_yyqu_cd", quarter);
        r.put("adstrd_cd", region);
        r.put("adstrd_cd_nm", "청운효자동");
        r.put("tot_wrc_popltn_co", value);
        return r;
    }

    private Path manifest(List<Map<String, Object>> rows, String definition) throws Exception {
        Path dir = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        Path raw = dir.resolve("raw.json"), maps = dir.resolve("mapping.json");
        Files.write(raw, mapper.writeValueAsBytes(Map.of("DATA", rows)));
        Files.write(
                maps,
                mapper.writeValueAsBytes(
                        new Mappings(
                                referenceVersion,
                                referenceSha,
                                List.of(
                                        new RegionMapping(
                                                "11110515",
                                                "청운효자동",
                                                "1111051500",
                                                "existing verified test mapping")))));
        var aliases =
                Map.of(
                        "stdr_yyqu_cd",
                        "stdr_yyqu_cd",
                        "adstrd_cd",
                        "adstrd_cd",
                        "adstrd_cd_nm",
                        "adstrd_cd_nm",
                        "tot_wrc_popltn_co",
                        "tot_wrc_popltn_co");
        var m =
                new DetailImportManifest(
                        "regional-detail-manifest-v1",
                        "SEOUL_COMMERCIAL",
                        "WORKER",
                        "seoul-dong-v1",
                        "QUARTER",
                        List.of("20262"),
                        "SEOUL_ADSTRD8",
                        "NONE",
                        referenceVersion,
                        referenceSha,
                        "mapping.json",
                        NationalRawValidator.sha256(maps),
                        "https://example.test/source",
                        "2026-11-11T00:00:00Z",
                        "raw.json",
                        NationalRawValidator.sha256(raw),
                        "UTF-8",
                        rows.size(),
                        aliases,
                        List.of(
                                new Metric(
                                        "tot_wrc_popltn_co",
                                        "PERSON",
                                        "SOURCE_DEFINED",
                                        definition)),
                        Map.of("fixture", true));
        Path p = dir.resolve("manifest.json");
        Files.write(p, mapper.writeValueAsBytes(m));
        return p;
    }

    private void rewrite(Path path, String key, Object value) throws Exception {
        var json =
                new LinkedHashMap<String, Object>(
                        mapper.readValue(
                                Files.readAllBytes(path),
                                new tools.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {}));
        json.put(key, value);
        Files.write(path, mapper.writeValueAsBytes(json));
    }
}
