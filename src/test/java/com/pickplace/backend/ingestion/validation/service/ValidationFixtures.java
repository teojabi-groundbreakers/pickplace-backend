package com.pickplace.backend.ingestion.validation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalSourceCatalogLoader;
import com.pickplace.backend.ingestion.validation.model.ValidationRow.Position;
import com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot;
import tools.jackson.databind.json.JsonMapper;

final class ValidationFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SHA = "a".repeat(64);
    static final Position POSITION = new Position(1, null, 1);
    static NationalDownloadSource source(String dataset) throws java.io.IOException {
        var original = new NationalSourceCatalogLoader(JSON).find(dataset, "202609");
        return new NationalDownloadSource(dataset, original.sourceSystem(), original.sourceId(), original.periodType(),
                "202609", "2026-09-30", original.sourceLabel(), "https://example.test/source",
                dataset.equals("POPULATION_MONTH") ? "https://example.test/population.csv" : "https://example.test/store.zip",
                original.format(), "UTF-8", 1, SHA, 1, original.headers(),
                dataset.equals("POPULATION_MONTH") ? List.of() : List.of("a.csv", "b.csv"));
    }
    static NationalReferenceSnapshot snapshot(NationalDownloadSource source) throws Exception {
        NationalReferenceSnapshot base;
        try (var input = ValidationFixtures.class.getResourceAsStream("/reference-data/national-reference-fixture.json")) {
            base = JSON.readValue(input, NationalReferenceSnapshot.class);
        }
        var sources = base.sources().stream().map(s -> new NationalReferenceSnapshot.Source(s.system(), s.code(), s.version(),
                s.name(), s.districtCode(), s.districtName(), s.referenceOn(), s.url(),
                "{\"sha256\":\"" + source.sha256() + "\"}", s.collectedAt())).toList();
        var mappings = base.mappings().stream().map(m -> new NationalReferenceSnapshot.Mapping(m.system(), m.sourceCode(),
                m.sourceVersion(), m.targetCode(), m.referenceOn(), m.reviewStatus(), "{\"temporalStatus\":\"WITHIN_RECORDED_PERIOD\"}")).toList();
        return copy(base, base.nodes(), sources, mappings, source.dataset().equals("STORE_SNAPSHOT") ? source.sha256() : SHA);
    }
    static NationalReferenceSnapshot copy(NationalReferenceSnapshot s, List<NationalReferenceSnapshot.Node> nodes,
            List<NationalReferenceSnapshot.Source> sources, List<NationalReferenceSnapshot.Mapping> mappings, String classificationSha) {
        return new NationalReferenceSnapshot(s.version(), s.parserVersion(), s.policyVersion(), s.collectedAt(),
                s.classificationVersion(), s.classificationSourceUrl(), classificationSha, s.referenceOn(),
                s.artifacts(), nodes, s.navigation(), sources, mappings, s.industries());
    }
    static List<String> population() {
        var cells = new ArrayList<>(Collections.nCopies(230, "0"));
        cells.set(0, "1111051500"); cells.set(1, "2026-09-30");
        return cells;
    }
    static List<String> store(String id) {
        var cells = new ArrayList<>(Collections.nCopies(39, ""));
        cells.set(0, id); cells.set(1, "카페, 테스트\n본점"); cells.set(3, "I"); cells.set(5, "I21"); cells.set(7, "I21201");
        cells.set(15, "11110515"); cells.set(17, "1111010100"); cells.set(37, "127.1"); cells.set(38, "37.5");
        return cells;
    }
    static NationalRowValidator validator(String dataset) throws Exception {
        var source = source(dataset);
        return new NationalRowValidator(new ValidationReference(snapshot(source)), source);
    }
    static NationalReferenceSnapshot changeDates(NationalReferenceSnapshot snapshot, LocalDate created, LocalDate abolished) {
        var nodes = snapshot.nodes().stream().map(n -> !n.code().equals("1111051500") ? n : new NationalReferenceSnapshot.Node(
                n.code(), n.province(), n.district(), n.locality(), n.rawCreatedOn(), n.rawAbolishedOn(), created, abolished,
                "VALID", null, n.status(), n.artifactRole(), n.rowNumber())).toList();
        return copy(snapshot, nodes, snapshot.sources(), snapshot.mappings(), snapshot.classificationSourceSha256());
    }
}
