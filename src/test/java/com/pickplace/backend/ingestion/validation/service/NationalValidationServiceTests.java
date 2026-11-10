package com.pickplace.backend.ingestion.validation.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import static org.assertj.core.api.Assertions.*;
import static com.pickplace.backend.ingestion.validation.service.ValidationFixtures.*;

class NationalValidationServiceTests {
    @TempDir Path temp;

    @Test void validatesPopulationAndKeepsOriginalCollectionTime() throws Exception {
        var input = prepare("POPULATION_MONTH", List.of(population()), List.of());
        var report = run(input, temp.resolve("out"));
        assertThat(report.sourceRows()).isEqualTo(1);
        assertThat(report.dispositions()).containsEntry("ACCEPTED", 1L);
        assertThat(report.collectedAt().toString()).isEqualTo("2026-11-08T12:25:07Z");
        assertThat(report.classificationVersion()).isNull();
        assertThat(report.published()).isFalse(); assertThat(report.databaseModified()).isFalse();
        assertThat(report.rowsSha256()).isEqualTo(NationalRawValidator.sha256(temp.resolve("out/rows.jsonl")));
    }

    @Test void zipQuotedCommasAndNewlinesKeepLogicalRowNumbersAndGlobalDuplicates() throws Exception {
        var c = store("STORE1");
        var input = prepare("STORE_SNAPSHOT", List.of(c), List.of(c));
        var report = run(input, temp.resolve("out"));
        assertThat(report.dispositions()).containsEntry("ACCEPTED", 1L).containsEntry("DUPLICATE", 1L);
        var rows = rows(temp.resolve("out/rows.jsonl"));
        assertThat(rows).allMatch(r -> r.position().rowNumber() == 1);
        assertThat(rows).extracting(r -> r.position().artifactNo()).containsExactly(2, 3);
        assertThat(report.artifactNumbers()).containsEntry("raw.zip", 1).containsEntry("a.csv", 2).containsEntry("b.csv", 3);
        assertThat(rows.getFirst().store().name()).isEqualTo("카페, 테스트\n본점");
        assertThat(report.countImpacts()).isEmpty();
    }

    @Test void partialCountHasExplicitImpactRatherThanBeingReportedAsComplete() throws Exception {
        var valid = new ArrayList<List<String>>();
        for (int i = 0; i < 97; i++) valid.add(store("VALID" + i));
        for (int i = 0; i < 3; i++) {
            var c = store("CONFLICT"); c.set(31, "서로 다른 주소 " + i); valid.add(c);
        }
        var input = prepare("STORE_SNAPSHOT", valid.subList(0, 50), valid.subList(50, 100));
        var report = run(input, temp.resolve("out"));
        assertThat(report.sourceRows()).isEqualTo(100);
        assertThat(report.dispositions()).containsEntry("ACCEPTED", 97L).containsEntry("HOLD", 3L);
        assertThat(report.countImpacts()).containsEntry("REGION_INDUSTRY:1111051500:I21201", 3L);
        assertThat(report.blocksNewPublication()).isFalse();
        assertThat(report.published()).isFalse();
    }

    @Test void unknownRegionBlocksNewPublicationWithoutInventingZeroCoverage() throws Exception {
        var unknown = store("UNKNOWN"); unknown.set(15, "99999999");
        var input = prepare("STORE_SNAPSHOT", List.of(store("KNOWN")), List.of(unknown));
        var report = run(input, temp.resolve("out"));
        assertThat(report.blocksNewPublication()).isTrue();
        assertThat(report.dispositions()).containsEntry("ACCEPTED", 1L).containsEntry("HOLD", 1L);
        assertThat(report.observedSourceRegions()).containsOnlyKeys("11110515", "99999999");
    }

    @Test void sameBytesWithWrongReceiptAreRejectedBeforeCreatingOutput() throws Exception {
        var input = prepare("POPULATION_MONTH", List.of(population()), List.of());
        Files.writeString(input.metadata(), "{}");
        assertThatThrownBy(() -> run(input, temp.resolve("out"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test void tamperedRawAndWrongReferenceHashAreRejected() throws Exception {
        var input = prepare("POPULATION_MONTH", List.of(population()), List.of());
        assertThatThrownBy(() -> new NationalValidationService(JSON).validate(input.source(), input.raw(), input.metadata(),
                input.snapshot(), "b".repeat(64), temp.resolve("out"))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(input.raw(), "tampered");
        assertThatThrownBy(() -> run(input, temp.resolve("out"))).isInstanceOf(java.io.IOException.class);
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test void existingOutputIsNeverOverwritten() throws Exception {
        var input = prepare("POPULATION_MONTH", List.of(population()), List.of());
        Path out = temp.resolve("out"); run(input, out);
        String hash = NationalRawValidator.sha256(out.resolve("report.json"));
        assertThatThrownBy(() -> run(input, out)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NationalRawValidator.sha256(out.resolve("report.json"))).isEqualTo(hash);
        try (var paths = Files.list(temp)) {
            assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".validation-pending-"));
        }
    }

    private NationalValidationService.Report run(Input input, Path out) throws Exception {
        return new NationalValidationService(JSON).validate(input.source(), input.raw(), input.metadata(), input.snapshot(),
                NationalRawValidator.sha256(input.snapshot()), out);
    }
    private List<ValidationRow> rows(Path path) throws Exception {
        var result = new ArrayList<ValidationRow>();
        for (String line : Files.readAllLines(path)) result.add(JSON.readValue(line, ValidationRow.class));
        return result;
    }
    private Input prepare(String dataset, List<List<String>> first, List<List<String>> second) throws Exception {
        var template = source(dataset);
        Path raw = temp.resolve(template.format().equals("ZIP") ? "raw.zip" : "raw.csv");
        if (template.format().equals("ZIP")) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(raw))) {
                int i = 0;
                for (var rows : List.of(first, second)) {
                    zip.putNextEntry(new ZipEntry(template.csvMembers().get(i++)));
                    zip.write(csv(template.headers(), rows).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
            }
        } else Files.writeString(raw, csv(template.headers(), first));
        var source = new NationalDownloadSource(template.dataset(), template.sourceSystem(), template.sourceId(), template.periodType(),
                template.periodKey(), template.referenceOn(), template.sourceLabel(), template.sourcePage(), template.downloadUrl(),
                template.format(), template.encoding(), Files.size(raw), NationalRawValidator.sha256(raw), first.size() + second.size(),
                template.headers(), template.csvMembers());
        Path snapshot = temp.resolve("snapshot.json");
        Files.write(snapshot, JSON.writeValueAsBytes(snapshot(source)));
        Path metadata = temp.resolve("metadata.json");
        Files.write(metadata, JSON.writeValueAsBytes(Map.ofEntries(Map.entry("schemaVersion", 1), Map.entry("httpStatus", 200),
                Map.entry("dataset", dataset), Map.entry("sourceSystem", source.sourceSystem()), Map.entry("sourceId", source.sourceId()),
                Map.entry("periodType", source.periodType()), Map.entry("periodKey", source.periodKey()),
                Map.entry("sourceReferenceOn", source.referenceOn()), Map.entry("sourceLabel", source.sourceLabel()),
                Map.entry("sourcePage", source.sourcePage()), Map.entry("requestedUrl", source.downloadUrl()), Map.entry("format", source.format()),
                Map.entry("encoding", source.encoding()), Map.entry("rawFile", raw.getFileName().toString()), Map.entry("bytes", source.expectedBytes()),
                Map.entry("sha256", source.sha256()), Map.entry("collectedAt", "2026-11-08T12:25:07Z"),
                Map.entry("rowNumbering", "DATA_RECORD_1_BASED_HEADER_EXCLUDED"))));
        return new Input(source, raw, metadata, snapshot);
    }
    private String csv(List<String> headers, List<List<String>> rows) throws Exception {
        var text = new StringBuilder();
        try (var printer = CSVFormat.RFC4180.print(text)) {
            printer.printRecord(headers);
            for (var row : rows) printer.printRecord(row);
        }
        return text.toString();
    }
    private record Input(NationalDownloadSource source, Path raw, Path metadata, Path snapshot) {}
}
