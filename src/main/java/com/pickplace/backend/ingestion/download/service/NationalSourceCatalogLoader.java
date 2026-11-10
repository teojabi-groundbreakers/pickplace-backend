package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import tools.jackson.databind.json.JsonMapper;

public final class NationalSourceCatalogLoader {
    private final JsonMapper mapper;

    public NationalSourceCatalogLoader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public NationalDownloadSource find(String dataset, String month) throws IOException {
        try (var stream = new ClassPathResource("data-download/national-source-catalog.json").getInputStream()) {
            var catalog = mapper.readValue(stream, Catalog.class);
            if (catalog.schemaVersion() != 1 || catalog.sources() == null) throw new IllegalArgumentException("전국 원천 카탈로그 버전 오류");
            var keys = new HashSet<String>();
            for (var source : catalog.sources()) {
                source.validate();
                URI uri = URI.create(source.downloadUrl());
                if (!"https".equals(uri.getScheme()) || !"www.data.go.kr".equals(uri.getHost())
                        || !"/cmm/cmm/fileDownload.do".equals(uri.getPath()) || uri.getRawUserInfo() != null
                        || uri.getFragment() != null || !keys.add(source.dataset() + ":" + source.periodKey())) {
                    throw new IllegalArgumentException("전국 원천 카탈로그의 다운로드 경로 또는 중복 항목 오류");
                }
            }
            return catalog.sources().stream().filter(s -> s.dataset().equals(dataset) && s.periodKey().equals(month))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("지원 데이터·기간은 POPULATION_MONTH/STORE_SNAPSHOT의 202609입니다."));
        }
    }

    private record Catalog(int schemaVersion, List<NationalDownloadSource> sources) {}
}
