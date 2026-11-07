package com.pickplace.backend.ingestion.download;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;
import com.pickplace.backend.ingestion.download.service.SourceCatalogLoader;
import com.pickplace.backend.ingestion.download.service.StoreDownloadService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreDownloadServiceTests {

	private static final List<String> TWO_CODES = List.of("CS100001", "CS100002");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final PrintStream progress = new PrintStream(OutputStream.nullOutputStream());

	@TempDir
	Path temporaryDirectory;

	@Test
	void collectsAllSourceIndustriesAndSeparatesTargetQuarterFromOriginals() throws Exception {
		var loader = new SourceCatalogLoader(mapper);
		List<String> codes = loader.loadIndustryCodes(loader.load());
		try (var fixture = new Fixture(codes.size(), this::raw)) {
			var result = download(fixture, codes);
			Path directory = Path.of(result.metadataFile()).getParent();
			JsonNode metadata = mapper.readTree(Files.readAllBytes(Path.of(result.metadataFile())));
			JsonNode target = mapper.readTree(Files.readAllBytes(Path.of(result.targetFile())));

			assertThat(codes).hasSize(100);
			assertThat(fixture.downloadedCodes).containsExactlyElementsOf(codes);
			assertThat(result.partitionCount()).isEqualTo(100);
			assertThat(result.rows()).isEqualTo(result.officialCount()).isEqualTo(100);
			assertThat(target.path("DATA").size()).isEqualTo(100);
			assertThat(target.path("DATA").valueStream()
					.map(row -> row.path("stdr_yyqu_cd").asString()).distinct()).containsExactly("20262");
			assertThat(metadata.path("status").asString()).isEqualTo("DOWNLOADED");
			assertThat(metadata.path("completedPartitions").asInt()).isEqualTo(100);
			assertThat(metadata.path("partitions").size()).isEqualTo(100);
			for (JsonNode partition : metadata.path("partitions")) {
				String code = partition.path("businessTypeCode").asString();
				Path rawFile = directory.resolve(partition.path("rawFile").asString());
				assertThat(Files.readString(rawFile, UTF_8)).isEqualTo(raw(code));
				assertThat(partition.path("sha256").asString()).isEqualTo(hash(Files.readAllBytes(rawFile)));
				assertThat(partition.path("rows").asInt()).isEqualTo(2);
				assertThat(partition.path("targetRows").asInt()).isEqualTo(1);
			}
			assertThat(result.sha256()).isEqualTo(hash(Files.readAllBytes(Path.of(result.targetFile()))));
			assertThat(directory.resolve("official-count.txt")).exists();
		}
	}

	@Test
	void rejectsIncompleteCollectionEvenWhenEveryRequestSucceeded() throws Exception {
		try (var fixture = new Fixture(3, this::raw)) {
			assertThatThrownBy(() -> download(fixture, TWO_CODES))
					.isInstanceOf(IOException.class).hasMessageContaining("공식 총건수");
			Path failed = failedDirectory();
			assertThat(readMetadata(failed).path("status").asString()).isEqualTo("FAILED");
			assertThat(failed.resolve("store-target.json")).doesNotExist();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"duplicate", "wrong-industry", "invalid-quarter", "invalid-dong",
			"missing-metric", "missing-target", "html", "trailing-document"})
	void recordsBadPartitionAsFailureAndKeepsTheResponse(String problem) throws Exception {
		try (var fixture = new Fixture(2, code -> invalidRaw(code, problem))) {
			assertThatThrownBy(() -> download(fixture, TWO_CODES)).isInstanceOf(IOException.class);
			Path failed = failedDirectory();
			JsonNode metadata = readMetadata(failed);
			assertThat(metadata.path("status").asString()).isEqualTo("FAILED");
			assertThat(failed.resolve(TWO_CODES.getFirst() + ".json")).exists();
			assertThat(failed.resolve("store-target.json")).doesNotExist();
		}
	}

	@Test
	void failedRunAndFreshRetryPreserveThePreviousCompleteRun() throws Exception {
		try (var fixture = new Fixture(2, this::raw)) {
			var first = download(fixture, TWO_CODES);
			fixture.failureCode = TWO_CODES.getLast();
			assertThatThrownBy(() -> download(fixture, TWO_CODES)).isInstanceOf(IOException.class);
			JsonNode failure = readMetadata(failedDirectory());
			assertThat(failure.path("failedPartition").asString()).isEqualTo(TWO_CODES.getLast());
			assertThat(failure.path("completedPartitions").asInt()).isEqualTo(1);
			assertThat(failure.path("partitions").size()).isEqualTo(1);

			fixture.failureCode = null;
			var retried = download(fixture, TWO_CODES);
			assertThat(retried.metadataFile()).isNotEqualTo(first.metadataFile());
			assertThat(Path.of(first.targetFile())).exists();
			assertThat(readMetadata(Path.of(first.metadataFile()).getParent()).path("status").asString())
					.isEqualTo("DOWNLOADED");
			assertThat(fixture.downloadedCodes).containsExactly(
					"CS100001", "CS100002", "CS100001", "CS100002", "CS100001", "CS100002");
		}
	}

	@Test
	void rejectsMismatchedIndustryListBeforeCreatingRunDirectory() throws Exception {
		try (var fixture = new Fixture(2, this::raw)) {
			DownloadPlan plan = fixture.plan(2);
			assertThatThrownBy(() -> new StoreDownloadService(mapper).download(
					plan, List.of("CS100001", "CS100001"), progress))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(temporaryDirectory.resolve("raw")).doesNotExist();
		}
	}

	private StoreDownloadService.Result download(Fixture fixture, List<String> codes) throws Exception {
		return new StoreDownloadService(mapper).download(fixture.plan(codes.size()), codes, progress);
	}

	private JsonNode readMetadata(Path directory) throws IOException {
		return mapper.readTree(Files.readAllBytes(directory.resolve("metadata.json")));
	}

	private Path failedDirectory() throws IOException {
		try (var paths = Files.list(temporaryDirectory.resolve("raw/store/20262"))) {
			List<Path> failed = paths.filter(path -> path.getFileName().toString().startsWith("failed-")).toList();
			assertThat(failed).hasSize(1);
			return failed.getFirst();
		}
	}

	private String row(String code, String quarter) {
		return "{\"stdr_yyqu_cd\":\"" + quarter + "\",\"adstrd_cd\":\"11110515\",\"svc_induty_cd\":\""
				+ code + "\",\"similr_induty_stor_co\":\"10\"}";
	}

	private String raw(String code) {
		return "{\"DESCRIPTION\":{},\"DATA\":[" + row(code, "20262") + "," + row(code, "20261") + "]}";
	}

	private String invalidRaw(String code, String problem) {
		return switch (problem) {
			case "duplicate" -> "{\"DESCRIPTION\":{},\"DATA\":[" + row(code, "20262") + "," + row(code, "20262") + "]}";
			case "wrong-industry" -> raw("CS999999");
			case "invalid-quarter" -> raw(code).replace("20262", "20265");
			case "invalid-dong" -> raw(code).replace("11110515", "abc");
			case "missing-metric" -> raw(code).replace("similr_induty_stor_co", "wrong_field");
			case "missing-target" -> raw(code).replace("20262", "20254");
			case "html" -> "<html>error</html>";
			case "trailing-document" -> raw(code) + "{}";
			default -> throw new IllegalArgumentException(problem);
		};
	}

	private String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private class Fixture implements AutoCloseable {
		private final HttpServer server;
		private final List<String> downloadedCodes = new CopyOnWriteArrayList<>();
		private volatile String failureCode;

		Fixture(int count, Function<String, String> response) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/count", exchange -> respond(exchange, 200,
					"{result:\"ok\",page:{totalCount:" + count + "},list:[{STDR_YYQU_CD:\"20262\"},]}"));
			server.createContext("/download", exchange -> {
				String form = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
				String code = Arrays.stream(form.split("&")).filter(value -> value.startsWith("txtFilter="))
						.map(value -> URLDecoder.decode(value.substring("txtFilter=".length()), UTF_8))
						.findFirst().orElseThrow();
				downloadedCodes.add(code);
				respond(exchange, code.equals(failureCode) ? 400 : 200, response.apply(code));
			});
			server.start();
		}

		DownloadPlan plan(int partitions) {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			String form = "application/x-www-form-urlencoded";
			return new DownloadPlan("PLANNED", "PLAN_ONLY", "", "STORE", "OA-22172", "20262",
					temporaryDirectory.resolve("raw").toString(), SourceCatalog.Strategy.SHEET_INDUSTRY_PARTITION,
					partitions, "CS100001", null,
					new DownloadPlan.Request("POST", base + "/count", form, Map.of("txtFilter", "20262")),
					new DownloadPlan.Request("POST", base + "/download", form,
							Map.of("txtFilter", "{businessTypeCode}")), List.of("businessTypeCode"), 0, 0);
		}

		private void respond(HttpExchange exchange, int status, String body) throws IOException {
			try (exchange) {
				byte[] bytes = body.getBytes(UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
