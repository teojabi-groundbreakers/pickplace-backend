package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

// 두 수집기에서 같은 제한과 재시도 정책으로 요청한다.
final class PublicDataHttpClient implements AutoCloseable {

	private static final int MAX_ATTEMPTS = 3;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
	private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper previewMapper = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, JsonReadFeature.ALLOW_TRAILING_COMMA)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	int officialCount(byte[] body, String quarter) throws IOException {
		// 서울시 미리보기 응답은 일반 JSON과 구분해서 읽는다.
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

	byte[] post(String label, DownloadPlan.Request request) throws IOException, InterruptedException {
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
				response = send(httpRequest);
			} catch (IOException exception) {
				if (attempt == MAX_ATTEMPTS) {
					throw new IOException(label + " 연결 또는 응답 읽기 실패: " + exception.getMessage(), exception);
				}
				Thread.sleep(Duration.ofSeconds(attempt));
				continue;
			}
			int status = response.statusCode();
			if (status == 200) {
				return response.body();
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

	private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
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

	@Override
	public void close() {
		client.close();
	}
}
