package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 검증한 전국 상가업소 집계를 원천 코드와 함께 읽는다. */
@Component
public class NationalStoreQuarterAdapter implements RegionalSourceAdapter {
    static final String ID = "semas-store-quarter-v1";
    static final String FIELD = "listed_store_count";
    private static final Set<String> FIELDS =
            Set.of("stdr_yyqu_cd", "adstrd_cd", "adstrd_cd_nm", "svc_induty_cd", FIELD);
    private final JsonMapper mapper;

    public NationalStoreQuarterAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void read(DetailInputLoader.Input input, Consumer<Map<String, Object>> consumer) throws IOException {
        try (var reader = Files.newBufferedReader(input.raw())) {
            String line;
            while ((line = reader.readLine()) != null) {
                require(line.length() <= 4096, "전국 업소 집계 행 길이 초과");
                Map<String, Object> row = mapper.readValue(line, new TypeReference<>() {});
                require(row != null && row.keySet().equals(FIELDS)
                        && row.get(FIELD) instanceof Number count
                        && count.toString().matches("[0-9]+"), "전국 업소 집계 필드 오류");
                consumer.accept(row);
            }
        }
    }
}
