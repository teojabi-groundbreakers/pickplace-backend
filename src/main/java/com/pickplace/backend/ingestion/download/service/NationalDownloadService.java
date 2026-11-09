package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;

import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 원본과 수집 메타데이터를 모두 검증한 뒤 폴더 하나로 확정한다. DB에는 접근하지 않는다.
public final class NationalDownloadService {
    private final JsonMapper mapper;
    private final NationalRawValidator validator = new NationalRawValidator();

    public NationalDownloadService(JsonMapper mapper) { this.mapper = mapper; }

    public Result acquire(NationalDownloadSource source, Path output, Path sourceFile, Path sourceMetadata)
            throws IOException, InterruptedException {
        source.validate();
        Path parent = output.toAbsolutePath().normalize().resolve("national")
                .resolve(source.dataset().toLowerCase(Locale.ROOT)).resolve(source.periodKey());
        Path destination = parent.resolve(source.sha256());
        String rawName = source.format().equals("ZIP") ? "raw.zip" : "raw.csv";
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return existing(source, destination, rawName);
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".pending-");
        Path raw = staging.resolve(rawName);
        try {
            String acquisition;
            String finalUrl;
            String collectedAt;
            String contentType;
            String contentDisposition;
            String upstreamMetadataSha256 = null;
            if (sourceFile != null) {
                if (sourceMetadata == null || Files.size(sourceMetadata) > 1024 * 1024 || Files.size(sourceFile) != source.expectedBytes()) {
                    throw new IOException("재사용 원본 또는 수집 메타데이터 크기 오류");
                }
                byte[] previousBytes = Files.readAllBytes(sourceMetadata);
                JsonNode previous = mapper.readTree(previousBytes);
                if (!source.downloadUrl().equals(previous.path("url").asText()) || previous.path("status").asInt() != 200
                        || previous.path("bytes").asLong() != source.expectedBytes()
                        || !source.sha256().equals(previous.path("sha256").asText())) {
                    throw new IOException("재사용 원본의 수집 메타데이터가 검증된 출처·크기·해시와 다릅니다.");
                }
                collectedAt = previous.path("downloadedAt").asText();
                try { Instant.parse(collectedAt); }
                catch (RuntimeException exception) { throw new IOException("원래 수집 시각이 없습니다.", exception); }
                copySource(sourceFile, raw, source.expectedBytes());
                Files.write(staging.resolve("upstream-metadata.json"), previousBytes);
                upstreamMetadataSha256 = NationalRawValidator.sha256(staging.resolve("upstream-metadata.json"));
                acquisition = "ADOPTED";
                finalUrl = source.downloadUrl();
                contentType = previous.path("contentType").asText(null);
                contentDisposition = previous.path("contentDisposition").asText(null);
            } else {
                try (var client = new NationalFileClient()) {
                    var response = client.download(URI.create(source.downloadUrl()), raw, source.expectedBytes());
                    finalUrl = response.finalUrl(); contentType = response.contentType(); contentDisposition = response.contentDisposition();
                }
                acquisition = "DOWNLOADED";
                collectedAt = Instant.now().toString();
            }
            var validation = validator.validate(raw, source);
            var metadata = new Receipt(1, source.dataset(), source.sourceSystem(), source.sourceId(), source.periodType(),
                    source.periodKey(), source.referenceOn(), source.sourceLabel(), source.sourcePage(), source.downloadUrl(),
                    finalUrl, 200, contentType, contentDisposition, collectedAt, Instant.now().toString(), acquisition, rawName,
                    source.format(), source.encoding(), "DATA_RECORD_1_BASED_HEADER_EXCLUDED",
                    source.expectedBytes(), source.sha256(), upstreamMetadataSha256, validation);
            Files.write(staging.resolve("metadata.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            return new Result(acquisition, destination.resolve(rawName).toString(), destination.resolve("metadata.json").toString(),
                    source.sha256(), source.expectedBytes(), validation.rows(), validation.members().size());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            try {
                for (String name : new String[] {rawName, "metadata.json", "upstream-metadata.json"}) Files.deleteIfExists(staging.resolve(name));
                Files.deleteIfExists(staging);
            } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    private void copySource(Path source, Path target, long limit) throws IOException {
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[1024 * 1024];
            long bytes = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count;
                if (bytes > limit || Thread.currentThread().isInterrupted()) throw new IOException("재사용 원본 크기 초과 또는 중단");
                output.write(buffer, 0, count);
            }
        }
    }

    private Result existing(NationalDownloadSource source, Path directory, String rawName) throws IOException {
        Path raw = directory.resolve(rawName), metadataFile = directory.resolve("metadata.json");
        if (Files.isSymbolicLink(directory) || !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS) || Files.size(metadataFile) > 1024 * 1024) throw new IOException("기존 수집 묶음이 불완전합니다. 자동 덮어쓰지 않습니다.");
        var receipt = mapper.readValue(Files.readAllBytes(metadataFile), Receipt.class);
        var validation = validator.validate(raw, source);
        if (receipt.schemaVersion() != 1 || !source.dataset().equals(receipt.dataset())
                || !source.sourceSystem().equals(receipt.sourceSystem()) || !source.sourceId().equals(receipt.sourceId())
                || !source.periodType().equals(receipt.periodType()) || !source.periodKey().equals(receipt.periodKey())
                || !source.referenceOn().equals(receipt.sourceReferenceOn()) || !source.sourceLabel().equals(receipt.sourceLabel())
                || !source.sourcePage().equals(receipt.sourcePage()) || !source.downloadUrl().equals(receipt.requestedUrl())
                || receipt.httpStatus() != 200 || receipt.bytes() != source.expectedBytes() || !source.sha256().equals(receipt.sha256())
                || !source.format().equals(receipt.format()) || !source.encoding().equals(receipt.encoding())
                || !"DATA_RECORD_1_BASED_HEADER_EXCLUDED".equals(receipt.rowNumbering())
                || !rawName.equals(receipt.rawFile()) || !validation.equals(receipt.validation())
                || !("ADOPTED".equals(receipt.acquisition()) || "DOWNLOADED".equals(receipt.acquisition()))) {
            throw new IOException("기존 수집 메타데이터가 검증 결과와 다릅니다. 자동 덮어쓰지 않습니다.");
        }
        try {
            if (Instant.parse(receipt.storedAt()).isBefore(Instant.parse(receipt.collectedAt()))) throw new IllegalArgumentException("Invalid storage time");
        }
        catch (RuntimeException exception) { throw new IOException("기존 수집 시각 오류", exception); }
        if (receipt.acquisition().equals("ADOPTED") && (!Files.isRegularFile(directory.resolve("upstream-metadata.json"), LinkOption.NOFOLLOW_LINKS)
                || !NationalRawValidator.sha256(directory.resolve("upstream-metadata.json")).equals(receipt.upstreamMetadataSha256()))) {
            throw new IOException("보존한 원래 수집 메타데이터가 변경되었습니다.");
        }
        return new Result("REUSED", raw.toString(), metadataFile.toString(), source.sha256(), source.expectedBytes(), validation.rows(), validation.members().size());
    }

    public record Result(String status, String rawFile, String metadataFile, String sha256, long bytes, long rows, int csvFiles) {}
    public record Receipt(int schemaVersion, String dataset, String sourceSystem, String sourceId, String periodType,
            String periodKey, String sourceReferenceOn, String sourceLabel, String sourcePage, String requestedUrl,
            String finalUrl, int httpStatus, String contentType, String contentDisposition, String collectedAt, String storedAt,
            String acquisition, String rawFile, String format, String encoding, String rowNumbering,
            long bytes, String sha256, String upstreamMetadataSha256,
            NationalRawValidator.Validation validation) {}
}
