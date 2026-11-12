package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Component;

import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** 서울 행정동 Sheet JSON과 연도별 ZIP CSV를 같은 원천 필드명으로 읽는다. */
@Component
public class SeoulDongSourceAdapter implements RegionalSourceAdapter {
    private final JsonMapper mapper;

    public SeoulDongSourceAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String id() {
        return "seoul-dong-v1";
    }

    public void read(DetailInputLoader.Input input, Consumer<Map<String, Object>> consumer)
            throws IOException {
        if (input.raw().toString().endsWith(".zip")) readCsv(input, consumer);
        else readJson(input, consumer);
    }

    private void readJson(DetailInputLoader.Input input, Consumer<Map<String, Object>> consumer)
            throws IOException {
        try (var stream = Files.newInputStream(input.raw());
                var parser = mapper.createParser(stream)) {
            require(parser.nextToken() == JsonToken.START_OBJECT, "Sheet JSON 객체가 필요합니다.");
            boolean found = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.PROPERTY_NAME, "잘못된 JSON 필드");
                String name = parser.currentName();
                parser.nextToken();
                if (!name.equals("DATA")) {
                    parser.skipChildren();
                    continue;
                }
                require(!found && parser.currentToken() == JsonToken.START_ARRAY, "DATA 배열 오류");
                found = true;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(parser.currentToken() == JsonToken.START_OBJECT, "DATA 행 객체 오류");
                    Map<String, Object> row =
                            mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                                    .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .readValue(parser);
                    consumer.accept(normalize(input, row));
                }
            }
            require(found && parser.nextToken() == null, "DATA 누락 또는 JSON 뒤 추가 문서");
        }
    }

    private void readCsv(DetailInputLoader.Input input, Consumer<Map<String, Object>> consumer)
            throws IOException {
        try (var zip = new ZipFile(input.raw().toFile(), Charset.forName("MS949"))) {
            var members = zip.stream().filter(e -> !e.isDirectory()).toList();
            require(
                    members.size() == 1
                            && members.getFirst()
                                    .getName()
                                    .toLowerCase(Locale.ROOT)
                                    .endsWith(".csv")
                            && members.getFirst().getSize() > 0
                            && members.getFirst().getSize() <= 512L * 1024 * 1024,
                    "단일 CSV ZIP이 필요합니다.");
            try (var reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            zip.getInputStream(members.getFirst()),
                                            Charset.forName(input.manifest().encoding())));
                    var csv =
                            CSVFormat.DEFAULT
                                    .builder()
                                    .setHeader()
                                    .setSkipHeaderRecord(true)
                                    .setIgnoreEmptyLines(false)
                                    .get()
                                    .parse(reader)) {
                require(
                        new HashSet<>(csv.getHeaderNames()).size() == csv.getHeaderNames().size(),
                        "CSV 중복 헤더");
                for (var record : csv) {
                    require(record.isConsistent(), "CSV 컬럼 수 불일치");
                    consumer.accept(normalize(input, new LinkedHashMap<>(record.toMap())));
                }
            }
        }
    }

    private Map<String, Object> normalize(DetailInputLoader.Input input, Map<String, Object> raw) {
        var row = new TreeMap<String, Object>();
        raw.forEach(
                (key, value) -> {
                    String canonical = input.manifest().aliases().get(key.replace("\uFEFF", ""));
                    require(canonical != null, "정의되지 않은 원천 필드: " + key);
                    require(!row.containsKey(canonical), "동일 의미 필드 중복");
                    row.put(canonical, value);
                });
        for (String key : List.of("stdr_yyqu_cd", "adstrd_cd", "adstrd_cd_nm"))
            require(row.containsKey(key), "필수 헤더 누락: " + key);
        for (var metric : input.manifest().metrics())
            require(row.containsKey(metric.field()), "지표 헤더 누락: " + metric.field());
        if (!"NONE".equals(input.manifest().industryScheme()))
            require(row.containsKey("svc_induty_cd"), "업종 헤더 누락");
        return row;
    }
}
