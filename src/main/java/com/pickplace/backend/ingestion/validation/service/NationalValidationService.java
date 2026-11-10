package com.pickplace.backend.ingestion.validation.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferenceValidator;
import com.pickplace.backend.reference.nationwide.InitialRegionSelectionPolicy;
import tools.jackson.databind.json.JsonMapper;

/**
 * T12 원본과 기준정보를 고정하고 T13 검증 과정을 실행한다.
 * 출처 확인 → 원본 구조 검사 → 행 정제 → 중복 확정 → 회계·결과 저장 순서로 진행한다.
 * VALIDATED는 검증 실행 완료를 뜻하며 DB 적재·승인·게시 완료를 뜻하지 않는다.
 */
public final class NationalValidationService {
    public static final String PARSER_VERSION = "national-statistics-parser-v1";
    public static final String POLICY_VERSION = "national-statistics-quality-v1";
    private final JsonMapper mapper;
    private static final CSVFormat CSV = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(false).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();

    public NationalValidationService(JsonMapper mapper) { this.mapper = mapper; }

    public Report validate(NationalDownloadSource source, Path raw, Path receipt, Path snapshotPath,
            String snapshotSha256, Path output) throws IOException {
        source.validate();
        Path destination = output.toAbsolutePath().normalize();
        // 이전 결과는 비교·추적에 필요하므로 덮어쓰지 않는다. 재실행은 새 결과 경로를 사용한다.
        if (Files.exists(destination)) throw new IllegalArgumentException("결과 폴더가 이미 있습니다. 새 경로를 지정하세요.");
        if (Files.size(receipt) > 1024 * 1024) throw new IllegalArgumentException("수집 메타데이터 크기 초과");
        // 읽은 메타데이터 바이트를 유지해 보고서의 해시와 실제로 사용한 내용이 일치하게 한다.
        byte[] receiptBytes = Files.readAllBytes(receipt);
        var metadata = mapper.readTree(receiptBytes);
        if (metadata.path("schemaVersion").asInt() != 1 || metadata.path("httpStatus").asInt() != 200
                || !source.dataset().equals(metadata.path("dataset").asText())
                || !source.sourceSystem().equals(metadata.path("sourceSystem").asText())
                || !source.sourceId().equals(metadata.path("sourceId").asText())
                || !source.periodType().equals(metadata.path("periodType").asText())
                || !source.periodKey().equals(metadata.path("periodKey").asText())
                || !source.referenceOn().equals(metadata.path("sourceReferenceOn").asText())
                || !source.sourceLabel().equals(metadata.path("sourceLabel").asText())
                || !source.downloadUrl().equals(metadata.path("requestedUrl").asText())
                || !source.sourcePage().equals(metadata.path("sourcePage").asText())
                || !source.format().equals(metadata.path("format").asText())
                || !source.encoding().equals(metadata.path("encoding").asText())
                || !"raw.".concat(source.format().equals("ZIP") ? "zip" : "csv").equals(metadata.path("rawFile").asText())
                || !source.sha256().equals(metadata.path("sha256").asText())
                || source.expectedBytes() != metadata.path("bytes").asLong()
                || !"DATA_RECORD_1_BASED_HEADER_EXCLUDED".equals(metadata.path("rowNumbering").asText()))
            throw new IllegalArgumentException("T12 수집 메타데이터와 원천 카탈로그가 다릅니다.");
        // 지금 실행한 시각과 원본을 수집한 시각을 구분한다. 원래 수집 시각을 새 값으로 덮지 않는다.
        Instant collectedAt = Instant.parse(metadata.path("collectedAt").asText());
        var snapshot = new NationalReferenceLoader(mapper).load(snapshotPath, snapshotSha256).snapshot();
        // 파일 자체의 검토 조건을 검사한다. 실제 DB에서 APPROVED인지 확인하는 일은 T14에서 한다.
        if (!new NationalReferenceValidator(new InitialRegionSelectionPolicy(mapper)).validate(snapshot).approvable())
            throw new IllegalArgumentException("기준정보 검토가 완료된 스냅샷이 아닙니다.");
        var reference = new ValidationReference(snapshot);
        String sourceVersion = reference.sourceVersion(source);
        var rowValidator = new NationalRowValidator(reference, source);
        // 정제를 시작하기 전에 T12의 무결성·인코딩·헤더·ZIP 멤버 검사도 다시 수행한다.
        var rawValidation = new NationalRawValidator().validate(raw, source);
        Files.createDirectories(destination.getParent());
        // 완성 전 파일이 정상 결과로 보이지 않게 최종 폴더 옆의 임시 폴더에서 작업한다.
        Path staging = Files.createTempDirectory(destination.getParent(), ".validation-pending-");
        try {
            var counters = new Counters();
            try (var partitions = new PartitionedRowProcessor(staging.resolve("partitions"), mapper)) {
                if (source.format().equals("CSV")) {
                    try (var input = Files.newInputStream(raw)) { parse(input, source, 1, null, rowValidator, partitions); }
                } else {
                    try (var zip = new ZipFile(raw.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                        // 업소 ZIP 자체가 1번이고, CSV 멤버는 카탈로그 순서대로 2번부터 부여한다.
                        int artifactNo = 2;
                        for (String member : source.csvMembers()) {
                            try (var input = zip.getInputStream(zip.getEntry(member))) {
                                parse(input, source, artifactNo++, member, rowValidator, partitions);
                            }
                        }
                    }
                }
                try (var rows = Files.newBufferedWriter(staging.resolve("rows.jsonl"));
                        var issues = Files.newBufferedWriter(staging.resolve("issues.jsonl"))) {
                    // 중복 판정이 확정된 행만 기록한다. 이미 쓴 첫 행을 나중에 수정하는 방식을 피한다.
                    partitions.finish(row -> {
                        rows.write(mapper.writeValueAsString(row)); rows.newLine();
                        counters.add(row);
                        // 동일 복사본의 문제를 다시 더하면 영향 행 수가 부풀어 오르므로 별도 이슈 출력에서 뺀다.
                        if (!row.issues().isEmpty() && !row.disposition().equals("DUPLICATE")) {
                            issues.write(mapper.writeValueAsString(Map.of("position", row.position(), "sourceKey", row.sourceKey(), "issues", row.issues())));
                            issues.newLine();
                        }
                    });
                }
            }
            // 어떤 행도 사라지거나 두 번 계산되지 않았는지 원천행 회계를 맞춘다.
            long total = counters.dispositions.values().stream().mapToLong(Long::longValue).sum();
            if (total != rawValidation.rows()) throw new IOException("원천행 회계 불일치");
            // 구조 검사 후 원본이 바뀌면 결과와 원본 해시가 어긋난다. 저장 확정 직전에 다시 확인한다.
            if (!source.sha256().equals(NationalRawValidator.sha256(raw))) throw new IOException("검사 중 원본이 변경되었습니다.");
            // ZIP 내부 열거 순서와 무관하게 원본 위치를 복원할 수 있도록 번호 대응표를 명시한다.
            var artifactNumbers = new java.util.LinkedHashMap<String, Integer>();
            artifactNumbers.put(source.format().equals("ZIP") ? "raw.zip" : "raw.csv", 1);
            int nextArtifact = 2;
            for (String member : source.csvMembers()) artifactNumbers.put(member, nextArtifact++);
            var report = new Report(1, "VALIDATED", source.dataset(), source.periodKey(), source.referenceOn(),
                    source.sourceLabel(), source.sha256(), NationalReferenceLoader.sha256(receiptBytes), collectedAt,
                    snapshot.version(), snapshotSha256, sourceVersion,
                    source.dataset().equals("STORE_SNAPSHOT") ? snapshot.classificationVersion() : null,
                    PARSER_VERSION, POLICY_VERSION, Instant.now(), total, counters.dispositions, counters.issueCounts,
                    counters.impacts, counters.coverage, counters.globalHold, false, false,
                    NationalRawValidator.sha256(staging.resolve("rows.jsonl")),
                    NationalRawValidator.sha256(staging.resolve("issues.jsonl")), artifactNumbers, rawValidation);
            Files.write(staging.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
            // 모든 결과·해시·보고서가 준비됐을 때만 폴더를 한 번에 확정한다.
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            return report;
        } catch (IOException | RuntimeException exception) {
            cleanup(staging, exception);
            throw exception;
        }
    }

    private void parse(InputStream input, NationalDownloadSource source, int artifact, String member,
            NationalRowValidator validator, PartitionedRowProcessor partitions) throws IOException {
        var decoder = Charset.forName(source.encoding()).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new PushbackReader(new InputStreamReader(input, decoder), 1);
        int first = reader.read(); if (first != -1 && first != 0xFEFF) reader.unread(first);
        try (var parser = CSV.parse(reader)) {
            if (!parser.getHeaderNames().equals(source.headers())) throw new IOException("헤더 불일치");
            for (var record : parser) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("정제 검사가 중단되었습니다.");
                if (!record.isConsistent()) throw new IOException("CSV 열 수 불일치");
                var cells = record.toList();
                partitions.add(cells, validator.validate(cells, new ValidationRow.Position(artifact, member, record.getRecordNumber())));
            }
        } catch (UncheckedIOException exception) { throw exception.getCause(); }
    }

    private static void cleanup(Path ownedDirectory, Exception failure) {
        // 이번 실행이 만든 임시 폴더만 정리한다. 원본과 이전 실행 결과는 건드리지 않는다.
        try (var paths = Files.walk(ownedDirectory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(ownedDirectory.toAbsolutePath().normalize()))
                    throw new IOException("임시 경로 범위 오류");
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    private static final class Counters {
        final Map<String, Long> dispositions = new TreeMap<>(Map.of("ACCEPTED", 0L, "HOLD", 0L, "REJECTED", 0L, "DUPLICATE", 0L));
        final Map<String, Long> issueCounts = new TreeMap<>();
        final Map<String, Long> impacts = new TreeMap<>();
        final Map<String, Map<String, Long>> coverage = new TreeMap<>();
        boolean globalHold;
        void add(ValidationRow row) {
            dispositions.merge(row.disposition(), 1L, Long::sum);
            // 실제 관측한 원천 지역만 남긴다. 행이 없는 지역을 업소 수 0으로 생성하지 않는다.
            coverage.computeIfAbsent(row.sourceRegionCode(), ignored -> new TreeMap<>()).merge(row.disposition(), 1L, Long::sum);
            if (row.disposition().equals("DUPLICATE")) return;
            // 한 행에 같은 범위의 오류가 여러 개 있어도 집계 영향 행 수는 한 번만 센다.
            var rowImpacts = new java.util.HashSet<String>();
            for (var issue : row.issues()) {
                issueCounts.merge(issue.code(), 1L, Long::sum);
                if (issue.affectsCount()) {
                    // 지역조차 특정하지 못하면 새 게시를 막아야 한다. 기존 게시 포인터를 바꾸지는 않는다.
                    globalHold |= issue.impactScope().equals("GLOBAL");
                    rowImpacts.add(issue.impactScope() + ":" + issue.affectedRegion() + ":" + issue.affectedIndustry());
                }
            }
            rowImpacts.forEach(key -> impacts.merge(key, 1L, Long::sum));
        }
    }

    // T14는 이 보고서의 버전·해시·영향 범위를 확인한 뒤 실제 승인된 DB 릴리스에 연결해야 한다.
    public record Report(int schemaVersion, String status, String dataset, String periodKey,
            String sourceReferenceOn, String sourceLabel, String rawSha256, String receiptSha256, Instant collectedAt,
            String mappingVersion, String mappingSha256, String sourceRegionVersion, String classificationVersion,
            String parserVersion, String policyVersion, Instant validatedAt, long sourceRows,
            Map<String, Long> dispositions, Map<String, Long> issueCounts, Map<String, Long> countImpacts,
            Map<String, Map<String, Long>> observedSourceRegions, boolean blocksNewPublication,
            boolean databaseModified, boolean published, String rowsSha256, String issuesSha256,
            Map<String, Integer> artifactNumbers, NationalRawValidator.Validation rawValidation) {}
}
