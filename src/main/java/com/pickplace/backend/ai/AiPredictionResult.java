package com.pickplace.backend.ai;

import tools.jackson.databind.JsonNode;

/**
 * 통신·응답 계약의 결과다. AVAILABLE이어도 성장률은 제공 불가일 수 있다.
 * 외부 오류의 상세 내용과 요청 본문은 결과에 담지 않는다.
 */
public record AiPredictionResult(Status status, JsonNode response, String reason) {
    @Override public String toString() { return "AiPredictionResult[status=" + status + ", reason=" + reason + "]"; }
    public enum Status { DISABLED, AVAILABLE, AUTH_FAILURE, INVALID_AI_REQUEST, UPSTREAM_UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }
    public static AiPredictionResult unavailable(Status status, String reason) {
        return new AiPredictionResult(status, null, reason);
    }
}
