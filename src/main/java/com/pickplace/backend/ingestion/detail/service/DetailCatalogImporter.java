package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 전체 목록을 먼저 확인하고 파일별로 적재한다. 재실행하면 앞서 완료된 배치는 재사용한다. */
@Service
public class DetailCatalogImporter {
    private final DetailInputLoader loader;
    private final RegionalDetailImporter importer;
    private final JsonMapper mapper;

    public DetailCatalogImporter(
            DetailInputLoader loader, RegionalDetailImporter importer, JsonMapper mapper) {
        this.loader = loader;
        this.importer = importer;
        this.mapper = mapper;
    }

    public List<RegionalDetailImporter.Result> importCatalog(Path path) throws IOException {
        require(Files.size(path) > 0 && Files.size(path) <= 2 * 1024 * 1024, "적재 목록 크기 오류");
        var catalog = mapper.readTree(Files.readAllBytes(path));
        var files = catalog.path("files");
        require(files.isArray() && !files.isEmpty() && files.size() <= 1000, "적재 목록이 필요합니다.");
        var inputs = new ArrayList<DetailInputLoader.Input>();
        var names = new HashSet<String>();
        for (var file : files) {
            String name = file.path("manifest").asText();
            require(
                    name.matches("[A-Za-z0-9._-]+\\.manifest\\.json") && names.add(name),
                    "적재 명세 파일명·중복 오류");
            var input = loader.load(path.toAbsolutePath().getParent().resolve(name));
            require(
                    catalog.path("referenceSha256")
                                    .asText()
                                    .equals(input.manifest().referenceSha256())
                            && file.path("sha256").asText().equals(input.manifest().rawSha256())
                            && file.path("rows").asLong() == input.manifest().expectedRows()
                            && file.path("dataset")
                                    .asText()
                                    .toUpperCase(Locale.ROOT)
                                    .equals(input.manifest().datasetCode()),
                    "목록과 명세 내용 불일치");
            var periods = new HashSet<String>();
            file.path("periods").forEach(p -> periods.add(p.asText()));
            require(periods.equals(new HashSet<>(input.manifest().periods())), "목록과 명세 분기 불일치");
            inputs.add(input);
        }
        var results = new ArrayList<RegionalDetailImporter.Result>();
        for (var input : inputs) {
            var result = importer.importData(input);
            results.add(result);
            System.out.println(input.raw().getFileName() + ": " + result);
        }
        return List.copyOf(results);
    }
}
