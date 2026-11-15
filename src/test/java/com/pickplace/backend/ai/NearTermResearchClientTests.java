package com.pickplace.backend.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static com.pickplace.backend.ai.NearTermResearchResult.TransportStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 가상 자료만으로 연구 클라이언트의 요청·부분 응답·실패 경계를 확인한다. */
class NearTermResearchClientTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REGION = "11111111";
    private static final String INDUSTRY = "CS100001";
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 13);

    private static NearTermResearchRequest request() {
        var rows = new ArrayList<NearTermResearchRequest.Observation>();
        for (String period : List.of("2025Q2", "2025Q3", "2025Q4", "2026Q1", "2026Q2")) {
            rows.add(new NearTermResearchRequest.Observation(REGION, "seoul-adstrd", INDUSTRY,
                    period, LocalDate.of(2026, 11, 4), "official", "test-only-research-source",
                    new BigDecimal("1000000"), 10L, null, null, null, null, null, null));
        }
        return new NearTermResearchRequest(REGION, "seoul-adstrd", INDUSTRY,
                "2026Q2", "2026Q3", AS_OF, "official", rows);
    }

    private static String response(String status, String reason, String asOf, String snapshot) {
        var root = JSON.createObjectNode();
        root.put("mode", "experimental_research_only"); root.put("status", status);
        if (status.equals("available")) root.put("estimated_yoy_growth", 0.1);
        else root.putNull("estimated_yoy_growth");
        if (reason == null) root.putNull("reason"); else root.put("reason", reason);
        root.put("region_code", REGION); root.put("region_code_system", "seoul-adstrd");
        root.put("industry_code", INDUSTRY); root.put("base_period", "2026Q2");
        root.put("target_period", "2026Q3"); root.put("comparison_period", "2025Q3");
        root.put("estimate_kind", "completed_quarter_unobserved_estimate");
        root.put("as_of", asOf); root.put("generated_at", "2026-11-13T02:00:00+00:00");
        root.put("snapshot_available_at", snapshot); root.put("model_sha256", "a".repeat(64));
        root.put("model_created_at", "2026-11-13T01:00:00+00:00");
        root.put("scope_seen_in_training", true); root.put("industry_test_rows", 42);
        if (status.equals("available")) root.put("model_choice", "test-only-model");
        else root.putNull("model_choice");
        root.put("input_values_verified", false); root.put("region_scope_verified", false);
        root.putArray("warnings").add("research_only");
        if (status.equals("available")) {
            var test = root.putObject("retrospective_test");
            test.put("target_period", "2025Q3"); test.put("rows", 42);
            test.put("mae_pp", 12.5); test.put("median_absolute_error_pp", 7.5);
        } else root.putNull("retrospective_test");
        return root.toString();
    }

    private static final class Fake implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<String> request = new AtomicReference<>();
        final AtomicReference<String> key = new AtomicReference<>();
        final String apiKey = UUID.randomUUID().toString();
        Fake(int status, String body, long delay, String contentType) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/research/near-term/predict", exchange -> {
                count.incrementAndGet();
                key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try { if (delay > 0) Thread.sleep(delay); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                try {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) { /* 시간 초과 시 연결이 먼저 닫힐 수 있다. */ }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        NearTermResearchClient client(long timeout) {
            return new NearTermResearchClient(JSON, true, url(), apiKey, 500, timeout);
        }
        @Override public void close() { server.stop(0); }
    }

    @Test void disabledSpringBeanNeedsNoResearchSettings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("research-test", Map.of(
                    "PICKPLACE_NEAR_TERM_RESEARCH_ENABLED", "false",
                    "PICKPLACE_NEAR_TERM_RESEARCH_BASE_URL", "invalid-url",
                    "PICKPLACE_NEAR_TERM_RESEARCH_REQUEST_TIMEOUT_MS", "invalid")));
            context.registerBean(JsonMapper.class, () -> JSON);
            context.register(NearTermResearchClient.class);
            context.refresh();
            var result = context.getBean(NearTermResearchClient.class).predict(request());
            assertThat(result.transportStatus()).isEqualTo(TransportStatus.DISABLED);
            assertThat(result.response()).isNull();
        }
    }

    @Test void requestRejectsInventedCodeConversionAndIncompleteHistory() {
        var valid = request();
        assertThatThrownBy(() -> new NearTermResearchRequest("1111111100", "seoul-adstrd", INDUSTRY,
                valid.basePeriod(), valid.targetPeriod(), AS_OF, "official", valid.history()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearTermResearchRequest(REGION, "seoul-adstrd", "I21201",
                valid.basePeriod(), valid.targetPeriod(), AS_OF, "official", valid.history()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearTermResearchRequest(REGION, "seoul-adstrd", INDUSTRY,
                valid.basePeriod(), valid.targetPeriod(), AS_OF, "official", valid.history().subList(1, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearTermResearchRequest(REGION, "seoul-adstrd", INDUSTRY,
                "2026Q3", valid.targetPeriod(), AS_OF, "official", valid.history()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void availableResponseKeepsResearchLimitsAndDropsUnexpectedFields() throws Exception {
        String body = response("available", null, AS_OF.toString(), AS_OF.toString())
                .replace("\"mode\":", "\"private_debug\":\"secret\",\"mode\":")
                .replace("\"mae_pp\":12.5", "\"debug_metric\":5,\"mae_pp\":12.5");
        try (var fake = new Fake(200, body, 0, "application/json")) {
            var result = fake.client(2000).predict(request());
            assertThat(result.transportStatus()).isEqualTo(TransportStatus.RETURNED);
            assertThat(result.response().path("status").asText()).isEqualTo("available");
            assertThat(result.response().path("estimated_yoy_growth").asDouble()).isEqualTo(0.1);
            assertThat(result.response().path("retrospective_test").path("mae_pp").asDouble()).isEqualTo(12.5);
            assertThat(result.response().path("retrospective_test").path("median_absolute_error_pp").asDouble()).isEqualTo(7.5);
            assertThat(result.response().path("input_values_verified").asBoolean()).isFalse();
            assertThat(result.response().path("region_scope_verified").asBoolean()).isFalse();
            assertThat(result.response().has("private_debug")).isFalse();
            assertThat(result.response().path("retrospective_test").has("debug_metric")).isFalse();
            assertThat(fake.key.get()).isEqualTo(fake.apiKey);
            var outbound = JSON.readTree(fake.request.get());
            assertThat(outbound.path("target_period").asText()).isEqualTo("2026Q3");
            assertThat(outbound.path("source_kind").asText()).isEqualTo("official");
            assertThat(outbound.path("history").size()).isEqualTo(5);
            assertThat(outbound.has("scopeAndSourceVerified")).isFalse();
        }
    }

    @Test void unavailableReasonAndNullArePreservedEvenBeforeModelSnapshot() throws Exception {
        var earlier = request();
        earlier = new NearTermResearchRequest(earlier.regionCode(), earlier.regionCodeSystem(),
                earlier.industryCode(), earlier.basePeriod(), earlier.targetPeriod(),
                LocalDate.of(2026, 11, 4), earlier.sourceKind(), earlier.history());
        try (var fake = new Fake(200, response("unavailable", "as_of_before_model_snapshot",
                "2026-11-04", "2026-11-13"), 0, "application/json")) {
            var result = fake.client(2000).predict(earlier);
            assertThat(result.transportStatus()).isEqualTo(TransportStatus.RETURNED);
            assertThat(result.response().path("status").asText()).isEqualTo("unavailable");
            assertThat(result.response().path("estimated_yoy_growth").isNull()).isTrue();
            assertThat(result.response().path("reason").asText()).isEqualTo("as_of_before_model_snapshot");
            assertThat(result.response().path("warnings").size()).isEqualTo(1);
        }
    }

    @Test void mismatchedScopeOrFalseClaimsAreRejected() throws Exception {
        String valid = response("available", null, AS_OF.toString(), AS_OF.toString());
        for (String body : List.of(valid.replace(REGION, "11111112"),
                valid.replace("\"input_values_verified\":false", "\"input_values_verified\":true"),
                valid.replace("\"industry_test_rows\":42", "\"industry_test_rows\":9223372036854775808"),
                valid.replace("\"estimate_kind\":\"completed_quarter_unobserved_estimate\"",
                        "\"estimate_kind\":\"in_progress_or_future_quarter_estimate\""),
                valid.replace("\"estimated_yoy_growth\":0.1", "\"estimated_yoy_growth\":null"),
                valid.replace("\"mode\":\"experimental_research_only\"", "\"mode\":\"production\""))) {
            try (var fake = new Fake(200, body, 0, "application/json")) {
                assertThat(fake.client(2000).predict(request()).transportStatus())
                        .isEqualTo(TransportStatus.INVALID_RESPONSE);
            }
        }
    }

    @Test void httpFailuresDoNotExposeBodyOrKey() throws Exception {
        for (int code : new int[]{401, 422, 503, 504}) {
            try (var fake = new Fake(code, "upstream-private-content", 0, "application/json")) {
                var result = fake.client(2000).predict(request());
                TransportStatus expected = switch (code) {
                    case 401 -> TransportStatus.AUTH_FAILURE;
                    case 422 -> TransportStatus.INVALID_REQUEST;
                    case 504 -> TransportStatus.TIMEOUT;
                    default -> TransportStatus.UPSTREAM_UNAVAILABLE;
                };
                assertThat(result.transportStatus()).isEqualTo(expected);
                assertThat(result.response()).isNull();
                assertThat(result.toString()).doesNotContain("upstream-private-content", fake.apiKey);
            }
        }
    }

    @Test void timeoutAndInvalidContentTypeFailClosed() throws Exception {
        try (var fake = new Fake(200, response("available", null, AS_OF.toString(), AS_OF.toString()),
                700, "application/json")) {
            assertThat(fake.client(100).predict(request()).transportStatus()).isEqualTo(TransportStatus.TIMEOUT);
        }
        try (var fake = new Fake(200, response("available", null, AS_OF.toString(), AS_OF.toString()),
                0, "text/html")) {
            assertThat(fake.client(2000).predict(request()).transportStatus()).isEqualTo(TransportStatus.INVALID_RESPONSE);
        }
    }
}
