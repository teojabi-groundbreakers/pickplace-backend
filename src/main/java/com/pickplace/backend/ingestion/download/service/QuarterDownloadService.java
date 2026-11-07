package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

// 공식 건수와 원본을 대조한 뒤 한 묶음으로 보관한다.
public class QuarterDownloadService {

	private static final int MAX_ATTEMPTS = 3;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
	private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
	private static final Map<String, String> PRIMARY_FIELDS = Map.of(
			"RESIDENT", "tot_repop_co",
			"WORKER", "tot_wrc_popltn_co",
			"FACILITY", "viatr_fclty_co");
	private static final String METADATA_FILE = "metadata.json";

	private final JsonMapper mapper;
	private final JsonMapper previewMapper = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, JsonReadFeature.ALLOW_TRAILING_COMMA)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	public QuarterDownloadService(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public static boolean supports(String dataset) {
		return PRIMARY_FIELDS.containsKey(dataset);
	}

	public Result download(DownloadPlan plan) throws IOException, InterruptedException {
		if (!supports(plan.dataset()) || plan.strategy() != SourceCatalog.Strategy.SHEET_QUARTER_FILTER) {
			throw new IllegalArgumentException("분기별 다운로드는 RESIDENT·WORKER·FACILITY를 지원합니다.");
		}
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			var countResponse = post(client, "공식 총건수 조회", plan.countRequest());
			int officialCount = officialCount(countResponse.body(), plan.quarter());

			var template = plan.downloadRequestTemplate();
			var parameters = new LinkedHashMap<>(template.parameters());
			parameters.put("gridTotalCnt", Integer.toString(officialCount));
			var downloadRequest = new DownloadPlan.Request(
					template.method(), template.url(), template.contentType(), parameters);
			var response = post(client, plan.dataset() + " 원본 다운로드", downloadRequest);
			Instant collectedAt = Instant.now();
			byte[] raw = response.body();
			int rows = validateRows(raw, plan.quarter(), officialCount, PRIMARY_FIELDS.get(plan.dataset()));
			return save(plan, downloadRequest, collectedAt, raw, officialCount, rows);
		}
	}

	private int officialCount(byte[] body, String quarter) throws IOException {
		// 서울시 미리보기 응답의 따옴표 없는 키와 마지막 쉼표만 허용한다.
		JsonNode preview = previewMapper.readTree(body);
		JsonNode count = preview.path("page").path("totalCount");
		JsonNode sample = preview.path("list");
		if (!"ok".equals(preview.path("result").asString()) || !count.isIntegralNumber()
				|| !count.canConvertToInt() || count.asInt() <= 0 || !sample.isArray() || sample.isEmpty()) {
			throw new IOException("공식 총건수 응답의 형식 또는 건수가 올바르지 않습니다.");
		}
		for (JsonNode row : sample) {
			if (!quarter.equals(row.path("STDR_YYQU_CD").asString())) {
				throw new IOException("공식 총건수 응답의 기준 분기가 요청과 다릅니다.");
			}
		}
		return count.asInt();
	}

	private int validateRows(byte[] raw, String quarter, int officialCount, String primaryField) throws IOException {
		JsonNode document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
		JsonNode rows = document.path("DATA");
		if (!document.path("DESCRIPTION").isObject() || !rows.isArray()) {
			throw new IOException("원본에 DESCRIPTION 객체 또는 DATA 배열이 없습니다.");
		}
		if (rows.size() != officialCount) {
			throw new IOException("공식 총건수와 원본 행 수가 다릅니다. 공식 "
					+ officialCount + "행, 원본 " + rows.size() + "행");
		}
		var dongs = new HashSet<String>();
		for (JsonNode row : rows) {
			// 데이터 종류를 식별하는 필드를 확인하고 숫자 정제는 후속 단계에서 처리한다.
			if (!row.has(primaryField)) {
				throw new IOException("원본에 해당 데이터의 필수 필드가 없습니다: " + primaryField);
			}
			String dong = row.path("adstrd_cd").asString();
			if (!quarter.equals(row.path("stdr_yyqu_cd").asString())
					|| dong == null || !dong.matches("[0-9]{8}") || !dongs.add(dong)) {
				throw new IOException("원본의 기준 분기 또는 행정동 코드가 올바르지 않거나 중복됩니다.");
			}
		}
		return rows.size();
	}

	private HttpResponse<byte[]> post(HttpClient client, String label, DownloadPlan.Request request)
			throws IOException, InterruptedException {
		String form = request.parameters().entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), UTF_8) + "="
						+ URLEncoder.encode(entry.getValue(), UTF_8))
				.collect(Collectors.joining("&"));
		var httpRequest = HttpRequest.newBuilder(URI.create(request.url()))
				.header("Content-Type", request.contentType())
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();

		for (int attempt = 1; ; attempt++) {
			HttpResponse<byte[]> response;
			try {
				response = send(client, httpRequest);
			} catch (IOException exception) {
				if (attempt == MAX_ATTEMPTS) {
					throw new IOException(label + " 연결 또는 응답 읽기 실패: " + exception.getMessage(), exception);
				}
				Thread.sleep(Duration.ofSeconds(attempt));
				continue;
			}
			int status = response.statusCode();
			if (status == 200) {
				return response;
			}
			if (attempt == MAX_ATTEMPTS || !retryable(status)) {
				throw new IOException(label + " 실패 (HTTP " + status + ")");
			}
			Thread.sleep(Duration.ofSeconds(attempt));
		}
	}

	private boolean retryable(int status) {
		return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
	}

	private HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
		var pending = client.sendAsync(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
		try {
			return pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException exception) {
			pending.cancel(true);
			throw new HttpTimeoutException("응답 본문 포함 " + REQUEST_TIMEOUT.toSeconds() + "초 초과");
		} catch (InterruptedException exception) {
			pending.cancel(true);
			throw exception;
		} catch (ExecutionException exception) {
			throw new IOException(exception.getCause().getMessage(), exception.getCause());
		}
	}

	private Result save(DownloadPlan plan, DownloadPlan.Request downloadRequest, Instant collectedAt,
			byte[] raw, int officialCount, int rows) throws IOException {
		String checksum = sha256(raw);
		String directory = plan.dataset().toLowerCase(Locale.ROOT);
		String rawFile = directory + ".json";
		var metadata = new Metadata(plan.dataset(), plan.sourceId(), plan.quarter(), collectedAt.toString(),
				rawFile, officialCount, rows, raw.length, checksum, plan.countRequest(), downloadRequest);
		Path parent = Files.createDirectories(Path.of(plan.outputDirectory()).resolve(directory).resolve(plan.quarter()));
		Path destination = parent.resolve(UUID.randomUUID().toString());
		Path staging = Files.createTempDirectory(parent, ".pending-");
		try {
			Files.write(staging.resolve(rawFile), raw);
			Files.write(staging.resolve(METADATA_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
			// 두 파일을 모두 저장한 뒤 수집 폴더를 한 번에 확정한다.
			Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException exception) {
			try {
				Files.deleteIfExists(staging.resolve(METADATA_FILE));
				Files.deleteIfExists(staging.resolve(rawFile));
				Files.deleteIfExists(staging);
			} catch (IOException cleanupError) {
				exception.addSuppressed(cleanupError);
			}
			throw exception;
		}
		return new Result("DOWNLOADED", destination.resolve(rawFile).toString(),
				destination.resolve(METADATA_FILE).toString(), officialCount, rows, checksum);
	}

	private String sha256(byte[] raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private record Metadata(String dataset, String sourceId, String quarter, String collectedAt, String rawFile,
			int officialCount, int rows, int bytes, String sha256,
			DownloadPlan.Request countRequest, DownloadPlan.Request downloadRequest) {}

	public record Result(String status, String rawFile, String metadataFile, int officialCount, int rows, String sha256) {}
}
