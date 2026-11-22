package com.pickplace.backend.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pickplace.backend.analysis.PerIndicatorAnalysisService;
import com.pickplace.backend.summary.MetricDecision;
import com.pickplace.backend.summary.SummaryReadContext;
import com.pickplace.backend.summary.SummaryService;

@WebMvcTest(AiAnalysisV2Controller.class)
@Import(AiAnalysisV2ControllerTests.Fixture.class)
class AiAnalysisV2ControllerTests {
    @Autowired MockMvc http;
    @MockitoBean SummaryService summaries;
    @MockitoBean PerIndicatorAnalysisService analysis;

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean Clock analysisClock() { return Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneOffset.UTC); }
    }

    @Test
    void v2_preserves_statistics_when_no_ai_input_is_approved() throws Exception {
        var selection = new SummaryReadContext.Selection(1, 2, "1111051500", "청운효자동", "I21201", "카페");
        var published = new SummaryReadContext.PublishedSummary(3, "202609", 2, Instant.parse("2026-11-12T03:00:00Z"), 1, 2);
        var source = new SummaryReadContext.Source(4, "POPULATION_MONTH", "v1", LocalDate.parse("2026-09-30"), null,
                Instant.parse("2026-11-01T00:00:00Z"));
        var context = new SummaryReadContext(selection, published, source, source);
        when(summaries.getSummary(anyString(), anyString())).thenReturn(new SummaryService.SummaryResult(context,
                MetricDecision.available(100), MetricDecision.available(10)));
        var unavailable = new PerIndicatorAnalysisService.Metric("unavailable", null, null, "mapping_unverified");
        var growth = new PerIndicatorAnalysisService.Growth("unavailable", null, null, "mapping_unverified", null, null);
        when(analysis.analyze(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(
                new PerIndicatorAnalysisService.Result("SKIPPED", "mapping_unverified", null,
                        new PerIndicatorAnalysisService.Metrics(growth, unavailable, unavailable), List.of(), null));
        http.perform(post("/api/ai/analyses/v2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"1111051500\",\"industryCode\":\"I21201\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value("2.0"))
                .andExpect(jsonPath("$.data.statistics.metrics.registeredPopulation.value").value(100))
                .andExpect(jsonPath("$.data.ai.state").value("SKIPPED"))
                .andExpect(jsonPath("$.data.ai.metrics.fit.reason").value("mapping_unverified"))
                .andExpect(jsonPath("$.data.ai.analysisScope").doesNotExist())
                .andExpect(jsonPath("$.data.basePeriod").doesNotExist());
    }
}
