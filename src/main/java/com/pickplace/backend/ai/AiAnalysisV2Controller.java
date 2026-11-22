package com.pickplace.backend.ai;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.analysis.PerIndicatorAnalysisService;
import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.summary.SummaryResponse;
import com.pickplace.backend.summary.SummaryService;

/** 지표별 기준 분기 응답. 기존 v1 경로를 유지하며 별도 경로로 제공한다. */
@RestController
@RequestMapping("/api/ai/analyses/v2")
public class AiAnalysisV2Controller {
    private final SummaryService summaries;
    private final PerIndicatorAnalysisService analysis;
    private final Clock clock;

    public AiAnalysisV2Controller(SummaryService summaries, PerIndicatorAnalysisService analysis, Clock analysisClock) {
        this.summaries = summaries;
        this.analysis = analysis;
        this.clock = analysisClock;
    }

    public record Response(String schemaVersion, AiAnalysisResponse.Request request, LocalDate asOf,
            AiAnalysisResponse.Statistics statistics, PerIndicatorAnalysisService.Result ai) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Response> analyze(@RequestBody AiAnalysisResponse.Request request) {
        AiAnalysisController.validate(request);
        LocalDate asOf = LocalDate.now(clock);
        var summary = summaries.getSummary(request.regionCode(), request.industryCode());
        var published = summary.context().summary();
        var stats = new AiAnalysisResponse.Statistics("national-summary-" + published.referenceMonth() + "-r" + published.revision(),
                published.referenceMonth(), published.publishedAt().toString(), SummaryResponse.metrics(summary));
        String to = AiAnalysisController.lastCompleteQuarter(asOf);
        // 직전 기준 분기의 전년 동기와 연속 성장 이력을 포함한다.
        String from = shift(to, -5);
        return ApiResponse.ok(new Response("2.0", request, asOf, stats,
                analysis.analyze(request.regionCode(), request.industryCode(), compact(from), compact(to), asOf)));
    }

    private static String compact(String period) { return period.substring(0, 4) + period.substring(5); }
    private static String shift(String period, int offset) {
        int index = Integer.parseInt(period.substring(0, 4)) * 4 + period.charAt(5) - '1' + offset;
        if (index < 4) index = 4;
        return (index / 4) + "Q" + (index % 4 + 1);
    }
}
