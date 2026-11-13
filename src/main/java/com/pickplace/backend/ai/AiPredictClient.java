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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static com.pickplace.backend.ai.AiPredictionResult.Status;

/**
 * 이슈 #17의 기본 FastAPI /predict 계약을 호출한다.
 * 서울 단기·통합 연구 API는 요청·응답 형식이 달라 별도 클라이언트가 필요하다.
 * AVAILABLE은 응답 형식 검증 통과를 뜻하며 개별 지표는 제공 불가일 수 있다.
 */
@Component
public class AiPredictClient {
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final JsonMapper mapper;
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient http;

    public AiPredictClient(JsonMapper mapper, boolean enabled, String baseUrl, String apiKey,
            long connectMs, long requestMs) {
        this(mapper, enabled, baseUrl, apiKey, Long.toString(connectMs), Long.toString(requestMs));
    }

    @Autowired
    public AiPredictClient(JsonMapper mapper,
            @Value("${PICKPLACE_AI_ENABLED:false}") boolean enabled,
            @Value("${PICKPLACE_AI_BASE_URL:}") String baseUrl,
            @Value("${PICKPLACE_AI_API_KEY:}") String apiKey,
            @Value("${PICKPLACE_AI_CONNECT_TIMEOUT_MS:1000}") String connectTimeout,
            @Value("${PICKPLACE_AI_REQUEST_TIMEOUT_MS:3000}") String requestTimeout) {
        this.mapper = mapper;
        this.enabled = enabled;
        // AI 연동은 기본 비활성이다. 설정이 없어도 일반 BE 기동에 영향을 주지 않는다.
        if (!enabled) {
            this.endpoint = null; this.apiKey = null; this.requestTimeout = null; this.http = null;
            return;
        }
        long connectMs, requestMs;
        try {
            connectMs = Long.parseLong(connectTimeout);
            requestMs = Long.parseLong(requestTimeout);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("AI timeout must be a number");
        }
        if (apiKey == null || apiKey.isBlank() || !apiKey.chars().allMatch(c -> c >= 33 && c <= 126))
            throw new IllegalArgumentException("AI API key must be configured as printable ASCII");
        if (connectMs < 1 || connectMs > 60_000 || requestMs < 1 || requestMs > 120_000)
            throw new IllegalArgumentException("AI timeout must be within configured bounds");
        try {
            URI base = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/")))
                throw new IllegalArgumentException("Invalid AI endpoint configuration");
            this.endpoint = base.resolve("/predict");
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid AI endpoint configuration");
        }
        this.apiKey = apiKey;
        this.requestTimeout = Duration.ofMillis(requestMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public AiPredictionResult predict(AiPredictionRequest request) {
        if (!enabled) return AiPredictionResult.unavailable(Status.DISABLED, "ai_disabled");
        if (request == null) throw new IllegalArgumentException("request is required");
        HttpRequest outbound = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload(request))))
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(outbound, ignored -> new LimitedBody(MAX_RESPONSE_BYTES));
        try {
            // HttpRequest.timeout만으로 본문 수신 완료까지 제한하지 못할 수 있어 전체 대기 시간을 따로 제한한다.
            HttpResponse<byte[]> response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            int code = response.statusCode();
            if (code == 401 || code == 403) return fail(Status.AUTH_FAILURE, "ai_authentication_failed");
            if (code == 422 || code == 400) return fail(Status.INVALID_AI_REQUEST, "ai_request_rejected");
            if (code == 408 || code == 504) return fail(Status.TIMEOUT, "ai_timeout");
            if (code >= 500) return fail(Status.UPSTREAM_UNAVAILABLE, "ai_server_unavailable");
            if (code != 200) return fail(Status.INVALID_RESPONSE, "ai_unexpected_status");
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!"application/json".equalsIgnoreCase(contentType)) return fail(Status.INVALID_RESPONSE, "ai_response_content_type");
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            JsonNode tree = mapper.readTree(raw);
            if (!validResponse(tree, request)) return fail(Status.INVALID_RESPONSE, "ai_response_invalid");
            ObjectNode clean = safeResponse(tree);
            if (containsSecret(clean, apiKey)) return fail(Status.INVALID_RESPONSE, "ai_response_invalid");
            return new AiPredictionResult(Status.AVAILABLE, clean, null);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return fail(Status.TIMEOUT, "ai_timeout");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return fail(Status.UPSTREAM_UNAVAILABLE, "ai_request_interrupted");
        } catch (ExecutionException ex) {
            if (causedByTimeout(ex)) return fail(Status.TIMEOUT, "ai_timeout");
            if (causedByLimit(ex)) return fail(Status.INVALID_RESPONSE, "ai_response_too_large");
            return fail(Status.UPSTREAM_UNAVAILABLE, "ai_transport_failure");
        } catch (IOException ex) {
            return fail(Status.INVALID_RESPONSE, "ai_response_invalid");
        } catch (RuntimeException ex) {
            return fail(Status.INVALID_RESPONSE, "ai_response_invalid");
        }
    }

    private ObjectNode payload(AiPredictionRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", "1.0"); node.put("region_code", request.regionCode());
        node.put("region_code_system", request.regionCodeSystem()); node.put("industry_code", request.industryCode());
        node.put("base_period", request.basePeriod()); node.put("as_of", request.asOf().toString());
        node.put("source_kind", "official");
        ArrayNode history = node.putArray("history");
        for (AiPredictionRequest.Observation item : request.history()) {
            ObjectNode row = history.addObject();
            row.put("region_code", item.regionCode()); row.put("region_code_system", item.regionCodeSystem());
            row.put("industry_code", item.industryCode()); row.put("period", item.period());
            row.put("available_at", item.availableAt().toString()); row.put("source_kind", "official");
            row.put("source_dataset", item.sourceDataset()); row.put("sales_unit", "KRW");
            row.put("sales_amount", item.salesAmount()); row.put("store_count", item.storeCount());
            row.put("opening_count", item.openingCount()); row.put("closing_count", item.closingCount());
            row.put("resident_population", item.residentPopulation()); row.put("working_population", item.workingPopulation());
            row.put("floating_population", item.floatingPopulation()); row.put("area_km2", item.areaKm2());
        }
        return node;
    }

    // JSON 형식이 맞아도 요청 지역·업종 또는 분석일과 다르면 응답을 거부한다.
    private boolean validResponse(JsonNode node, AiPredictionRequest input) {
        if (node == null || !node.isObject() || !"1.0".equals(node.path("schema_version").asText())
                || !input.regionCode().equals(node.path("region_code").asText())
                || !input.regionCodeSystem().equals(node.path("region_code_system").asText())
                || !input.industryCode().equals(node.path("industry_code").asText())
                || !input.basePeriod().equals(node.path("base_period").asText())
                || !input.asOf().toString().equals(node.path("as_of").asText())
                || !"official".equals(node.path("source_kind").asText())
                || !node.path("warnings").isArray() || !safeText(node.path("policy_version"), 120)
                || !optionalText(node.get("model_version"), 120)) return false;
        JsonNode growth = node.path("growth"), risk = node.path("risk"), fit = node.path("fit");
        if (!growth.path("target_period").asText().equals(shiftQuarter(input.basePeriod(), 1))
                || !growth.path("comparison_period").asText().equals(shiftQuarter(input.basePeriod(), -3))
                || !optionalText(growth.get("model_version"), 120)
                || !validExplanations(growth.path("explanations"))
                || !safeText(risk.path("policy_status"), 80) || !safeText(fit.path("policy_status"), 80)
                || !validComponents(risk.path("components")) || !validComponents(fit.path("components"))) return false;
        if (node.path("warnings").size() > 32) return false;
        for (JsonNode warning : node.path("warnings")) if (!safeText(warning, 300)) return false;
        return validIndicator(growth, "estimated_yoy_growth", false)
                && validIndicator(risk, "score", true) && validIndicator(fit, "score", true);
    }

    private static String shiftQuarter(String period, int delta) {
        int index = Integer.parseInt(period.substring(0, 4)) * 4 + (period.charAt(5) - '1') + delta;
        return String.format(java.util.Locale.ROOT, "%04dQ%d", Math.floorDiv(index, 4), Math.floorMod(index, 4) + 1);
    }

    private boolean validIndicator(JsonNode node, String field, boolean score) {
        if (!node.isObject() || !node.has(field) || !node.has("reason")) return false;
        String status = node.path("status").asText();
        JsonNode value = node.get(field), reason = node.get("reason");
        if ("unavailable".equals(status))
            return value.isNull() && safeText(reason, 160) && !reason.asText().isBlank();
        if (!"available".equals(status) || !reason.isNull() || !value.isNumber()) return false;
        double n = value.asDouble();
        return Double.isFinite(n) && (score ? n >= 0 && n <= 100 : n >= -1);
    }

    private boolean validExplanations(JsonNode explanations) {
        if (!explanations.isArray() || explanations.size() > 3) return false;
        for (JsonNode item : explanations) {
            if (!item.isObject() || !safeText(item.path("feature"), 100)
                    || !"single_feature_reference_sensitivity".equals(item.path("method").asText())
                    || !finite(item.path("observed_value")) || !finite(item.path("reference_value"))
                    || !finite(item.path("delta_growth"))) return false;
            if (item.has("direction") && !List.of("positive", "negative").contains(item.path("direction").asText())) return false;
        }
        return true;
    }

    private boolean validComponents(JsonNode components) {
        if (!components.isArray() || components.size() > 16) return false;
        for (JsonNode item : components) {
            if (!item.isObject() || !safeText(item.path("name"), 100) || !safeText(item.path("unit"), 80)
                    || !finite(item.path("value")) || !finite(item.path("contribution_points"))) return false;
        }
        return true;
    }

    private static boolean finite(JsonNode node) { return node.isNumber() && Double.isFinite(node.asDouble()); }
    private static boolean optionalText(JsonNode node, int limit) { return node != null && (node.isNull() || safeText(node, limit)); }
    private static boolean safeText(JsonNode node, int limit) {
        if (node == null || !node.isString()) return false;
        String value = node.asText();
        return !value.isBlank() && value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** 문서화된 응답 필드만 복사하고 내부 디버그 정보는 다음 단계로 전달하지 않는다. */
    private ObjectNode safeResponse(JsonNode source) {
        ObjectNode result = mapper.createObjectNode();
        copy(source, result, "schema_version", "region_code", "region_code_system", "industry_code",
                "source_kind", "as_of", "base_period", "model_version", "policy_version", "warnings");
        ObjectNode growth = mapper.createObjectNode();
        copy(source.path("growth"), growth, "status", "reason", "estimated_yoy_growth", "target_period", "comparison_period", "model_version");
        ArrayNode explanations = growth.putArray("explanations");
        for (JsonNode item : source.path("growth").path("explanations")) {
            ObjectNode safe = explanations.addObject();
            copy(item, safe, "feature", "method", "observed_value", "reference_value", "delta_growth");
            if (item.has("direction")) safe.set("direction", item.get("direction"));
        }
        result.set("growth", growth);
        for (String name : List.of("risk", "fit")) {
            JsonNode indicator = source.path(name);
            ObjectNode safe = mapper.createObjectNode();
            copy(indicator, safe, "status", "score", "reason", "policy_status");
            ArrayNode components = safe.putArray("components");
            for (JsonNode item : indicator.path("components")) {
                ObjectNode component = components.addObject();
                copy(item, component, "name", "value", "unit", "contribution_points");
            }
            result.set(name, safe);
        }
        return result;
    }
    private static boolean containsSecret(JsonNode value, String secret) {
        if (value.isString() && value.asText().contains(secret)) return true;
        if (value.isObject() || value.isArray()) for (JsonNode child : value) if (containsSecret(child, secret)) return true;
        return false;
    }
    private static void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) target.set(field, source.get(field));
    }

    private static AiPredictionResult fail(Status status, String reason) { return AiPredictionResult.unavailable(status, reason); }
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
    // 응답 전체를 메모리에 담기 전에 스트리밍 중 크기 제한을 적용한다.
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
