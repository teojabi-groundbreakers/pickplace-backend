package com.pickplace.backend.summary;

/** 공개 지표 판정. 내부 채택 수가 있어도 보류 상태라면 공개 value는 반드시 null이다. */
public record MetricDecision(Long value, Status status, Reason reasonCode) {
    public enum Status { AVAILABLE, NO_DATA, UNAVAILABLE }
    public enum Reason {
        SOURCE_SUPPRESSED, CLASSIFICATION_MISMATCH, MAPPING_UNRESOLVED,
        BOUNDARY_REVIEW_PENDING, BOUNDARY_MISMATCH, COUNT_INCOMPLETE, QUALITY_CHECK_FAILED,
        SOURCE_REGION_ABSENT, SOURCE_ROW_ABSENT, SOURCE_VALUE_MISSING
    }
    public MetricDecision {
        if (status == null || (status == Status.AVAILABLE
                ? value == null || value < 0 || value > 9_007_199_254_740_991L || reasonCode != null
                : value != null || reasonCode == null || (status == Status.NO_DATA) != isMissing(reasonCode))) {
            throw new IllegalArgumentException("지표 값·상태·사유 조합 오류");
        }
    }
    public static MetricDecision available(long value) { return new MetricDecision(value, Status.AVAILABLE, null); }
    public static MetricDecision withoutValue(Reason reason) {
        return new MetricDecision(null, isMissing(reason) ? Status.NO_DATA : Status.UNAVAILABLE, reason);
    }
    private static boolean isMissing(Reason reason) {
        return reason == Reason.SOURCE_REGION_ABSENT || reason == Reason.SOURCE_ROW_ABSENT || reason == Reason.SOURCE_VALUE_MISSING;
    }
}
