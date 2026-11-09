package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// ZIP을 메모리에 담지 않고 임시 파일로 받는다. 본문 전송까지 시간·크기를 제한한다.
public final class NationalFileClient implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Duration timeout;
    private final int attempts;
    private final Duration retryDelay;

    public NationalFileClient() {
        this(Duration.ofMinutes(10), 3, Duration.ofSeconds(1));
    }

    NationalFileClient(Duration timeout, int attempts, Duration retryDelay) {
        this.timeout = timeout;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
    }

    public Response download(URI uri, Path target, long maxBytes) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Referer", "https://www.data.go.kr/").GET().build();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Files.deleteIfExists(target);
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), maxBytes));
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() == 200) {
                    long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (length >= 0 && Files.size(target) != length) throw new IOException("원본 전송 길이가 Content-Length와 다릅니다.");
                    return new Response(response.uri().toString(), response.headers().firstValue("Content-Type").orElse(null),
                            response.headers().firstValue("Content-Disposition").orElse(null));
                }
                boolean retryable = java.util.Set.of(408, 429, 500, 502, 503, 504).contains(response.statusCode());
                if (!retryable || attempt == attempts) throw new HttpStatusFailure(response.statusCode());
            } catch (TimeoutException exception) {
                pending.cancel(true);
                if (attempt == attempts) throw new HttpTimeoutException("원본 본문 수신 시간 초과");
            } catch (ExecutionException exception) {
                if (attempt == attempts) throw new IOException("원본 전송 실패", exception.getCause());
            } catch (InterruptedException exception) {
                pending.cancel(true);
                throw exception;
            }
            Thread.sleep(retryDelay.multipliedBy(attempt));
        }
        throw new IOException("원본 전송 실패");
    }

    private static final class HttpStatusFailure extends IOException {
        HttpStatusFailure(int status) { super("원본 다운로드 실패 (HTTP " + status + ")"); }
    }

    @Override
    public void close() { client.close(); }

    public record Response(String finalUrl, String contentType, String contentDisposition) {}
}
