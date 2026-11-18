package com.pickplace.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.pickplace.backend.analysis.SeoulAiHistoryPlanner.Kind.*;
import static com.pickplace.backend.analysis.SeoulAiHistoryPlanner.Metric.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SeoulAiHistoryPlannerTests {
    private static final LocalDate AS_OF = LocalDate.parse("2026-11-19");
    private static final LocalDate START = LocalDate.parse("2024-01-01");
    private static final LocalDate END = LocalDate.parse("2026-12-31");
    private final SeoulAiHistoryPlanner planner = new SeoulAiHistoryPlanner();
    private final SeoulAiHistoryPlanner.Mapping mapping = new SeoulAiHistoryPlanner.Mapping(
            "1111051500", "I21201", "11110515", "CS100010", "reviewed-v1", true, true,
            LocalDate.parse("2026-10-01"), START, END);

    private SeoulAiHistoryPlanner.Value value(SeoulAiHistoryPlanner.Kind kind, String period, String amount) {
        String source = switch (kind) {
            case SALES -> "OA-22175";
            case STORE_COUNT, CLOSING_COUNT -> "OA-22172";
            case RESIDENT_POPULATION -> "OA-22183";
            case AREA_SQUARE_METERS -> "OA-22160";
        };
        var evidence = new SeoulAiHistoryPlanner.Evidence(source, "v1", true, true,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"),
                START, END, true, true);
        String industry = switch (kind) {
            case SALES, STORE_COUNT, CLOSING_COUNT -> "CS100010";
            default -> null;
        };
        return new SeoulAiHistoryPlanner.Value(kind, period, "11110515", industry, new BigDecimal(amount), evidence);
    }

    @Test
    void selects_latest_period_per_metric_and_converts_square_meters() {
        var values = List.of(value(SALES, "2025Q2", "100"), value(SALES, "2026Q2", "80"),
                value(STORE_COUNT, "2026Q2", "10"), value(CLOSING_COUNT, "2026Q2", "2"),
                value(STORE_COUNT, "2026Q3", "12"), value(RESIDENT_POPULATION, "2026Q3", "3000"),
                value(AREA_SQUARE_METERS, "2026Q3", "2500000"));
        var result = planner.prepare(mapping, values, AS_OF);
        assertThat(result.get(RISK).basePeriod()).isEqualTo("2026Q2");
        assertThat(result.get(RISK).request().history()).extracting(r -> r.period()).containsExactly("2025Q2", "2026Q2");
        assertThat(result.get(FIT).basePeriod()).isEqualTo("2026Q3");
        assertThat(result.get(FIT).request().history().getFirst().areaKm2()).isEqualByComparingTo("2.5");
        assertThat(result.get(FIT).request().history().getFirst().salesAmount()).isNull();
        assertThat(result.get(GROWTH).reason()).isEqualTo("insufficient_verified_data");
    }

    @Test
    void area_without_boundary_evidence_is_not_used_and_earlier_valid_quarter_is_chosen() {
        var bad = value(AREA_SQUARE_METERS, "2026Q3", "3000000");
        var e = bad.evidence();
        bad = new SeoulAiHistoryPlanner.Value(bad.kind(), bad.period(), bad.sourceRegionCode(), null, bad.amount(),
                new SeoulAiHistoryPlanner.Evidence(e.dataset(), e.version(), true, true, e.approvedAt(),
                        e.publishedAt(), e.availableAt(), e.validFrom(), e.validThrough(), true, false));
        var values = List.of(value(STORE_COUNT, "2026Q3", "12"), value(RESIDENT_POPULATION, "2026Q3", "3000"), bad,
                value(STORE_COUNT, "2026Q2", "10"), value(RESIDENT_POPULATION, "2026Q2", "2500"),
                value(AREA_SQUARE_METERS, "2026Q2", "1000000"));
        assertThat(planner.prepare(mapping, values, AS_OF).get(FIT).basePeriod()).isEqualTo("2026Q2");
    }

    @Test
    void growth_needs_five_consecutive_quarters_and_expired_target_is_not_a_forecast() {
        var rows = new ArrayList<SeoulAiHistoryPlanner.Value>();
        for (String period : List.of("2025Q2", "2025Q3", "2025Q4", "2026Q1", "2026Q2"))
            rows.add(value(SALES, period, "100"));
        var growth = planner.prepare(mapping, rows, AS_OF).get(GROWTH);
        assertThat(growth.basePeriod()).isEqualTo("2026Q2");
        assertThat(growth.targetPeriod()).isEqualTo("2026Q3");
        assertThat(growth.reason()).isEqualTo("target_not_future");
        assertThat(growth.request()).isNull();
        rows.remove(2);
        assertThat(planner.prepare(mapping, rows, AS_OF).get(GROWTH).reason()).isEqualTo("insufficient_verified_data");
    }

    @Test
    void absent_or_unapproved_mapping_never_constructs_request() {
        var unapproved = new SeoulAiHistoryPlanner.Mapping(mapping.regionCode(), mapping.businessTypeCode(),
                mapping.sourceRegionCode(), mapping.sourceIndustryCode(), "v1", true, false, mapping.approvedAt(), START, END);
        assertThat(planner.prepare(unapproved, List.of(value(STORE_COUNT, "2026Q3", "10")), AS_OF).get(FIT).reason())
                .isEqualTo("mapping_unverified");
    }

    @Test
    void duplicate_verified_input_is_rejected_and_zero_store_does_not_enable_fit() {
        var zero = value(STORE_COUNT, "2026Q3", "0");
        assertThatThrownBy(() -> planner.prepare(mapping, List.of(zero, zero), AS_OF)).isInstanceOf(IllegalArgumentException.class);
        assertThat(planner.prepare(mapping, List.of(zero, value(RESIDENT_POPULATION, "2026Q3", "3000"),
                value(AREA_SQUARE_METERS, "2026Q3", "1000000")), AS_OF).get(FIT).reason())
                .isEqualTo("insufficient_verified_data");
    }

    @Test
    void late_publication_and_foreign_scope_do_not_enter_ai_request() {
        var late = value(RESIDENT_POPULATION, "2026Q3", "3000");
        var e = late.evidence();
        late = new SeoulAiHistoryPlanner.Value(late.kind(), late.period(), late.sourceRegionCode(), null, late.amount(),
                new SeoulAiHistoryPlanner.Evidence(e.dataset(), e.version(), true, true, e.approvedAt(),
                        e.publishedAt(), LocalDate.parse("2026-12-01"), e.validFrom(), e.validThrough(), true, true));
        var wrong = value(STORE_COUNT, "2026Q3", "12");
        wrong = new SeoulAiHistoryPlanner.Value(wrong.kind(), wrong.period(), wrong.sourceRegionCode(),
                "CS100008", wrong.amount(), wrong.evidence());
        var selection = planner.prepare(mapping, List.of(late, wrong, value(AREA_SQUARE_METERS, "2026Q3", "1000000")), AS_OF).get(FIT);
        assertThat(selection.request()).isNull();
    }

    @Test
    void growth_request_keeps_source_sales_without_rescaling() {
        var rows = new ArrayList<SeoulAiHistoryPlanner.Value>();
        for (String period : List.of("2025Q3", "2025Q4", "2026Q1", "2026Q2", "2026Q3"))
            rows.add(value(SALES, period, "123456"));
        var growth = planner.prepare(mapping, rows, AS_OF).get(GROWTH);
        assertThat(growth.basePeriod()).isEqualTo("2026Q3");
        assertThat(growth.targetPeriod()).isEqualTo("2026Q4");
        assertThat(growth.request().history()).hasSize(5);
        assertThat(growth.request().history().getLast().salesAmount()).isEqualByComparingTo("123456");
    }
}
