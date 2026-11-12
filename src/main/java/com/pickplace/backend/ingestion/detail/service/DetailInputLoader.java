package com.pickplace.backend.ingestion.detail.service;

import com.pickplace.backend.ingestion.detail.model.DetailImportManifest;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.Mappings;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

@Component
public class DetailInputLoader {
    public static final String WRITER_VERSION = "regional-detail-v2";
    private final JsonMapper mapper;

    public DetailInputLoader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Input load(Path manifest) throws IOException {
        require(Files.size(manifest) > 0 && Files.size(manifest) <= 4 * 1024 * 1024, "명세 파일 크기 오류");
        byte[] bytes = Files.readAllBytes(manifest);
        var m = mapper.readValue(bytes, DetailImportManifest.class);
        require("regional-detail-manifest-v1".equals(m.version()), "지원하지 않는 명세 버전");
        require(
                m.sourceSystem() != null
                        && m.sourceSystem().matches("[A-Z][A-Z0-9_]{0,39}")
                        && m.datasetCode() != null
                        && m.datasetCode().matches("[A-Z][A-Z0-9_]{0,39}"),
                "원천·자료 코드 오류");
        require(
                "QUARTER".equals(m.periodType())
                        && m.periods() != null
                        && !m.periods().isEmpty()
                        && new HashSet<>(m.periods()).size() == m.periods().size()
                        && m.periods().stream().allMatch(p -> p.matches("[0-9]{4}[1-4]")),
                "현재 어댑터는 명시된 분기만 처리합니다.");
        require(
                m.expectedRows() > 0
                        && m.aliases() != null
                        && m.metrics() != null
                        && !m.metrics().isEmpty(),
                "건수·필드 정의 필요");
        require(
                m.sourceUrl() != null
                        && m.sourceUrl().startsWith("https://")
                        && m.regionScheme() != null
                        && !m.regionScheme().isBlank()
                        && m.industryScheme() != null
                        && !m.industryScheme().isBlank(),
                "출처·원천 분류 필요");
        if ("seoul-dong-v1".equals(m.adapter()))
            require(
                    "SEOUL_COMMERCIAL".equals(m.sourceSystem())
                            && "SEOUL_ADSTRD8".equals(m.regionScheme())
                            && java.util.Set.of("NONE", "SEOUL_CS100").contains(m.industryScheme()),
                    "서울 어댑터의 원천·분류 체계 오류");
        require(
                m.metrics().stream().map(DetailImportManifest.Metric::field).distinct().count()
                        == m.metrics().size(),
                "중복 지표 정의");
        for (var metric : m.metrics())
            require(
                    metric.field() != null
                            && metric.field().matches("[a-z][a-z0-9_]*")
                            && metric.unit() != null
                            && !metric.unit().isBlank()
                            && metric.aggregation() != null
                            && !metric.aggregation().isBlank()
                            && java.util.Set.of("VERIFIED", "SOURCE_ONLY")
                                    .contains(metric.definitionStatus()),
                    "지표 단위·집계 정의 필요");
        m.collectionTime();
        Path base = manifest.toAbsolutePath().getParent(),
                raw = base.resolve(m.rawFile()).normalize(),
                mapping = base.resolve(m.mappingFile()).normalize();
        require(Files.size(raw) > 0 && Files.size(raw) <= 512L * 1024 * 1024, "원본 크기 오류");
        require(Files.size(mapping) <= 8 * 1024 * 1024, "대응표 크기 오류");
        String mapHash = NationalRawValidator.sha256(mapping);
        require(mapHash.equals(m.mappingSha256()), "대응표 해시 불일치");
        var maps = mapper.readValue(Files.readAllBytes(mapping), Mappings.class);
        require(
                m.referenceVersion().equals(maps.referenceVersion())
                        && m.referenceSha256().equals(maps.referenceSha256())
                        && maps.regions() != null,
                "대응표 기준정보 불일치");
        var keys = new HashSet<String>();
        for (var r : maps.regions())
            require(
                    r.sourceCode() != null
                            && r.sourceCode().matches("[0-9]{8}")
                            && keys.add(r.sourceCode())
                            && r.targetCode() != null
                            && r.targetCode().matches("[0-9]{10}")
                            && r.sourceName() != null
                            && !r.sourceName().isBlank()
                            && r.evidence() != null
                            && !r.evidence().isBlank(),
                    "대응표 키·근거 오류");
        var input =
                new Input(
                        m,
                        raw,
                        mapping,
                        mapper.writeValueAsString(maps),
                        NationalReferenceLoader.sha256(bytes),
                        mapHash);
        input.verify();
        return input;
    }

    public record Input(
            DetailImportManifest manifest,
            Path raw,
            Path mapping,
            String mappingJson,
            String manifestHash,
            String mappingHash) {
        public void verify() throws IOException {
            require(NationalRawValidator.sha256(raw).equals(manifest.rawSha256()), "원본 해시 불일치");
            require(NationalRawValidator.sha256(mapping).equals(mappingHash), "실행 중 대응표가 변경됐습니다.");
        }
    }

    public static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
