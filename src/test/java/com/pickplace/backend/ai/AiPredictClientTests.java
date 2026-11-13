package com.pickplace.backend.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.pickplace.backend.ai.AiPredictionResult.Status;

class AiPredictClientTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BODY = """
            {"schema_version":"1.0","region_code":"11110515","region_code_system":"seoul-adstrd",
             "industry_code":"CS100001","source_kind":"official","as_of":"2026-02-15",
             "base_period":"2025Q4","model_version":null,
             "growth":{"status":"unavailable","reason":"model_not_found","estimated_yoy_growth":null,
               "target_period":"2026Q1","comparison_period":"2025Q1","model_version":null,"explanations":[]},
             "risk":{"status":"unavailable","reason":"missing_risk_indicators","score":null,"policy_status":"draft","components":[]},
             "fit":{"status":"unavailable","reason":"missing_fit_indicators","score":null,"policy_status":"draft","components":[]},
             "policy_version":"draft-v0.1","warnings":["draft_scoring_policy"]}
            """;
    private static AiPredictionRequest request() {
        var observation = new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100001", "2025Q4",
                LocalDate.of(2026, 2, 1), "seoul-sales-adstrd:as_published", BigDecimal.valueOf(100),
                4L, 0L, 1L, null, null, null, null);
        return new AiPredictionRequest("11110515", "seoul-adstrd", "CS100001", "2025Q4",
                LocalDate.of(2026, 2, 15), true, List.of(observation));
    }
    private static class Fake implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<String> key = new AtomicReference<>();
        final AtomicReference<String> request = new AtomicReference<>();
        Fake(int status, String body, long delay, String location, String contentType) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/predict", exchange -> {
                count.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try { if (delay > 0) Thread.sleep(delay); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                try {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
                    if (location != null) exchange.getResponseHeaders().set("Location", location);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) { /* client may have timed out */ }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        AiPredictClient client(long timeout) { return new AiPredictClient(JSON, true, url(), "test-key", 500, timeout); }
        @Override public void close() { server.stop(0); }
    }
    private static Fake server(int status, String body) throws IOException { return new Fake(status, body, 0, null, "application/json"); }

    @Test void defaultSpringBeanStartsWithoutAiSettings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.registerBean(JsonMapper.class, () -> JSON);
            context.register(AiPredictClient.class);
            context.refresh();
            assertThat(context.getBean(AiPredictClient.class).predict(request()).status()).isEqualTo(Status.DISABLED);
        }
    }
    @Test void disabledSpringBeanIgnoresInvalidOptionalConnectionSettings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-ai-settings", Map.of(
                    "PICKPLACE_AI_ENABLED", "false",
                    "PICKPLACE_AI_BASE_URL", "not-a-url",
                    "PICKPLACE_AI_API_KEY", "",
                    "PICKPLACE_AI_CONNECT_TIMEOUT_MS", "not-a-number",
                    "PICKPLACE_AI_REQUEST_TIMEOUT_MS", "also-not-a-number")));
            context.registerBean(JsonMapper.class, () -> JSON);
            context.register(AiPredictClient.class);
            context.refresh();
            assertThat(context.getBean(AiPredictClient.class).predict(request()).status()).isEqualTo(Status.DISABLED);
        }
    }
    @Test void enabledSpringBeanRejectsInvalidTimeout() {
        assertThatThrownBy(() -> new AiPredictClient(JSON, true, "http://127.0.0.1", "key", "bad", "1000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI timeout must be a number");
    }
    @Test void disabledIsSafeWithoutConfigurationOrNetwork() {
        var result = new AiPredictClient(JSON, false, "", "", 0, 0).predict(request());
        assertThat(result.status()).isEqualTo(Status.DISABLED);
        assertThat(result.response()).isNull();
    }
    @Test void successfulTransportPreservesUnavailableModelAndExactScope() throws Exception {
        try (var fake = server(200, BODY)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.AVAILABLE);
            assertThat(result.response().path("growth").path("reason").asText()).isEqualTo("model_not_found");
            assertThat(result.response().path("growth").path("estimated_yoy_growth").isNull()).isTrue();
            assertThat(result.response().path("risk").path("policy_status").asText()).isEqualTo("draft");
            assertThat(fake.key.get()).isEqualTo("test-key");
            var sent = JSON.readTree(fake.request.get());
            assertThat(sent.path("schema_version").asText()).isEqualTo("1.0");
            assertThat(sent.path("region_code").asText()).isEqualTo("11110515");
            assertThat(sent.path("industry_code").asText()).isEqualTo("CS100001");
            assertThat(sent.path("history").get(0).path("available_at").asText()).isEqualTo("2026-02-01");
            assertThat(sent.path("history").get(0).path("sales_unit").asText()).isEqualTo("KRW");
            assertThat(sent.path("history").get(0).path("opening_count").asInt()).isZero();
            assertThat(sent.path("history").get(0).path("resident_population").isNull()).isTrue();
            assertThat(sent.has("scopeAndSourceVerified")).isFalse();
        }
    }
    @Test void validActualPredictionRetainsUnitsAndDraftScoring() throws Exception {
        String available = BODY.replace("\"status\":\"unavailable\",\"reason\":\"model_not_found\",\"estimated_yoy_growth\":null",
                "\"status\":\"available\",\"reason\":null,\"estimated_yoy_growth\":0.15");
        try (var fake = server(200, available)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.AVAILABLE);
            assertThat(result.response().path("growth").path("estimated_yoy_growth").asDouble()).isEqualTo(0.15);
        }
    }
    @Test void availableDraftScoresAndComponentsSurviveSanitizing() throws Exception {
        String available = BODY
                .replace("\"status\":\"unavailable\",\"reason\":\"missing_risk_indicators\",\"score\":null,\"policy_status\":\"draft\",\"components\":[]",
                        "\"status\":\"available\",\"reason\":null,\"score\":25.5,\"policy_status\":\"draft\",\"components\":[{\"name\":\"sales_decline\",\"value\":-0.1,\"unit\":\"ratio\",\"contribution_points\":25.5}]")
                .replace("\"status\":\"unavailable\",\"reason\":\"missing_fit_indicators\",\"score\":null,\"policy_status\":\"draft\",\"components\":[]",
                        "\"status\":\"available\",\"reason\":null,\"score\":75.0,\"policy_status\":\"draft\",\"components\":[{\"name\":\"residents_per_store\",\"value\":300,\"unit\":\"persons\",\"contribution_points\":75}]");
        try (var fake = server(200, available)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.AVAILABLE);
            assertThat(result.response().path("risk").path("score").asDouble()).isEqualTo(25.5);
            assertThat(result.response().path("risk").path("components").get(0).path("unit").asText()).isEqualTo("ratio");
            assertThat(result.response().path("fit").path("score").asDouble()).isEqualTo(75.0);
            assertThat(result.response().path("fit").path("policy_status").asText()).isEqualTo("draft");
        }
    }
    @Test void httpFailuresAreDistinctAndBodyNeverReturned() throws Exception {
        for (int code : new int[]{401,403,422,503,504}) {
            try (var fake = server(code, "secret upstream payload")) {
                var result = fake.client(2000).predict(request());
                Status expected = switch (code) { case 401,403 -> Status.AUTH_FAILURE; case 422 -> Status.INVALID_AI_REQUEST;
                    case 504 -> Status.TIMEOUT; default -> Status.UPSTREAM_UNAVAILABLE; };
                assertThat(result.status()).isEqualTo(expected);
                assertThat(result.response()).isNull();
                assertThat(result.toString()).doesNotContain("secret upstream payload", "test-key");
            }
        }
    }
    @Test void redirectsAreNotFollowed() throws Exception {
        try (var target = server(200, BODY); var redirect = new Fake(302, "", 0, target.url() + "/predict", "application/json")) {
            var result = redirect.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.INVALID_RESPONSE);
            assertThat(target.count.get()).isZero();
        }
    }
    @Test void timeoutIsSeparatedFromOtherTransportFailures() throws Exception {
        try (var fake = new Fake(200, BODY, 800, null, "application/json")) {
            assertThat(fake.client(100).predict(request()).status()).isEqualTo(Status.TIMEOUT);
        }
    }
    @Test void timeoutIncludesSlowResponseBodyAfterHeaders() throws Exception {
        var fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/predict", exchange -> {
            try {
                byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body, 0, 8);
                exchange.getResponseBody().flush();
                try { Thread.sleep(800); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                exchange.getResponseBody().write(body, 8, body.length - 8);
            } catch (IOException ignored) { /* timeout cancels the connection */ }
            finally { exchange.close(); }
        });
        fake.start();
        try {
            var client = new AiPredictClient(JSON, true, "http://127.0.0.1:" + fake.getAddress().getPort(),
                    "test-key", 500, 150);
            long before = System.nanoTime();
            var result = client.predict(request());
            long elapsedMs = (System.nanoTime() - before) / 1_000_000;
            assertThat(result.status()).isEqualTo(Status.TIMEOUT);
            assertThat(elapsedMs).isLessThan(650);
        } finally { fake.stop(0); }
    }
    @Test void malformedAndWrongContentTypeResponsesAreRejected() throws Exception {
        try (var fake = server(200, "not-json")) {
            assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
        }
        try (var fake = new Fake(200, BODY, 0, null, "text/html")) {
            assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
        }
    }
    @Test void scopeAndPredictionPeriodMismatchAreRejected() throws Exception {
        for (String bad : List.of(BODY.replace("11110515", "11110516"), BODY.replace("2026Q1", "2026Q2"),
                BODY.replace("\"estimated_yoy_growth\":null", "\"estimated_yoy_growth\":1.0"))) {
            try (var fake = server(200, bad)) {
                assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
            }
        }
    }
    @Test void unexpectedDebugFieldsAreNotPassedDownstream() throws Exception {
        String contaminated = BODY.replace("\"schema_version\":\"1.0\"", "\"private_root\":\"/internal/secret\",\"schema_version\":\"1.0\"")
                .replace("\"growth\":{", "\"growth\":{\"debug_payload\":\"sensitive\",")
                .replace("\"risk\":{", "\"risk\":{\"private_feature\":\"sensitive\",");
        try (var fake = server(200, contaminated)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.AVAILABLE);
            assertThat(result.response().has("private_root")).isFalse();
            assertThat(result.response().path("growth").has("debug_payload")).isFalse();
            assertThat(result.response().path("risk").has("private_feature")).isFalse();
            assertThat(result.response().toString()).doesNotContain("sensitive", "/internal/secret");
        }
    }
    @Test void excessivelyLongOrControlCharacterWarningsAreRejected() throws Exception {
        for (String bad : List.of("x".repeat(301), "valid\\nsecond line")) {
            String body = BODY.replace("draft_scoring_policy", bad);
            try (var fake = server(200, body)) {
                assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
            }
        }
    }
    @Test void secretReflectedInAllowedResponseTextIsNeverReturned() throws Exception {
        String contaminated = BODY.replace("draft_scoring_policy", "prefix-test-key-suffix");
        try (var fake = server(200, contaminated)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.INVALID_RESPONSE);
            assertThat(result.response()).isNull();
            assertThat(result.toString()).doesNotContain("test-key");
        }
    }
    @Test void optionalExplanationDirectionIsValidatedAndPreserved() throws Exception {
        String explanation = "{\"feature\":\"sales_qoq\",\"method\":\"single_feature_reference_sensitivity\","
                + "\"observed_value\":1,\"reference_value\":0,\"delta_growth\":0.1,\"direction\":\"positive\"}";
        String withExplanation = BODY.replace("\"explanations\":[]", "\"explanations\":[" + explanation + "]");
        try (var fake = server(200, withExplanation)) {
            var result = fake.client(2000).predict(request());
            assertThat(result.status()).isEqualTo(Status.AVAILABLE);
            assertThat(result.response().path("growth").path("explanations").get(0).path("direction").asText()).isEqualTo("positive");
        }
        try (var fake = server(200, withExplanation.replace("positive", "unexpected"))) {
            assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
        }
    }
    @Test void oversizedResponseIsRejected() throws Exception {
        try (var fake = server(200, "x".repeat(1_000_001))) {
            assertThat(fake.client(2000).predict(request()).status()).isEqualTo(Status.INVALID_RESPONSE);
        }
    }
    @Test void invalidSettingsFailSafely() {
        assertThatThrownBy(() -> new AiPredictClient(JSON, true, "http://user:pass@example.org", "key", 500, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("pass");
        assertThatThrownBy(() -> new AiPredictClient(JSON, true, "http://127.0.0.1", "", 500, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unverifiedOrUnavailableHistoryIsRejectedBeforeTransport() {
        var req = request();
        assertThatThrownBy(() -> new AiPredictionRequest(req.regionCode(), req.regionCodeSystem(), req.industryCode(),
                req.basePeriod(), req.asOf(), false, req.history())).isInstanceOf(IllegalArgumentException.class);
        var future = new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100001", "2025Q4",
                LocalDate.of(2026, 3, 1), "source", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new AiPredictionRequest(req.regionCode(), req.regionCodeSystem(), req.industryCode(),
                req.basePeriod(), req.asOf(), true, List.of(future))).isInstanceOf(IllegalArgumentException.class);
    }
}
