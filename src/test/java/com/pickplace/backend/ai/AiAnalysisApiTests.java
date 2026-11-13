package com.pickplace.backend.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pickplace.backend.analysis.AnalysisConfiguration;
import com.pickplace.backend.analysis.PublishedSummarySnapshotReader;
import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.summary.SummaryContextService;
import com.pickplace.backend.summary.SummaryReadContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청부터 조합 로직까지 연결하되 외부 매출 근거를 테스트에서만 대신한다. */
@WebMvcTest(AiAnalysisController.class)
@Import({AnalysisConfiguration.class, PublishedSummarySnapshotReader.class, AiAnalysisApiTests.FixedClock.class})
class AiAnalysisApiTests {
    private static final String URL = "/api/ai/analyses";
    private static final String REGION = "1111051500";
    private static final String INDUSTRY = "I21201";

    @Autowired MockMvc http;
    @MockitoBean SummaryContextService summaries;
    @MockitoBean AiPredictClient aiClient;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void publishedSummary() {
        // 테스트는 게시 요약만 제공하며 미승인 매출을 운영 입력처럼 만들지 않는다.
        doAnswer(call -> {
            Function<SummaryReadContext, ?> callback = call.getArgument(2);
            return callback.apply(context());
        }).when(summaries).withContext(anyString(), anyString(), any());
    }

    @Test
    void publishedContextWithoutApprovedSalesReturnsSkipAndPreservesRequest() throws Exception {
        http.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"" + INDUSTRY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.regionCode").value(REGION))
                .andExpect(jsonPath("$.data.request.industryCode").value(INDUSTRY))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-13"))
                .andExpect(jsonPath("$.data.basePeriod").value("2026Q3"))
                .andExpect(jsonPath("$.data.statistics.version").value("national-summary-202610-r2"))
                .andExpect(jsonPath("$.data.statistics.metrics").isEmpty())
                .andExpect(jsonPath("$.data.ai.state").value("SKIPPED"))
                .andExpect(jsonPath("$.data.ai.reason").value("mapping_unverified"))
                .andExpect(jsonPath("$.data.ai.transportStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.ai.result").value(org.hamcrest.Matchers.nullValue()));
        verify(summaries).withContext(anyString(), anyString(), any());
        verifyNoInteractions(aiClient);
    }

    @Test
    void malformedInputIsRejectedBeforeSnapshotRead() throws Exception {
        http.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"11110515\",\"industryCode\":\"" + INDUSTRY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"))
                .andExpect(jsonPath("$.error.details[0].field").value("regionCode"));
        http.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"coffee\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("industryCode"));
        verifyNoInteractions(summaries, aiClient);
    }

    @Test
    void unpublishedSummaryRemainsServiceUnavailable() throws Exception {
        doThrow(new ApiException(ErrorCode.SUMMARY_DATA_NOT_READY)).when(summaries)
                .withContext(anyString(), anyString(), any());
        http.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"" + INDUSTRY + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SUMMARY_DATA_NOT_READY"));
        verify(aiClient, never()).predict(any());
    }

    @Test
    void completeQuarterSelectionHandlesYearAndQuarterBoundaries() {
        org.assertj.core.api.Assertions.assertThat(AiAnalysisController.lastCompleteQuarter(LocalDate.of(2026, 3, 31)))
                .isEqualTo("2025Q4");
        org.assertj.core.api.Assertions.assertThat(AiAnalysisController.lastCompleteQuarter(LocalDate.of(2026, 4, 1)))
                .isEqualTo("2026Q1");
        org.assertj.core.api.Assertions.assertThat(AiAnalysisController.lastCompleteQuarter(LocalDate.of(2027, 1, 1)))
                .isEqualTo("2026Q4");
    }

    private static SummaryReadContext context() {
        return new SummaryReadContext(
                new SummaryReadContext.Selection(1, 2, REGION, "서울 종로구 청운효자동", INDUSTRY, "카페"),
                new SummaryReadContext.PublishedSummary(3, "202610", 2,
                        Instant.parse("2026-11-12T03:00:00Z"), 1, 2),
                new SummaryReadContext.Source(4, "POPULATION_MONTH", "v1",
                        LocalDate.of(2026, 10, 31), null, Instant.parse("2026-11-11T03:00:00Z")),
                new SummaryReadContext.Source(5, "STORE_SNAPSHOT", "v1",
                        LocalDate.of(2026, 10, 31), "202610", Instant.parse("2026-11-11T03:00:00Z")));
    }
}
