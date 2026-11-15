package com.pickplace.backend.ai;

import tools.jackson.databind.JsonNode;

/** HTTP 계약 검증 결과와 연구 모델 자체의 가용성 상태를 분리한다. */
public record NearTermResearchResult(TransportStatus transportStatus, JsonNode response, String reason) {
    public enum TransportStatus {
        RETURNED, DISABLED, AUTH_FAILURE, INVALID_REQUEST, TIMEOUT, UPSTREAM_UNAVAILABLE, INVALID_RESPONSE
    }

    public NearTermResearchResult {
        if (transportStatus == null
                || (transportStatus == TransportStatus.RETURNED) != (response != null)
                || (transportStatus == TransportStatus.RETURNED) != (reason == null))
            throw new IllegalArgumentException("invalid near-term transport result");
    }

    public static NearTermResearchResult unavailable(TransportStatus status, String reason) {
        return new NearTermResearchResult(status, null, reason);
    }

    @Override public String toString() {
        return "NearTermResearchResult[transportStatus=" + transportStatus + ", reason=" + reason + "]";
    }
}
