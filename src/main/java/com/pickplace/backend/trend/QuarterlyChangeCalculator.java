package com.pickplace.backend.trend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/** 한 지역·업종·지표의 분기 관측값에서 비교 가능한 변화만 계산한다. */
public final class QuarterlyChangeCalculator {
    private static final int PERCENT_SCALE = 2;

    private QuarterlyChangeCalculator() {}

    public static Changes calculate(Quarter quarter, Map<Quarter, Observation> observations) {
        var current = observations.get(quarter);
        return new Changes(
                compare(current, observations.get(quarter.previous())),
                compare(current, observations.get(quarter.lastYear())));
    }

    private static Change compare(Observation current, Observation baseline) {
        if (current == null || current.status() == ObservationStatus.MISSING)
            return unavailable(ChangeStatus.CURRENT_MISSING);
        if (current.status() != ObservationStatus.AVAILABLE)
            return unavailable(ChangeStatus.CURRENT_UNAVAILABLE);
        if (baseline == null || baseline.status() == ObservationStatus.MISSING)
            return unavailable(ChangeStatus.BASELINE_MISSING);
        if (baseline.status() != ObservationStatus.AVAILABLE)
            return unavailable(ChangeStatus.BASELINE_UNAVAILABLE);
        if (!current.scope().equals(baseline.scope()))
            return unavailable(ChangeStatus.NOT_COMPARABLE);

        BigDecimal difference = current.value().subtract(baseline.value());
        if (baseline.value().signum() == 0)
            return new Change(difference, null, ChangeStatus.ZERO_BASELINE);
        BigDecimal rate = difference.movePointRight(2)
                .divide(baseline.value(), PERCENT_SCALE, RoundingMode.HALF_UP);
        return new Change(difference, rate, ChangeStatus.AVAILABLE);
    }

    private static Change unavailable(ChangeStatus status) {
        return new Change(null, null, status);
    }

    public record Quarter(int year, int number) {
        public Quarter {
            if (number < 1 || number > 4) throw new IllegalArgumentException("분기는 1~4입니다.");
        }

        Quarter previous() {
            return number == 1 ? new Quarter(year - 1, 4) : new Quarter(year, number - 1);
        }

        Quarter lastYear() {
            return new Quarter(year - 1, number);
        }
    }

    /** 비교 범위에는 원천·지역 경계·업종·지표 정의·단위 식별자를 넣는다. */
    public record Observation(BigDecimal value, ObservationStatus status, String scope) {
        public Observation {
            Objects.requireNonNull(status);
            if (status == ObservationStatus.AVAILABLE
                    && (value == null || value.signum() < 0 || scope == null || scope.isBlank()))
                throw new IllegalArgumentException("비교 가능한 관측에는 값과 비교 범위가 필요합니다.");
        }
    }

    public enum ObservationStatus { AVAILABLE, MISSING, UNAVAILABLE }

    public enum ChangeStatus {
        AVAILABLE, ZERO_BASELINE, CURRENT_MISSING, CURRENT_UNAVAILABLE,
        BASELINE_MISSING, BASELINE_UNAVAILABLE, NOT_COMPARABLE
    }

    public record Change(BigDecimal absoluteChange, BigDecimal changeRatePercent, ChangeStatus status) {}

    public record Changes(Change previousQuarter, Change sameQuarterLastYear) {}
}
