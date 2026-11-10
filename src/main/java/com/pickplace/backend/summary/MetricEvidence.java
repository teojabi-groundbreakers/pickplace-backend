package com.pickplace.backend.summary;

import java.util.Objects;

/** T17 조회기가 조립할 판정 입력이다. null 행과 숫자 0을 구분하고, 귀속·시점 검토를 생략하지 않는다. */
public final class MetricEvidence {
    private MetricEvidence() {}
    // 원천→H10 대응과 공개 지역 시점 호환을 검토한 결과다. 단순히 H10 문자열이 같다고 VALID가 아니다.
    public enum RegionState { VALID, MAPPING_UNRESOLVED, BOUNDARY_REVIEW_PENDING, BOUNDARY_MISMATCH }
    public enum PopulationStatus { AVAILABLE, NO_DATA, INVALID, SUPPRESSED }
    public record PopulationRow(PopulationStatus status, Long value) {
        public PopulationRow { Objects.requireNonNull(status); }
    }
    public record Population(RegionState regionState, boolean sourceRegionPresent, PopulationRow row) {
        public Population { Objects.requireNonNull(regionState); }
    }
    public record StoreAggregate(long acceptedCount, boolean held) {}
    public record Stores(RegionState regionState, boolean sourceRegionPresent, boolean coverageValid,
            boolean countAffected, StoreAggregate aggregate) {
        public Stores { Objects.requireNonNull(regionState); }
    }
}
