package com.pickplace.backend.summary;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.pickplace.backend.summary.MetricDecision.*;
import com.pickplace.backend.summary.MetricEvidence.*;
import com.pickplace.backend.summary.SummaryReadContext.*;
import static org.assertj.core.api.Assertions.*;

class MetricAvailabilityPolicyTests {
    final MetricAvailabilityPolicy policy = new MetricAvailabilityPolicy();
    static SummaryReadContext context(boolean same) {
        var source = new Source(1, "POPULATION_MONTH", "test", LocalDate.of(2026, 9, 30), null, Instant.EPOCH);
        return new SummaryReadContext(new Selection(1, same ? 2 : 3, "1111051500", "동", "I21201", "카페"),
                new PublishedSummary(1, "202609", 1, Instant.EPOCH, 1, 2), source, source);
    }
    @ParameterizedTest @ValueSource(longs = {0, 100, 9007199254740991L})
    void keepsSafeValuesAndRealZero(long value) {
        assertThat(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.AVAILABLE, value))))
                .isEqualTo(MetricDecision.available(value));
        assertThat(policy.stores(context(true), new Stores(RegionState.VALID, true, true, false, new StoreAggregate(value, false))))
                .isEqualTo(MetricDecision.available(value));
    }
    @Test void missingSourceAndMissingRowAreDifferent() {
        assertReason(policy.population(new Population(RegionState.VALID, false, null)), Reason.SOURCE_REGION_ABSENT);
        assertReason(policy.population(new Population(RegionState.VALID, true, null)), Reason.SOURCE_ROW_ABSENT);
        assertReason(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.NO_DATA, null))), Reason.SOURCE_VALUE_MISSING);
        assertReason(policy.stores(context(true), new Stores(RegionState.VALID, false, false, false, null)), Reason.SOURCE_REGION_ABSENT);
    }
    @Test void zeroNeedsValidCoverageAndNoCountImpact() {
        assertThat(policy.stores(context(true), new Stores(RegionState.VALID, true, true, false, null))).isEqualTo(MetricDecision.available(0));
        assertReason(policy.stores(context(true), new Stores(RegionState.VALID, true, false, false, null)), Reason.QUALITY_CHECK_FAILED);
        assertReason(policy.stores(context(true), new Stores(RegionState.VALID, true, true, true, null)), Reason.COUNT_INCOMPLETE);
    }
    @Test void neverPublishes97WhenThreeRowsAreHeld() {
        var result = policy.stores(context(true), new Stores(RegionState.VALID, true, true, true, new StoreAggregate(97, true)));
        assertReason(result, Reason.COUNT_INCOMPLETE); assertThat(result.value()).isNull();
    }
    @Test void differentClassificationOnlyBlocksStores() {
        assertReason(policy.stores(context(false), new Stores(RegionState.BOUNDARY_MISMATCH, false, false, true, null)), Reason.CLASSIFICATION_MISMATCH);
        assertThat(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.AVAILABLE, 100L))).value()).isEqualTo(100L);
    }
    @ParameterizedTest @EnumSource(value=RegionState.class, names="VALID", mode=EnumSource.Mode.EXCLUDE)
    void regionReviewPrecedesMissingData(RegionState state) {
        var expected = Reason.valueOf(state.name());
        assertReason(policy.population(new Population(state, false, null)), expected);
        assertReason(policy.stores(context(true), new Stores(state, false, false, true, null)), expected);
    }
    @Test void actualSuppressionPrecedesBoundaryAndOtherQuality() {
        assertReason(policy.population(new Population(RegionState.BOUNDARY_MISMATCH, true, new PopulationRow(PopulationStatus.SUPPRESSED, null))), Reason.SOURCE_SUPPRESSED);
        assertReason(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.INVALID, null))), Reason.QUALITY_CHECK_FAILED);
    }
    @ParameterizedTest @ValueSource(longs={-1,9007199254740992L})
    void unsafeNumbersAreNotPublicValues(long number) {
        assertReason(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.AVAILABLE, number))), Reason.QUALITY_CHECK_FAILED);
        assertReason(policy.stores(context(true), new Stores(RegionState.VALID, true, true, false, new StoreAggregate(number, false))), Reason.QUALITY_CHECK_FAILED);
    }
    @Test void missingAvailableNumberIsNotZero() {
        assertReason(policy.population(new Population(RegionState.VALID, true, new PopulationRow(PopulationStatus.AVAILABLE, null))), Reason.QUALITY_CHECK_FAILED);
    }
    @Test void resultCannotExposeValueWithHeldStateOrInvalidReason() {
        assertThatThrownBy(() -> new MetricDecision(97L, Status.UNAVAILABLE, Reason.COUNT_INCOMPLETE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricDecision(null, Status.NO_DATA, Reason.BOUNDARY_MISMATCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricDecision(null, Status.UNAVAILABLE, Reason.SOURCE_ROW_ABSENT)).isInstanceOf(IllegalArgumentException.class);
    }
    static void assertReason(MetricDecision decision, Reason reason) {
        assertThat(decision.reasonCode()).isEqualTo(reason); assertThat(decision.value()).isNull();
    }
}
