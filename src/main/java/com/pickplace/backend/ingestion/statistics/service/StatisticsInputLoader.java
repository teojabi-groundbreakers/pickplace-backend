package com.pickplace.backend.ingestion.statistics.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.download.service.NationalSourceCatalogLoader;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.ingestion.validation.service.NationalValidationService;
import com.pickplace.backend.ingestion.validation.service.ValidationReference;
import com.pickplace.backend.reference.nationwide.InitialRegionSelectionPolicy;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferenceValidator;
import tools.jackson.databind.json.JsonMapper;

/** T13 출력과 원본·수집 기록·기준정보가 같은 입력인지 확인한다. 이 단계는 DB를 수정하지 않는다. */
@Component
public class StatisticsInputLoader {
    private final JsonMapper mapper;
    private final SourceLookup catalog;

    @org.springframework.beans.factory.annotation.Autowired
    public StatisticsInputLoader(JsonMapper mapper) {
        this(mapper, new NationalSourceCatalogLoader(mapper)::find);
    }

    // 작은 원본으로도 같은 검증 경로를 시험할 수 있도록 카탈로그 조회만 교체 가능하게 한다.
    StatisticsInputLoader(JsonMapper mapper, SourceLookup catalog) { this.mapper = mapper; this.catalog = catalog; }

    public StatisticsImportInput load(Path directory, Path raw, Path metadata, Path reference) throws IOException {
        Path reportFile = directory.resolve("report.json");
        require(Files.size(reportFile) <= 2 * 1024 * 1024 && Files.size(metadata) <= 1024 * 1024, "입력 보고서 크기 초과");
        byte[] reportBytes = Files.readAllBytes(reportFile);
        var report = mapper.readValue(reportBytes, NationalValidationService.Report.class);
        require(report.schemaVersion() == 1 && "VALIDATED".equals(report.status())
                && !report.databaseModified() && !report.published(), "T13 검증 완료 보고서가 아닙니다.");
        require(NationalValidationService.PARSER_VERSION.equals(report.parserVersion())
                && NationalValidationService.POLICY_VERSION.equals(report.policyVersion()), "지원하지 않는 T13 규칙 버전입니다.");
        var source = catalog.find(report.dataset(), report.periodKey());
        source.validate();
        require(source.sha256().equals(report.rawSha256()) && source.expectedRows() == report.sourceRows()
                && source.referenceOn().equals(report.sourceReferenceOn()) && source.sourceLabel().equals(report.sourceLabel()), "원본과 T13 보고서 불일치");
        require(report.dispositions() != null && report.dispositions().keySet().equals(java.util.Set.of("ACCEPTED", "HOLD", "REJECTED", "DUPLICATE"))
                && report.dispositions().values().stream().allMatch(v -> v != null && v >= 0)
                && report.dispositions().values().stream().mapToLong(Long::longValue).sum() == report.sourceRows(), "T13 행 회계 오류");
        require(report.collectedAt() != null && report.validatedAt() != null, "T13 시각 정보 누락");
        var receipt = mapper.readTree(Files.readAllBytes(metadata));
        require(receipt.path("schemaVersion").asInt() == 1 && receipt.path("httpStatus").asInt() == 200
                && source.dataset().equals(receipt.path("dataset").asText())
                && source.sourceSystem().equals(receipt.path("sourceSystem").asText())
                && source.periodKey().equals(receipt.path("periodKey").asText())
                && source.downloadUrl().equals(receipt.path("requestedUrl").asText())
                && source.sha256().equals(receipt.path("sha256").asText())
                && source.expectedBytes() == receipt.path("bytes").asLong()
                && report.collectedAt().equals(Instant.parse(receipt.path("collectedAt").asText())), "수집 기록 불일치");
        var loaded = new NationalReferenceLoader(mapper).load(reference, report.mappingSha256());
        var snapshot = loaded.snapshot();
        require(snapshot.version().equals(report.mappingVersion())
                && new NationalReferenceValidator(new InitialRegionSelectionPolicy(mapper)).validate(snapshot).approvable(), "검토된 기준정보가 아닙니다.");
        require(new ValidationReference(snapshot).sourceVersion(source).equals(report.sourceRegionVersion()), "원천 지역 버전 불일치");
        require(source.dataset().equals("STORE_SNAPSHOT") ? snapshot.classificationVersion().equals(report.classificationVersion())
                : report.classificationVersion() == null, "분류 버전 불일치");
        var numbers = new LinkedHashMap<String, Integer>();
        numbers.put(source.format().equals("ZIP") ? "raw.zip" : "raw.csv", 1);
        int no = 2;
        for (String member : source.csvMembers()) numbers.put(member, no++);
        require(numbers.equals(report.artifactNumbers()), "원본 번호 대응표 불일치");
        var input = new StatisticsImportInput(report, source, snapshot, directory.resolve("rows.jsonl"), directory.resolve("issues.jsonl"),
                raw.toAbsolutePath().normalize(), metadata, reference, reportFile, NationalReferenceLoader.sha256(reportBytes),
                snapshot.sources().stream().filter(s -> s.system().equals(source.sourceSystem()) && s.version().equals(report.sourceRegionVersion()))
                        .map(s -> s.code()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        input.verifyUnchanged();
        // 멤버 이름만 맞는 ZIP을 신뢰하지 않고 실제 해시·CRC·헤더·행 수를 다시 대조한다.
        require(new NationalRawValidator().validate(raw, source).equals(report.rawValidation()), "T12 원본 구조와 T13 검사 기록 불일치");
        return input;
    }

    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    @FunctionalInterface interface SourceLookup { NationalDownloadSource find(String dataset, String period) throws IOException; }
}
