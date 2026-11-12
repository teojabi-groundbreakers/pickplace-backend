package com.pickplace.backend.trend;

import static com.pickplace.backend.trend.QuarterlyChangeCalculator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarterlyChangeCalculatorTest {
    private static final Quarter CURRENT = new Quarter(2026, 1);
    private static final Quarter PREVIOUS = new Quarter(2025, 4);
    private static final Quarter LAST_YEAR = new Quarter(2025, 1);

    @Test
    void usesExactPreviousQuarterAcrossYearsAndExactSameQuarterLastYear() {
        var changes = calculate(CURRENT, Map.of(CURRENT, value(7), PREVIOUS, value(6), LAST_YEAR, value(10)));
        assertThat(changes.previousQuarter()).isEqualTo(
                new Change(BigDecimal.ONE, new BigDecimal("16.67"), ChangeStatus.AVAILABLE));
        assertThat(changes.sameQuarterLastYear()).isEqualTo(
                new Change(BigDecimal.valueOf(-3), new BigDecimal("-30.00"), ChangeStatus.AVAILABLE));
    }

    @Test
    void preservesAbsoluteChangeWhenBaselineIsZeroAndAllowsCurrentZero() {
        var increase = calculate(CURRENT, Map.of(CURRENT, value(3), PREVIOUS, value(0))).previousQuarter();
        assertThat(increase).isEqualTo(new Change(BigDecimal.valueOf(3), null, ChangeStatus.ZERO_BASELINE));
        var decrease = calculate(CURRENT, Map.of(CURRENT, value(0), PREVIOUS, value(3))).previousQuarter();
        assertThat(decrease.changeRatePercent()).isEqualByComparingTo("-100.00");
        var bothZero = calculate(CURRENT, Map.of(CURRENT, value(0), PREVIOUS, value(0))).previousQuarter();
        assertThat(bothZero).isEqualTo(new Change(BigDecimal.ZERO, null, ChangeStatus.ZERO_BASELINE));
    }

    @Test
    void distinguishesMissingAndUnavailableValuesWithoutSearchingForAnotherQuarter() {
        var missing = new Observation(null, ObservationStatus.MISSING, null);
        var unverified = new Observation(BigDecimal.TEN, ObservationStatus.UNAVAILABLE, null);
        assertThat(calculate(CURRENT, Map.of(PREVIOUS, value(4))).previousQuarter().status())
                .isEqualTo(ChangeStatus.CURRENT_MISSING);
        assertThat(calculate(CURRENT, Map.of(CURRENT, unverified, PREVIOUS, value(4))).previousQuarter().status())
                .isEqualTo(ChangeStatus.CURRENT_UNAVAILABLE);
        assertThat(calculate(CURRENT, Map.of(CURRENT, value(4), PREVIOUS, unverified)).previousQuarter().status())
                .isEqualTo(ChangeStatus.BASELINE_UNAVAILABLE);
        assertThat(calculate(CURRENT, Map.of(CURRENT, value(4), PREVIOUS, missing, LAST_YEAR, value(1)))
                .previousQuarter().status()).isEqualTo(ChangeStatus.BASELINE_MISSING);
    }

    @Test
    void rejectsMixedScopesAndInvalidAvailableObservations() {
        var other = new Observation(BigDecimal.TEN, ObservationStatus.AVAILABLE, "other-region-or-source");
        assertThat(calculate(CURRENT, Map.of(CURRENT, value(4), PREVIOUS, other)).previousQuarter())
                .isEqualTo(new Change(null, null, ChangeStatus.NOT_COMPARABLE));
        assertThatThrownBy(() -> value(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quarter(2026, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Observation value(long value) {
        return new Observation(BigDecimal.valueOf(value), ObservationStatus.AVAILABLE, "same-series");
    }
}
