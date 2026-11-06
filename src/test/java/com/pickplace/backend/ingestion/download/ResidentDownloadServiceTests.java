package com.pickplace.backend.ingestion.download;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;
import com.pickplace.backend.ingestion.download.service.ResidentDownloadService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidentDownloadServiceTests {

	private static final String PREVIEW =
			"{result:\"ok\",page:{totalCount:1},list:[{STDR_YYQU_CD:\"20262\"},]}";
	private static final String RAW =
			"{\"DESCRIPTION\":{},\"DATA\":[{\"stdr_yyqu_cd\":\"20262\",\"adstrd_cd\":\"11110515\"}]}\n";

	@TempDir
	Path temporaryDirectory;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void retriesTemporaryFailureAndSavesOriginalBytesWithMatchingMetadata() throws Exception {
		var downloadCalls = new AtomicInteger();
		var downloadForm = new AtomicReference<String>();
		HttpServer server = server(PREVIEW);
		server.createContext("/download", exchange -> {
			downloadForm.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
			if (downloadCalls.incrementAndGet() == 1) {
				respond(exchange, 503, "busy");
			} else {
				respond(exchange, 200, RAW);
			}
		});
		server.start();
		try {
			var service = new ResidentDownloadService(mapper);
			var result = service.download(plan(server));
			Path rawFile = Path.of(result.rawFile());
			var metadata = mapper.readTree(Files.readAllBytes(Path.of(result.metadataFile())));
			String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RAW.getBytes(UTF_8)));

			assertThat(downloadCalls.get()).isEqualTo(2);
			assertThat(downloadForm.get()).contains("gridTotalCnt=1");
			assertThat(Files.readAllBytes(rawFile)).isEqualTo(RAW.getBytes(UTF_8));
			assertThat(metadata.path("sha256").asString()).isEqualTo(expectedHash);
			assertThat(metadata.path("downloadRequest").path("url").asString()).endsWith("/download");
			assertThat(metadata.path("collectedAt").asString()).isNotBlank();
			assertThat(result.officialCount()).isEqualTo(result.rows()).isEqualTo(1);

			var second = service.download(plan(server));
			assertThat(second.rawFile()).isNotEqualTo(result.rawFile());
			assertThat(rawFile).exists();
		} finally {
			server.stop(0);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"<html>error</html>",
			"{\"DESCRIPTION\":{},\"DATA\":[]}",
			"{\"DESCRIPTION\":{},\"DATA\":[{\"stdr_yyqu_cd\":\"20261\",\"adstrd_cd\":\"11110515\"}]}",
			RAW + "{}"
	})
	void rejectsInvalidDownloadsWithoutPublishingFiles(String body) throws Exception {
		HttpServer server = server(PREVIEW);
		server.createContext("/download", exchange -> respond(exchange, 200, body));
		server.start();
		try {
			assertThatThrownBy(() -> new ResidentDownloadService(mapper).download(plan(server)))
					.isInstanceOfAny(IOException.class, JacksonException.class);
			assertThat(temporaryDirectory.resolve("raw")).doesNotExist();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void stopsBeforeDownloadWhenOfficialPreviewHasWrongQuarter() throws Exception {
		var downloadCalls = new AtomicInteger();
		HttpServer server = server(PREVIEW.replace("20262", "20261"));
		server.createContext("/download", exchange -> {
			downloadCalls.incrementAndGet();
			respond(exchange, 200, RAW);
		});
		server.start();
		try {
			assertThatThrownBy(() -> new ResidentDownloadService(mapper).download(plan(server)))
					.isInstanceOf(IOException.class).hasMessageContaining("기준 분기");
			assertThat(downloadCalls.get()).isZero();
			assertThat(temporaryDirectory.resolve("raw")).doesNotExist();
		} finally {
			server.stop(0);
		}
	}

	private HttpServer server(String preview) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/count", exchange -> respond(exchange, 200, preview));
		return server;
	}

	private DownloadPlan plan(HttpServer server) {
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		String form = "application/x-www-form-urlencoded";
		return new DownloadPlan("PLANNED", "PLAN_ONLY", "", "RESIDENT", "OA-22183", "20262",
				temporaryDirectory.resolve("raw").toString(), SourceCatalog.Strategy.SHEET_QUARTER_FILTER,
				1, null, null, new DownloadPlan.Request("POST", base + "/count", form, Map.of()),
				new DownloadPlan.Request("POST", base + "/download", form,
						Map.of("gridTotalCnt", "{officialQuarterCount}")), List.of("officialQuarterCount"), 0, 0);
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		try (exchange) {
			byte[] bytes = body.getBytes(UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/x-msdownload;charset=UTF-8");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}
}
