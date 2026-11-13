package com.pickplace.backend.ai;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.analysis.AnalysisOrchestrator;
import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.common.response.ErrorDetail;

/** 원본 H10·소진공 업종을 보존한 채 내부 분석 조합 로직을 호출한다. */
@RestController
@RequestMapping("/api/ai/analyses")
public class AiAnalysisController {
    private final AnalysisOrchestrator analyses;
    private final Clock clock;

    public AiAnalysisController(AnalysisOrchestrator analyses, Clock analysisClock) {
        this.analyses = analyses;
        this.clock = analysisClock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AiAnalysisResponse> analyze(@RequestBody AiAnalysisResponse.Request request) {
        validate(request);
        LocalDate asOf = LocalDate.now(clock);
        String basePeriod = lastCompleteQuarter(asOf);
        var query = new AnalysisOrchestrator.Query(request.regionCode(), request.industryCode(), basePeriod, asOf);
        return ApiResponse.ok(AiAnalysisResponse.from(analyses.analyze(query)));
    }

    private static void validate(AiAnalysisResponse.Request request) {
        if (request == null)
            throw error(ErrorCode.INVALID_REQUEST, "request", "REQUIRED");
        if (request.regionCode() == null || request.regionCode().isEmpty())
            throw error(ErrorCode.INVALID_REGION_CODE, "regionCode", "REQUIRED");
        if (!request.regionCode().matches("[0-9]{10}"))
            throw error(ErrorCode.INVALID_REGION_CODE, "regionCode", "INVALID_FORMAT");
        if (request.industryCode() == null || request.industryCode().isEmpty())
            throw error(ErrorCode.INVALID_REQUEST, "industryCode", "REQUIRED");
        if (!request.industryCode().matches("[A-Z][0-9]{5}"))
            throw error(ErrorCode.INVALID_REQUEST, "industryCode", "INVALID_FORMAT");
    }

    static String lastCompleteQuarter(LocalDate asOf) {
        // 진행 중인 분기를 결과 기간으로 잘못 표시하지 않도록 직전 분기를 선택한다.
        int firstMonth = (asOf.getMonthValue() - 1) / 3 * 3 + 1;
        LocalDate lastDay = LocalDate.of(asOf.getYear(), firstMonth, 1).minusDays(1);
        return lastDay.getYear() + "Q" + ((lastDay.getMonthValue() - 1) / 3 + 1);
    }

    private static ApiException error(ErrorCode code, String field, String reason) {
        return new ApiException(code, List.of(new ErrorDetail(field, reason)));
    }
}
