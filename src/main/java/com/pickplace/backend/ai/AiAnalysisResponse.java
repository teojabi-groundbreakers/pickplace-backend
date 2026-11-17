package com.pickplace.backend.ai;

import java.time.LocalDate;

import com.pickplace.backend.analysis.AnalysisOrchestrator;
import com.pickplace.backend.summary.SummaryResponse;

import tools.jackson.databind.JsonNode;

/** 요청 범위와 통계·AI의 개별 가용성을 함께 전달한다. */
public record AiAnalysisResponse(Request request, LocalDate asOf, String basePeriod,
        Statistics statistics, Ai ai) {

    public record Request(String regionCode, String industryCode) {}

    // 현황 API와 같은 기준월·게시 시각·지표 모양이다. AI 결과와 관계없이 채운다.
    // 현황 판정 없이 만든 조합(연구용 테스트 등)은 기준월·게시 시각·지표가 null이다.
    public record Statistics(String version, String referenceMonth, String publishedAt, SummaryResponse.Metrics metrics) {}

    // result는 AI 응답 중 검증을 통과한 값이다. SKIPPED·FAILED이면 항상 null이며 점수 0으로 대신하지 않는다.
    public record Ai(AiState state, String reason, TransportStatus transportStatus, JsonNode result) {}

    // 공개 응답의 AI 상태다. 내부 조합 로직의 상태 이름이 바뀌어도 FE 응답이 바로 바뀌지 않도록 따로 정해 둔다.
    // SKIPPED는 AI를 호출하지 않음(자료·대응 미확인 등), FAILED는 호출했지만 결과를 쓸 수 없음이다.
    public enum AiState { SKIPPED, AVAILABLE, FAILED }

    // 공개 응답의 AI 통신 결과다. 실제 AI 호출 결과가 있을 때만 채운다.
    public enum TransportStatus {
        DISABLED, AVAILABLE, AUTH_FAILURE, INVALID_AI_REQUEST, UPSTREAM_UNAVAILABLE, TIMEOUT, INVALID_RESPONSE
    }

    static AiAnalysisResponse from(AnalysisOrchestrator.Result value) {
        var query = value.request();
        var outcome = value.ai();
        var prediction = outcome.prediction();
        return new AiAnalysisResponse(
                new Request(query.regionCode(), query.businessTypeCode()),
                query.asOf(), query.basePeriod(),
                statistics(value.statistics()),
                new Ai(state(outcome.state()), outcome.reason(),
                        prediction == null ? null : transportStatus(prediction.status()),
                        prediction == null ? null : prediction.response()));
    }

    // 조합 결과의 통계를 현황 API와 같은 모양으로 바꾼다. 날짜·시각은 현황 API처럼 문자열로 고정한다.
    private static Statistics statistics(AnalysisOrchestrator.Statistics statistics) {
        var summary = statistics.summary();
        if (summary == null) {
            return new Statistics(statistics.version(), null, null, null);
        }
        var published = summary.context().summary();
        return new Statistics(statistics.version(), published.referenceMonth(), published.publishedAt().toString(),
                SummaryResponse.metrics(summary));
    }

    // 내부 상태를 공개 상태로 바꾼다. 내부에 상태가 추가되면 여기서 컴파일 오류가 나 공개 값을 먼저 정하게 된다.
    private static AiState state(AnalysisOrchestrator.AiState state) {
        return switch (state) {
            case SKIPPED -> AiState.SKIPPED;
            case AVAILABLE -> AiState.AVAILABLE;
            case FAILED -> AiState.FAILED;
        };
    }

    // 내부 통신 결과를 공개 값으로 바꾼다. 위와 같은 이유로 모든 값을 명시적으로 옮긴다.
    private static TransportStatus transportStatus(AiPredictionResult.Status status) {
        return switch (status) {
            case DISABLED -> TransportStatus.DISABLED;
            case AVAILABLE -> TransportStatus.AVAILABLE;
            case AUTH_FAILURE -> TransportStatus.AUTH_FAILURE;
            case INVALID_AI_REQUEST -> TransportStatus.INVALID_AI_REQUEST;
            case UPSTREAM_UNAVAILABLE -> TransportStatus.UPSTREAM_UNAVAILABLE;
            case TIMEOUT -> TransportStatus.TIMEOUT;
            case INVALID_RESPONSE -> TransportStatus.INVALID_RESPONSE;
        };
    }
}
