package com.pickplace.backend.ingestion.statistics.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.csv.CSVFormat;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.download.service.NationalSourceCatalogLoader;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.ingestion.validation.service.NationalValidationService;
import com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot;
import tools.jackson.databind.json.JsonMapper;

/** 공식 원본 대신 작은 파일로 같은 T12→T13→T14 경로를 검증한다. 예시 수치는 실제 통계가 아니다. */
final class StatisticsTestFixture {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String REGION = "1111051500";
    static List<String> population(String month) {
        var c = new ArrayList<>(Collections.nCopies(230, "0"));
        c.set(0, REGION); c.set(1, date(month)); c.set(5, "100"); c.set(6, "40"); c.set(7, "60"); c.set(8, "40"); c.set(119, "60");
        return c;
    }
    static List<String> store(String id) {
        var c = new ArrayList<>(Collections.nCopies(39, ""));
        c.set(0, id); c.set(1, "카페, 검증\n본점"); c.set(3, "I"); c.set(5, "I21"); c.set(7, "I21201");
        c.set(15, "11110515"); c.set(17, "1111010100"); c.set(37, "127.1"); c.set(38, "37.5");
        return c;
    }

    static Prepared prepare(Path directory, String month, List<List<String>> stores) throws Exception {
        Files.createDirectories(directory);
        var pop = source(directory, "POPULATION_MONTH", month, List.of(population(month)));
        var shop = source(directory, "STORE_SNAPSHOT", month, stores);
        NationalReferenceSnapshot base;
        try (var stream = StatisticsTestFixture.class.getResourceAsStream("/reference-data/national-reference-fixture.json")) {
            base = JSON.readValue(stream, NationalReferenceSnapshot.class);
        }
        String suffix = UUID.randomUUID().toString();
        LocalDate on = LocalDate.parse(date(month));
        var sources = base.sources().stream().map(s -> new NationalReferenceSnapshot.Source(s.system(), s.code(), s.version() + suffix,
                s.name(), s.districtCode(), s.districtName(), on, s.url(),
                "{\"sha256\":\"" + (s.system().equals("MOIS_RESIDENT") ? pop.sha256() : shop.sha256()) + "\"}", s.collectedAt())).toList();
        var mappings = base.mappings().stream().map(m -> new NationalReferenceSnapshot.Mapping(m.system(), m.sourceCode(),
                m.sourceVersion() + suffix, m.targetCode(), on, "VERIFIED", "{\"temporalStatus\":\"WITHIN_RECORDED_PERIOD\"}")).toList();
        var industries = new ArrayList<>(base.industries());
        industries.add(new NationalReferenceSnapshot.Industry("I21001", "빵/도넛", "SMALL", "I21", "빵/도넛", true, 2));
        var snapshot = new NationalReferenceSnapshot("stats-" + suffix, base.parserVersion(), base.policyVersion(), base.collectedAt(),
                "class-" + suffix, shop.downloadUrl(), shop.sha256(), on, base.artifacts(), base.nodes(), base.navigation(), sources, mappings, industries);
        Path reference = directory.resolve("snapshot.json"); Files.write(reference, JSON.writeValueAsBytes(snapshot));
        String sha = NationalRawValidator.sha256(reference);
        for (var source : List.of(pop, shop)) {
            Path sub = directory.resolve(source.dataset()); Files.createDirectory(sub);
            var receipt = new java.util.LinkedHashMap<String, Object>();
            receipt.put("schemaVersion", 1); receipt.put("httpStatus", 200); receipt.put("dataset", source.dataset());
            receipt.put("sourceSystem", source.sourceSystem()); receipt.put("sourceId", source.sourceId()); receipt.put("periodType", source.periodType());
            receipt.put("periodKey", month); receipt.put("sourceReferenceOn", source.referenceOn()); receipt.put("sourceLabel", source.sourceLabel());
            receipt.put("sourcePage", source.sourcePage()); receipt.put("requestedUrl", source.downloadUrl()); receipt.put("format", source.format());
            receipt.put("encoding", source.encoding()); receipt.put("rawFile", source.format().equals("ZIP") ? "raw.zip" : "raw.csv");
            receipt.put("bytes", source.expectedBytes()); receipt.put("sha256", source.sha256()); receipt.put("collectedAt", "2026-11-08T12:25:07Z");
            receipt.put("rowNumbering", "DATA_RECORD_1_BASED_HEADER_EXCLUDED");
            Path metadata = sub.resolve("metadata.json"); Files.write(metadata, JSON.writeValueAsBytes(receipt));
            new NationalValidationService(JSON).validate(source, directory.resolve(source.format().equals("ZIP") ? "raw.zip" : "raw.csv"),
                    metadata, reference, sha, sub.resolve("validated"));
        }
        return new Prepared(directory, pop, shop, reference, snapshot);
    }

    static StatisticsImportInput load(Prepared p, String dataset) throws Exception {
        var loader = new StatisticsInputLoader(JSON, (d, m) -> d.equals("POPULATION_MONTH") ? p.population() : p.stores());
        return loader.load(p.directory().resolve(dataset).resolve("validated"), p.directory().resolve(dataset.equals("POPULATION_MONTH") ? "raw.csv" : "raw.zip"),
                p.directory().resolve(dataset).resolve("metadata.json"), p.reference());
    }

    private static NationalDownloadSource source(Path directory, String dataset, String month, List<List<String>> rows) throws Exception {
        var template = new NationalSourceCatalogLoader(JSON).find(dataset, "202609");
        var text = new StringBuilder();
        try (var csv = CSVFormat.RFC4180.print(text)) { csv.printRecord(template.headers()); for (var row : rows) csv.printRecord(row); }
        Path raw = directory.resolve(dataset.equals("POPULATION_MONTH") ? "raw.csv" : "raw.zip");
        if (dataset.equals("POPULATION_MONTH")) Files.writeString(raw, text);
        else try (var zip = new ZipOutputStream(Files.newOutputStream(raw))) {
            zip.putNextEntry(new ZipEntry("stores.csv")); zip.write(text.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return new NationalDownloadSource(dataset, template.sourceSystem(), template.sourceId(), template.periodType(), month, date(month),
                template.sourceLabel(), "https://example.test/source", dataset.equals("POPULATION_MONTH") ? "https://example.test/population.csv" : "https://example.test/store.zip",
                template.format(), "UTF-8", Files.size(raw), NationalRawValidator.sha256(raw), rows.size(), template.headers(),
                dataset.equals("POPULATION_MONTH") ? List.of() : List.of("stores.csv"));
    }
    private static String date(String month) { return YearMonth.of(Integer.parseInt(month.substring(0, 4)), Integer.parseInt(month.substring(4))).atEndOfMonth().toString(); }
    record Prepared(Path directory, NationalDownloadSource population, NationalDownloadSource stores, Path reference, NationalReferenceSnapshot snapshot) {}
}
