package com.pickplace.backend.ai;

import java.io.File;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.pickplace.backend.ai.NearTermResearchResult.TransportStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 AI 프로젝트의 CSV에서 요청을 만들고 최신 모델 FastAPI와 Java 클라이언트를 대조한다. */
class NearTermResearchLiveIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @EnabledIfEnvironmentVariable(named = "PICKPLACE_TEST_NEAR_TERM_URL", matches = ".+")
    void actualCsvRequestMatchesLatestForecastAndKeepsResearchLimits() throws Exception {
        // 전용 환경변수가 모두 주어진 경우에만 외부 프로젝트·실제 집계 자료를 읽는다.
        Path aiRoot = Path.of(required("PICKPLACE_TEST_NEAR_TERM_AI_ROOT")).toRealPath();
        Path historical = Path.of(required("PICKPLACE_TEST_NEAR_TERM_HISTORICAL_CSV")).toRealPath();
        Path modelRoot = Path.of(required("PICKPLACE_TEST_NEAR_TERM_MODEL_DIR")).toRealPath();
        String region = required("PICKPLACE_TEST_NEAR_TERM_REGION_CODE");
        String industry = required("PICKPLACE_TEST_NEAR_TERM_INDUSTRY_CODE");
        String apiKey = required("PICKPLACE_TEST_NEAR_TERM_KEY");
        String endpoint = required("PICKPLACE_TEST_NEAR_TERM_URL");
        String expectedModelHash = required("PICKPLACE_TEST_NEAR_TERM_EXPECTED_SHA256");
        assertThat(aiRoot.resolve("scripts/make_near_term_request.py")).isRegularFile();
        assertThat(historical).isRegularFile();
        assertThat(modelRoot.resolve("model.joblib")).isRegularFile();
        JsonNode summary = JSON.readTree(Files.readString(modelRoot.resolve("summary.json")));
        String asOf = summary.path("as_of").asText();
        String modelHash = summary.path("model_sha256").asText();
        assertThat(modelHash).isEqualTo(expectedModelHash);
        assertThat(LocalDate.parse(asOf)).isAfterOrEqualTo(LocalDate.of(2026, 7, 1));
        Path temporary = Files.createTempDirectory("pickplace-near-term-java-");
        try {
            var client = new NearTermResearchClient(JSON, true, endpoint, apiKey, 1_000, 15_000);
            for (String target : List.of("2026Q3", "2026Q4")) {
                Path payloadFile = temporary.resolve(target + ".json");
                generateRequest(aiRoot, historical, region, industry, target, asOf, payloadFile);
                NearTermResearchRequest input = parseRequest(JSON.readTree(Files.readString(payloadFile)), null);
                var result = client.predict(input);
                assertThat(result.transportStatus()).isEqualTo(TransportStatus.RETURNED);
                JsonNode response = result.response();
                assertThat(response.path("status").asText()).isEqualTo("available");
                assertThat(response.path("model_sha256").asText()).isEqualTo(modelHash);
                assertThat(response.path("input_values_verified").asBoolean()).isFalse();
                assertThat(response.path("region_scope_verified").asBoolean()).isFalse();
                assertThat(response.path("warnings").isArray()).isTrue();
                double expected = forecastValue(modelRoot, region, industry, target);
                assertThat(response.path("estimated_yoy_growth").asDouble()).isCloseTo(expected,
                        org.assertj.core.data.Offset.offset(1.0e-10));
                String horizon = target.equals("2026Q3") ? "1" : "2";
                double historicalMae = summary.path("results").path(horizon).path("common_2025_test")
                        .path("new").path("mae_pp").asDouble();
                assertThat(response.path("retrospective_test").path("mae_pp").asDouble())
                        .isCloseTo(historicalMae, org.assertj.core.data.Offset.offset(1.0e-10));
            }

            // 원본 비교 분기의 매출이 없으면 연구 API의 null·사유를 그대로 보존한다.
            JsonNode q3 = JSON.readTree(Files.readString(temporary.resolve("2026Q3.json")));
            NearTermResearchRequest missingComparison = parseRequest(q3, "2025Q3");
            var unavailable = client.predict(missingComparison);
            assertThat(unavailable.transportStatus()).isEqualTo(TransportStatus.RETURNED);
            assertThat(unavailable.response().path("status").asText()).isEqualTo("unavailable");
            assertThat(unavailable.response().path("estimated_yoy_growth").isNull()).isTrue();
            assertThat(unavailable.response().path("reason").asText())
                    .isEqualTo("missing_or_nonpositive_comparison_sales");
        } finally {
            Files.deleteIfExists(temporary.resolve("2026Q3.json"));
            Files.deleteIfExists(temporary.resolve("2026Q4.json"));
            Files.deleteIfExists(temporary);
        }
    }

    private static void generateRequest(Path aiRoot, Path historical, String region, String industry,
            String target, String asOf, Path output) throws Exception {
        var process = new ProcessBuilder(
                aiRoot.resolve(".venv/bin/python").toString(), "-B", "-m", "scripts.make_near_term_request",
                "--input", historical.toString(), "--region-code", region,
                "--industry-code", industry, "--target-period", target,
                "--as-of", asOf, "--output", output.toString())
                .directory(aiRoot.toFile()).redirectErrorStream(true);
        process.environment().put("PYTHONPATH", aiRoot.resolve("src") + File.pathSeparator + aiRoot);
        process.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process run = process.start();
        boolean finished = run.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            run.destroyForcibly();
            throw new IllegalStateException("AI request generation timed out");
        }
        if (run.exitValue() != 0 || !Files.isRegularFile(output))
            throw new IllegalStateException("AI request generation failed");
    }

    private static NearTermResearchRequest parseRequest(JsonNode root, String omitSalesPeriod) {
        var rows = new ArrayList<NearTermResearchRequest.Observation>();
        for (JsonNode row : root.path("history")) {
            rows.add(new NearTermResearchRequest.Observation(
                    row.path("region_code").asText(), row.path("region_code_system").asText(),
                    row.path("industry_code").asText(), row.path("period").asText(),
                    LocalDate.parse(row.path("available_at").asText()), row.path("source_kind").asText(),
                    row.path("source_dataset").asText(),
                    row.path("period").asText().equals(omitSalesPeriod) ? null : decimal(row.get("sales_amount")),
                    count(row.get("store_count")), count(row.get("opening_count")), count(row.get("closing_count")),
                    decimal(row.get("resident_population")), decimal(row.get("working_population")),
                    decimal(row.get("floating_population")), decimal(row.get("area_km2"))));
        }
        return new NearTermResearchRequest(root.path("region_code").asText(),
                root.path("region_code_system").asText(), root.path("industry_code").asText(),
                root.path("base_period").asText(), root.path("target_period").asText(),
                LocalDate.parse(root.path("as_of").asText()), root.path("source_kind").asText(), rows);
    }

    private static BigDecimal decimal(JsonNode value) {
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }
    private static Long count(JsonNode value) {
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static double forecastValue(Path modelRoot, String region, String industry, String target) throws Exception {
        try (Reader reader = Files.newBufferedReader(modelRoot.resolve("forecast-2026-q3-q4.csv"));
                var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (var row : csv) {
                if (region.equals(row.get("region_code")) && industry.equals(row.get("industry_code"))
                        && target.equals(row.get("target_period")))
                    return Double.parseDouble(row.get("estimated_yoy_growth"));
            }
        }
        throw new IllegalStateException("The selected research scope is absent from the forecast artifact");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the opt-in test");
        return value;
    }
}
