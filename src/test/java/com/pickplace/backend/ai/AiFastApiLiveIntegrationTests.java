package com.pickplace.backend.ai;

import com.pickplace.backend.analysis.AnalysisOrchestrator;
import com.pickplace.backend.analysis.AnalysisOrchestrator.PublishedRow;
import com.pickplace.backend.analysis.AnalysisOrchestrator.Query;
import com.pickplace.backend.analysis.AnalysisOrchestrator.ScopeMapping;
import com.pickplace.backend.analysis.AnalysisOrchestrator.Snapshot;
import com.pickplace.backend.analysis.AnalysisOrchestrator.Statistics;
import com.pickplace.backend.analysis.AnalysisOrchestrator.SupplementalEvidence;
import com.pickplace.backend.common.web.RequestIdFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 FastAPI 프로세스와 Spring 내부 조합·HTTP 클라이언트 사이의 계약을 확인한다. */
class AiFastApiLiveIntegrationTests {
    private static final LocalDate AS_OF = LocalDate.of(2026, 2, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @EnabledIfEnvironmentVariable(named = "PICKPLACE_TEST_FASTAPI_URL", matches = ".+")
    void reviewedFixturePassesThroughRealFastApiAndKeepsPartialResults() {
        // 공식 자료가 아닌 테스트 전용 승인 이력이다. 실제 서비스 데이터의 검증 상태를 뜻하지 않는다.
        var client = new AiPredictClient(JsonMapper.builder().build(), true,
                System.getenv("PICKPLACE_TEST_FASTAPI_URL"), System.getenv("PICKPLACE_TEST_FASTAPI_KEY"), 1_000, 5_000);
        var query = new Query("1111051500", "I21201", "2025Q4", AS_OF);
        var result = new AnalysisOrchestrator(ignored -> fixture(), client::predict, CLOCK).analyze(query);

        assertThat(result.ai().state()).isEqualTo(AnalysisOrchestrator.AiState.AVAILABLE);
        assertThat(result.ai().reason()).isNull();
        var response = result.ai().prediction().response();
        assertThat(response.path("region_code").asString()).isEqualTo("11110515");
        assertThat(response.path("industry_code").asString()).isEqualTo("CS100001");
        assertThat(response.path("growth").path("status").asString()).isEqualTo("unavailable");
        assertThat(response.path("growth").path("reason").asString()).isEqualTo("model_not_found");
        assertThat(response.path("growth").path("estimated_yoy_growth").isNull()).isTrue();
        assertThat(response.path("risk").path("status").asString()).isEqualTo("available");
        assertThat(response.path("fit").path("status").asString()).isEqualTo("available");
        assertThat(response.path("risk").path("policy_status").asString()).isEqualTo("draft");
        assertThat(result.statistics().version()).isEqualTo("test-only-fixture");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PICKPLACE_TEST_FASTAPI_URL", matches = ".+")
    void publicControllerPassesThroughRealFastApiAndReturnsFrontendEnvelope() throws Exception {
        // 공개 HTTP 응답의 data 구조까지 검증하되 DB 대신 테스트 전용 이력만 제공한다.
        var client = new AiPredictClient(JsonMapper.builder().build(), true,
                System.getenv("PICKPLACE_TEST_FASTAPI_URL"), System.getenv("PICKPLACE_TEST_FASTAPI_KEY"), 1_000, 5_000);
        var analyses = new AnalysisOrchestrator(ignored -> fixture(), client::predict, CLOCK);
        var http = MockMvcBuilders.standaloneSetup(new AiAnalysisController(analyses, CLOCK))
                .addFilters(new RequestIdFilter()).build();

        http.perform(post("/api/ai/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"1111051500\",\"industryCode\":\"I21201\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.regionCode").value("1111051500"))
                .andExpect(jsonPath("$.data.request.industryCode").value("I21201"))
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.data.basePeriod").value("2025Q4"))
                .andExpect(jsonPath("$.data.ai.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.ai.result.growth.status").value("unavailable"))
                .andExpect(jsonPath("$.data.ai.result.growth.reason").value("model_not_found"))
                .andExpect(jsonPath("$.data.ai.result.risk.status").value("available"))
                .andExpect(jsonPath("$.data.ai.result.fit.status").value("available"));
    }

    private static Snapshot fixture() {
        var mapping = new ScopeMapping("1111051500", "I21201", "11110515", "seoul-adstrd", "CS100001",
                "test-only-mapping", true, true, LocalDate.of(2026, 2, 3),
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        return new Snapshot(new Statistics("test-only-fixture", Map.of()), mapping, List.of(
                row("2024Q4", "100", null, null, null, null, LocalDate.of(2025, 1, 15)),
                row("2025Q4", "80", 10L, 2L, new BigDecimal("3000"), new BigDecimal("2"),
                        LocalDate.of(2026, 2, 1))));
    }

    private static PublishedRow row(String period, String sales, Long stores, Long closings,
            BigDecimal residents, BigDecimal area, LocalDate publication) {
        var observation = new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100001",
                period, publication, "test-only-sales-fixture", new BigDecimal(sales),
                stores, null, closings, residents, null, null, area);
        var evidence = new SupplementalEvidence("test-only-supplement-fixture", true, true,
                LocalDate.of(2026, 2, 3), true, publication, publication, true, true);
        Map<String, SupplementalEvidence> supplements = stores == null ? Map.of() : Map.of(
                "store_count", evidence, "closing_count", evidence,
                "resident_population", evidence, "area_km2", evidence);
        return new PublishedRow(observation, true, true, LocalDate.of(2026, 2, 3), true,
                publication, true, supplements);
    }
}
