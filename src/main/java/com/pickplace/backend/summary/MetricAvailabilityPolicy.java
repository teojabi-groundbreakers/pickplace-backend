package com.pickplace.backend.summary;

import org.springframework.stereotype.Component;
import com.pickplace.backend.summary.MetricDecision.Reason;
import com.pickplace.backend.summary.MetricEvidence.Population;
import com.pickplace.backend.summary.MetricEvidence.PopulationStatus;
import com.pickplace.backend.summary.MetricEvidence.RegionState;
import com.pickplace.backend.summary.MetricEvidence.Stores;

/** DB나 AI를 호출하지 않는 순수 판정기다. 조건 순서는 현황 명세의 공개 사유 우선순위다. */
@Component
public class MetricAvailabilityPolicy {
    public MetricDecision population(Population evidence) {
        var row = evidence.row();
        // 실제 원천의 비공개 표시만 인정한다. 결측이나 경계 보류를 SUPPRESSED로 만들지 않는다.
        if (row != null && row.status() == PopulationStatus.SUPPRESSED) return blocked(Reason.SOURCE_SUPPRESSED);
        Reason boundary = regionReason(evidence.regionState());
        if (boundary != null) return blocked(boundary);
        if (row != null && (row.status() == PopulationStatus.INVALID
                || row.status() == PopulationStatus.AVAILABLE && !safe(row.value()))) return blocked(Reason.QUALITY_CHECK_FAILED);
        if (!evidence.sourceRegionPresent()) return blocked(Reason.SOURCE_REGION_ABSENT);
        if (row == null) return blocked(Reason.SOURCE_ROW_ABSENT);
        if (row.status() == PopulationStatus.NO_DATA) return blocked(Reason.SOURCE_VALUE_MISSING);
        return MetricDecision.available(row.value());
    }

    public MetricDecision stores(SummaryReadContext context, Stores evidence) {
        // 분류본이 다르면 코드 문자열이 같아도 같은 업종으로 합치지 않는다. 정상 인구 판정은 독립적이다.
        if (!context.classificationMatches()) return blocked(Reason.CLASSIFICATION_MISMATCH);
        Reason boundary = regionReason(evidence.regionState());
        if (boundary != null) return blocked(boundary);
        var count = evidence.aggregate();
        if (evidence.countAffected() || count != null && count.held()) return blocked(Reason.COUNT_INCOMPLETE);
        if (count != null && !safe(count.acceptedCount())) return blocked(Reason.QUALITY_CHECK_FAILED);
        if (!evidence.sourceRegionPresent()) return blocked(Reason.SOURCE_REGION_ABSENT);
        if (!evidence.coverageValid()) return blocked(Reason.QUALITY_CHECK_FAILED);
        // 유효한 수록 범위·같은 분류·영향 없음이 확인된 경우에만 미수록 업종을 0으로 해석한다.
        return MetricDecision.available(count == null ? 0 : count.acceptedCount());
    }

    private static Reason regionReason(RegionState state) {
        return switch (state) {
            case VALID -> null;
            case MAPPING_UNRESOLVED -> Reason.MAPPING_UNRESOLVED;
            case BOUNDARY_REVIEW_PENDING -> Reason.BOUNDARY_REVIEW_PENDING;
            case BOUNDARY_MISMATCH -> Reason.BOUNDARY_MISMATCH;
        };
    }
    private static boolean safe(Long value) { return value != null && value >= 0 && value <= 9_007_199_254_740_991L; }
    private static MetricDecision blocked(Reason reason) { return MetricDecision.withoutValue(reason); }
}
