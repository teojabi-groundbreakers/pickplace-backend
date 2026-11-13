package com.pickplace.backend.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.pickplace.backend.ai.NearTermResearchResult.TransportStatus;

/**
 * 서울 2026Q2 단기 모델의 별도 연구 API만 호출한다.
 * 현재 공개 분석 API나 기본 /predict 경로와 연결되지 않으며 결과를 운영 판단으로 승격하지 않는다.
 */
@Component
public final class NearTermResearchClient {
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final JsonMapper mapper;
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient http;

    public NearTermResearchClient(JsonMapper mapper, boolean enabled, String baseUrl, String apiKey,
            long connectMs, long requestMs) {
        this(mapper, enabled, baseUrl, apiKey, Long.toString(connectMs), Long.toString(requestMs));
    }

    @Autowired
    public NearTermResearchClient(JsonMapper mapper,
            @Value("${PICKPLACE_NEAR_TERM_RESEARCH_ENABLED:false}") boolean enabled,
            @Value("${PICKPLACE_NEAR_TERM_RESEARCH_BASE_URL:}") String baseUrl,
            @Value("${PICKPLACE_NEAR_TERM_RESEARCH_API_KEY:}") String apiKey,
            @Value("${PICKPLACE_NEAR_TERM_RESEARCH_CONNECT_TIMEOUT_MS:1000}") String connectTimeout,
            @Value("${PICKPLACE_NEAR_TERM_RESEARCH_REQUEST_TIMEOUT_MS:5000}") String requestTimeout) {
        this.mapper = mapper;
        this.enabled = enabled;
        if (!enabled) {
            this.endpoint = null; this.apiKey = null; this.requestTimeout = null; this.http = null;
            return;
        }
        long connectMs, requestMs;
        try {
            connectMs = Long.parseLong(connectTimeout);
            requestMs = Long.parseLong(requestTimeout);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Research AI timeout must be numeric");
        }
        if (apiKey == null || apiKey.isBlank() || !apiKey.chars().allMatch(c -> c >= 33 && c <= 126))
            throw new IllegalArgumentException("Research AI key must be printable ASCII");
        if (connectMs < 1 || connectMs > 60_000 || requestMs < 1 || requestMs > 120_000)
            throw new IllegalArgumentException("Research AI timeout outside bounds");
        try {
            URI base = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/")))
                throw new IllegalArgumentException("Invalid research AI endpoint");
            this.endpoint = base.resolve("/research/near-term/predict");
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid research AI endpoint");
        }
        this.apiKey = apiKey;
        this.requestTimeout = Duration.ofMillis(requestMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public NearTermResearchResult predict(NearTermResearchRequest request) {
        if (!enabled) return fail(TransportStatus.DISABLED, "near_term_research_disabled");
        if (request == null) throw new IllegalArgumentException("research request required");
        HttpRequest outbound;
        try {
            outbound = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload(request))))
                    .build();
        } catch (RuntimeException failure) {
            return fail(TransportStatus.INVALID_REQUEST, "near_term_request_invalid");
        }
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = http.sendAsync(outbound, ignored -> new LimitedBody(MAX_RESPONSE_BYTES));
        } catch (RuntimeException failure) {
            return fail(TransportStatus.UPSTREAM_UNAVAILABLE, "near_term_transport_failure");
        }
        try {
            HttpResponse<byte[]> response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            int code = response.statusCode();
            if (code == 401 || code == 403) return fail(TransportStatus.AUTH_FAILURE, "near_term_authentication_failed");
            if (code == 400 || code == 422) return fail(TransportStatus.INVALID_REQUEST, "near_term_request_rejected");
            if (code == 408 || code == 504) return fail(TransportStatus.TIMEOUT, "near_term_timeout");
            if (code >= 500) return fail(TransportStatus.UPSTREAM_UNAVAILABLE, "near_term_server_unavailable");
            if (code != 200) return fail(TransportStatus.INVALID_RESPONSE, "near_term_unexpected_status");
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!"application/json".equalsIgnoreCase(contentType))
                return fail(TransportStatus.INVALID_RESPONSE, "near_term_response_content_type");
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            JsonNode tree = mapper.readTree(raw);
            if (!validResponse(tree, request)) return fail(TransportStatus.INVALID_RESPONSE, "near_term_response_invalid");
            ObjectNode clean = safeResponse(tree);
            if (containsSecret(clean, apiKey)) return fail(TransportStatus.INVALID_RESPONSE, "near_term_response_invalid");
            return new NearTermResearchResult(TransportStatus.RETURNED, clean, null);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            return fail(TransportStatus.TIMEOUT, "near_term_timeout");
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return fail(TransportStatus.UPSTREAM_UNAVAILABLE, "near_term_request_interrupted");
        } catch (ExecutionException failure) {
            if (causedByTimeout(failure)) return fail(TransportStatus.TIMEOUT, "near_term_timeout");
            if (causedByLimit(failure)) return fail(TransportStatus.INVALID_RESPONSE, "near_term_response_too_large");
            return fail(TransportStatus.UPSTREAM_UNAVAILABLE, "near_term_transport_failure");
        } catch (IOException | RuntimeException failure) {
            return fail(TransportStatus.INVALID_RESPONSE, "near_term_response_invalid");
        }
    }

    private ObjectNode payload(NearTermResearchRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", "1.0"); node.put("region_code", request.regionCode());
        node.put("region_code_system", request.regionCodeSystem()); node.put("industry_code", request.industryCode());
        node.put("base_period", request.basePeriod()); node.put("target_period", request.targetPeriod());
        node.put("as_of", request.asOf().toString()); node.put("source_kind", request.sourceKind());
        ArrayNode history = node.putArray("history");
        for (NearTermResearchRequest.Observation row : request.history()) {
            ObjectNode item = history.addObject();
            item.put("region_code", row.regionCode()); item.put("region_code_system", row.regionCodeSystem());
            item.put("industry_code", row.industryCode()); item.put("period", row.period());
            item.put("available_at", row.availableAt().toString()); item.put("source_kind", row.sourceKind());
            item.put("source_dataset", row.sourceDataset()); item.put("sales_unit", "KRW");
            item.put("sales_amount", row.salesAmount()); item.put("store_count", row.storeCount());
            item.put("opening_count", row.openingCount()); item.put("closing_count", row.closingCount());
            item.put("resident_population", row.residentPopulation());
            item.put("working_population", row.workingPopulation());
            item.put("floating_population", row.floatingPopulation()); item.put("area_km2", row.areaKm2());
        }
        return node;
    }

    private boolean validResponse(JsonNode node, NearTermResearchRequest request) {
        if (node == null || !node.isObject() || !"experimental_research_only".equals(node.path("mode").asText())
                || !request.regionCode().equals(node.path("region_code").asText())
                || !request.regionCodeSystem().equals(node.path("region_code_system").asText())
                || !request.industryCode().equals(node.path("industry_code").asText())
                || !request.basePeriod().equals(node.path("base_period").asText())
                || !request.targetPeriod().equals(node.path("target_period").asText())
                || !request.asOf().toString().equals(node.path("as_of").asText())
                || !comparisonPeriod(request.targetPeriod()).equals(node.path("comparison_period").asText())
                || !expectedEstimateKind(request).equals(node.path("estimate_kind").asText())
                || !safeDate(node.path("snapshot_available_at"))
                || !safeDateTime(node.path("generated_at"))
                || !node.path("model_sha256").isString()
                || !node.path("model_sha256").asText().matches("[0-9a-f]{64}")
                || !optionalText(node.get("model_created_at"), 100)
                || !node.path("input_values_verified").isBoolean()
                || node.path("input_values_verified").asBoolean()
                || !node.path("region_scope_verified").isBoolean()
                || node.path("region_scope_verified").asBoolean()
                || !node.path("warnings").isArray() || node.path("warnings").size() > 32
                || !optionalBoolean(node.get("scope_seen_in_training"))
                || !optionalNonnegativeInteger(node.get("industry_test_rows"))
                || !optionalText(node.get("model_choice"), 100)) return false;
        for (JsonNode warning : node.path("warnings")) if (!safeText(warning, 300)) return false;
        String status = node.path("status").asText();
        JsonNode growth = node.get("estimated_yoy_growth"), reason = node.get("reason");
        if ("available".equals(status)) {
            JsonNode evaluation = node.get("retrospective_test");
            return finite(growth) && growth.asDouble() >= -1 && reason != null && reason.isNull()
                    && !LocalDate.parse(node.path("snapshot_available_at").asText()).isAfter(request.asOf())
                    && safeText(node.get("model_choice"), 100)
                    && evaluation != null && evaluation.isObject()
                    && (request.targetPeriod().equals("2026Q3") ? "2025Q3" : "2025Q4")
                            .equals(evaluation.path("target_period").asText())
                    && positiveInteger(evaluation.path("rows"))
                    && nonnegativeFinite(evaluation.path("mae_pp"))
                    && nonnegativeFinite(evaluation.path("median_absolute_error_pp"));
        }
        return "unavailable".equals(status) && growth != null && growth.isNull()
                && safeText(reason, 160)
                && node.get("retrospective_test") != null && node.get("retrospective_test").isNull();
    }

    private static String comparisonPeriod(String target) { return target.equals("2026Q3") ? "2025Q3" : "2025Q4"; }
    private static String expectedEstimateKind(NearTermResearchRequest request) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate effective = request.asOf().isBefore(today) ? request.asOf() : today;
        int year = Integer.parseInt(request.targetPeriod().substring(0, 4));
        int quarter = Character.digit(request.targetPeriod().charAt(5), 10);
        LocalDate end = YearMonth.of(year, quarter * 3).atEndOfMonth();
        return !end.isAfter(effective) ? "completed_quarter_unobserved_estimate"
                : "in_progress_or_future_quarter_estimate";
    }
    private static boolean safeDate(JsonNode node) {
        if (node == null || !node.isString()) return false;
        try { LocalDate.parse(node.asText()); return true; }
        catch (RuntimeException failure) { return false; }
    }
    private static boolean safeDateTime(JsonNode node) {
        if (node == null || !node.isString()) return false;
        try { OffsetDateTime.parse(node.asText()); return true; }
        catch (RuntimeException failure) { return false; }
    }
    private static boolean safeText(JsonNode node, int limit) {
        if (node == null || !node.isString()) return false;
        String value = node.asText();
        return !value.isBlank() && value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean optionalText(JsonNode node, int limit) {
        return node != null && (node.isNull() || safeText(node, limit));
    }
    private static boolean optionalBoolean(JsonNode node) {
        return node != null && (node.isNull() || node.isBoolean());
    }
    private static boolean optionalNonnegativeInteger(JsonNode node) {
        return node != null && (node.isNull()
                || node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0);
    }
    private static boolean positiveInteger(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }
    private static boolean finite(JsonNode node) { return node != null && node.isNumber() && Double.isFinite(node.asDouble()); }
    private static boolean nonnegativeFinite(JsonNode node) { return finite(node) && node.asDouble() >= 0; }

    /** 검증된 연구 필드만 반환하고 임의 디버그 필드는 FE나 로그로 넘기지 않는다. */
    private ObjectNode safeResponse(JsonNode source) {
        ObjectNode clean = mapper.createObjectNode();
        copy(source, clean, "mode", "status", "estimated_yoy_growth", "reason", "region_code",
                "region_code_system", "industry_code", "base_period", "target_period", "comparison_period",
                "estimate_kind", "as_of", "generated_at", "snapshot_available_at", "model_sha256",
                "model_created_at", "scope_seen_in_training", "industry_test_rows", "model_choice",
                "region_scope_verified", "input_values_verified", "warnings");
        JsonNode evaluation = source.get("retrospective_test");
        if (evaluation.isNull()) clean.set("retrospective_test", evaluation);
        else {
            ObjectNode safeEvaluation = mapper.createObjectNode();
            copy(evaluation, safeEvaluation, "target_period", "rows", "mae_pp", "median_absolute_error_pp");
            clean.set("retrospective_test", safeEvaluation);
        }
        return clean;
    }

    private static void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) target.set(field, source.get(field));
    }
    private static boolean containsSecret(JsonNode value, String secret) {
        if (value.isString() && value.asText().contains(secret)) return true;
        if (value.isObject() || value.isArray())
            for (JsonNode child : value) if (containsSecret(child, secret)) return true;
        return false;
    }
    private static NearTermResearchResult fail(TransportStatus status, String reason) {
        return NearTermResearchResult.unavailable(status, reason);
    }
    private static boolean causedByTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof HttpTimeoutException) return true;
        return false;
    }
    private static boolean causedByLimit(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof ResponseTooLarge) return true;
        return false;
    }
    private static final class ResponseTooLarge extends IOException {}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return future; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel(); future.completeExceptionally(new ResponseTooLarge()); return;
                }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { future.completeExceptionally(error); }
        @Override public void onComplete() { future.complete(bytes.toByteArray()); }
    }
}
