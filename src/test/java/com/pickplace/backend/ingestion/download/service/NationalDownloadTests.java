package com.pickplace.backend.ingestion.download.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NationalDownloadTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> HEADERS = List.of("행정기관코드", "기준연월", "원문");
    private static final String HEADER = "행정기관코드,기준연월,원문\r\n";
    private static final String ROW = "1111051500,2026-09-30,원문\r\n";
    @TempDir Path temporary;

    @Test
    void adoptsLocalOriginalWithoutChangingItsCollectionTimeAndReusesVerifiedFiles() throws Exception {
        var source = population(HEADER + ROW, StandardCharsets.UTF_8, "https://example.test/file");
        Path raw = temporary.resolve("original.csv"), meta = originalMetadata(source);
        Files.writeString(raw, HEADER + ROW);
        var service = new NationalDownloadService(JSON);
        var result = service.acquire(source, temporary.resolve("out"), raw, meta);
        assertThat(result.status()).isEqualTo("ADOPTED");
        var metadata = JSON.readTree(Files.readAllBytes(Path.of(result.metadataFile())));
        assertThat(metadata.path("collectedAt").asText()).isEqualTo("2026-11-08T12:25:07Z");
        assertThat(metadata.path("periodType").asText()).isEqualTo("MONTH");
        assertThat(metadata.path("encoding").asText()).isEqualTo("UTF-8");
        assertThat(metadata.path("rowNumbering").asText()).isEqualTo("DATA_RECORD_1_BASED_HEADER_EXCLUDED");
        assertThat(metadata.path("validation").path("members").get(0).path("columns").size()).isEqualTo(3);
        byte[] receipt = Files.readAllBytes(Path.of(result.metadataFile()));
        var modified = Files.getLastModifiedTime(Path.of(result.metadataFile()));
        assertThat(service.acquire(source, temporary.resolve("out"), raw, meta).status()).isEqualTo("REUSED");
        assertThat(Files.readAllBytes(Path.of(result.metadataFile()))).isEqualTo(receipt);
        assertThat(Files.getLastModifiedTime(Path.of(result.metadataFile()))).isEqualTo(modified);
        assertThat(Files.readString(raw)).isEqualTo(HEADER + ROW);
    }

    @Test
    void parsesMs949AndQuotedCommaNewlineWithoutLoadingAllRows() throws Exception {
        String csv = HEADER + "1111051500,2026-09-30,\"가게, 이름\n다음 줄\"\r\n";
        var source = population(csv, java.nio.charset.Charset.forName("MS949"), "https://example.test/file");
        Path raw = write(csv.getBytes(java.nio.charset.Charset.forName("MS949")));
        assertThat(new NationalRawValidator().validate(raw, source).rows()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "행정기관코드,기준연월,원문\r\n", "행정기관코드,기준연월,원문\r\n1111051500,2026-10-31,값\r\n",
            "행정기관코드,기준연월,원문\r\n1111051500,2026-09-30\r\n",
            "행정기관코드,기준연월,원문\r\n1111051500,2026-09-30,\"닫히지 않은 값",
            "<html>접근 오류</html>", "행정기관코드,기준연월,원문\r\n1111051500,2026-09-30,값\r\n1111051600,2026-09-30,값\r\n",
            "행정기관코드,기준연월,기준연월\r\n1111051500,2026-09-30,값\r\n"})
    void rejectsHtmlWrongMonthWidthsHeadersCountsAndMalformedCsv(String csv) throws Exception {
        var source = population(csv, StandardCharsets.UTF_8, "https://example.test/file");
        Path raw = write(csv.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new NationalRawValidator().validate(raw, source)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsInvalidUtf8RatherThanReplacingCharacters() throws Exception {
        byte[] bytes = (HEADER + ROW).getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length - 3] = (byte) 0xff;
        var source = source(bytes, "CSV", "UTF-8", HEADERS, List.of(), 1, "https://example.test/file");
        assertThatThrownBy(() -> new NationalRawValidator().validate(write(bytes), source)).isInstanceOf(IOException.class);
    }

    @Test
    void checksZipMembersHeaderRowsShaAndCrcAndDoesNotExtractThem() throws Exception {
        byte[] bytes = zip(List.of("a_202609.csv", "b_202609.csv", "readme.txt"));
        var source = source(bytes, "ZIP", "UTF-8", List.of("상가업소번호", "상호명"), List.of("a_202609.csv", "b_202609.csv"), 2, "https://example.test/file");
        var validation = new NationalRawValidator().validate(write(bytes), source);
        assertThat(validation.members()).hasSize(2);
        assertThat(validation.rows()).isEqualTo(2);
        assertThat(validation.ignoredMembers()).containsExactly("readme.txt");
        assertThat(validation.members().getFirst().sha256()).matches("[0-9a-f]{64}");
        assertThat(validation.members().getFirst().crc32()).matches("[0-9a-f]{8}");
        assertThat(Files.exists(temporary.resolve("a_202609.csv"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unexpected", "traversal", "crc"})
    void rejectsIncompleteOrCorruptedArchives(String scenario) throws Exception {
        List<String> names = switch (scenario) {
            case "missing" -> List.of("a_202609.csv");
            case "unexpected" -> List.of("a_202609.csv", "wrong_202610.csv");
            case "traversal" -> List.of("../a_202609.csv");
            default -> List.of("a_202609.csv", "b_202609.csv");
        };
        byte[] bytes = zip(names);
        if (scenario.equals("crc")) {
            for (int i = 0; i < bytes.length - 20; i++) {
                if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) { bytes[i + 16] ^= 1; break; }
            }
        }
        var source = source(bytes, "ZIP", "UTF-8", List.of("상가업소번호", "상호명"), List.of("a_202609.csv", "b_202609.csv"), 2, "https://example.test/file");
        assertThatThrownBy(() -> new NationalRawValidator().validate(write(bytes), source)).isInstanceOf(IOException.class);
    }

    @Test
    void wrongSourceMetadataOrRawChecksumLeavesNoCompletedOrPendingFiles() throws Exception {
        var source = population(HEADER + ROW, StandardCharsets.UTF_8, "https://example.test/file");
        Path raw = temporary.resolve("original.csv");
        Files.writeString(raw, HEADER + ROW.replace("1111051500", "1111051600"));
        Path meta = originalMetadata(source), out = temporary.resolve("out");
        assertThatThrownBy(() -> new NationalDownloadService(JSON).acquire(source, out, raw, meta)).isInstanceOf(IOException.class);
        try (var files = Files.walk(out)) { assertThat(files.filter(Files::isRegularFile).toList()).isEmpty(); }
        JSON.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), Map.of("url", "https://wrong.test", "status", 200));
        assertThatThrownBy(() -> new NationalDownloadService(JSON).acquire(source, out, raw, meta)).isInstanceOf(IOException.class);
    }

    @Test
    void tamperedCacheIsRejectedAndNeverOverwritten() throws Exception {
        var source = population(HEADER + ROW, StandardCharsets.UTF_8, "https://example.test/file");
        Path raw = temporary.resolve("original.csv"); Files.writeString(raw, HEADER + ROW);
        Path meta = originalMetadata(source), out = temporary.resolve("out");
        var service = new NationalDownloadService(JSON);
        var result = service.acquire(source, out, raw, meta);
        Files.writeString(Path.of(result.rawFile()), "tampered");
        assertThatThrownBy(() -> service.acquire(source, out, raw, meta)).isInstanceOf(IOException.class);
        assertThat(Files.readString(Path.of(result.rawFile()))).isEqualTo("tampered");
    }

    @Test
    void downloadsStreamingResponseAndThenReusesWithoutAnotherHttpRequest() throws Exception {
        byte[] body = (HEADER + ROW).getBytes(StandardCharsets.UTF_8);
        var count = new AtomicInteger();
        var server = server();
        server.createContext("/file", exchange -> {
            count.incrementAndGet(); exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/file";
            var source = population(HEADER + ROW, StandardCharsets.UTF_8, url);
            var service = new NationalDownloadService(JSON);
            var first = service.acquire(source, temporary.resolve("out"), null, null);
            assertThat(first.status()).isEqualTo("DOWNLOADED");
            assertThat(service.acquire(source, temporary.resolve("out"), null, null).status()).isEqualTo("REUSED");
            assertThat(count.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void retriesTransientStatusButNot404() throws Exception {
        var attempts = new AtomicInteger();
        var server = server();
        server.createContext("/retry", exchange -> {
            int status = attempts.incrementAndGet() == 1 ? 503 : 200;
            exchange.sendResponseHeaders(status, 2);
            try (var body = exchange.getResponseBody()) { body.write(new byte[] {1, 2}); }
        });
        server.createContext("/missing", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        try (var client = new NationalFileClient(Duration.ofSeconds(2), 2, Duration.ZERO)) {
            URI root = URI.create("http://localhost:" + server.getAddress().getPort());
            client.download(root.resolve("/retry"), temporary.resolve("retry.bin"), 100);
            assertThat(attempts.get()).isEqualTo(2);
            assertThatThrownBy(() -> client.download(root.resolve("/missing"), temporary.resolve("missing.bin"), 100))
                    .isInstanceOf(IOException.class).hasMessageContaining("404");
        } finally { server.stop(0); }
    }

    @Test
    void boundsResponseSizeAndEntireBodyTime() throws Exception {
        var server = server();
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200, 100); try (var out = exchange.getResponseBody()) { out.write(new byte[100]); } });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (var out = exchange.getResponseBody()) {
                out.write(1); out.flush();
                try { Thread.sleep(800); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                out.write(new byte[99]);
            } catch (IOException ignored) { }
        });
        server.start();
        try (var client = new NationalFileClient(Duration.ofMillis(200), 1, Duration.ZERO)) {
            URI root = URI.create("http://localhost:" + server.getAddress().getPort());
            assertThatThrownBy(() -> client.download(root.resolve("/large"), temporary.resolve("large.bin"), 2)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> client.download(root.resolve("/slow"), temporary.resolve("slow.bin"), 100)).isInstanceOf(IOException.class);
        } finally { server.stop(0); }
    }

    private HttpServer server() throws IOException { return HttpServer.create(new InetSocketAddress("localhost", 0), 0); }

    private Path originalMetadata(NationalDownloadSource source) throws IOException {
        Path meta = temporary.resolve("original.meta.json");
        JSON.writeValue(meta.toFile(), Map.of("url", source.downloadUrl(), "status", 200, "bytes", source.expectedBytes(),
                "sha256", source.sha256(), "downloadedAt", "2026-11-08T12:25:07Z", "contentType", "application/octet-stream"));
        return meta;
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(temporary, "source-", ".bin"); Files.write(file, bytes); return file;
    }

    private NationalDownloadSource population(String csv, java.nio.charset.Charset encoding, String url) throws IOException {
        return source(csv.getBytes(encoding), "CSV", encoding.name(), HEADERS, List.of(), 1, url);
    }

    private NationalDownloadSource source(byte[] bytes, String format, String encoding, List<String> headers, List<String> members, long rows, String url) throws IOException {
        String sha = NationalRawValidator.sha256(write(bytes));
        boolean population = format.equals("CSV");
        return new NationalDownloadSource(population ? "POPULATION_MONTH" : "STORE_SNAPSHOT", population ? "MOIS_RESIDENT" : "SEMAS_STORE",
                "test", population ? "MONTH" : "SNAPSHOT", "202609", "2026-09-30", "fixture", "https://example.test", url,
                format, encoding, bytes.length, sha, rows, headers, members);
    }

    private byte[] zip(List<String> members) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (String member : members) {
                zip.putNextEntry(new ZipEntry(member));
                zip.write((member.endsWith(".csv") ? "\uFEFF상가업소번호,상호명\r\n1,\"가게,이름\"\r\n" : "안내문").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
