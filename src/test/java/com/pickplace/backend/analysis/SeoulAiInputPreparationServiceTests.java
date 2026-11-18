package com.pickplace.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static com.pickplace.backend.analysis.SeoulAiHistoryPlanner.Kind.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SeoulAiInputPreparationServiceTests {
    private final SeoulPublishedStatisticsRepository repo = mock(SeoulPublishedStatisticsRepository.class);
    private final SeoulAiInputPreparationService service = new SeoulAiInputPreparationService(repo, JsonMapper.builder().build());
    private final LocalDate date = LocalDate.parse("2026-10-01");
    private final LocalDate asOf = LocalDate.parse("2026-11-19");
    private final LocalDate from = LocalDate.parse("2026-01-01"), to = LocalDate.parse("2026-12-31");
    private final SeoulAiHistoryPlanner.Mapping mapping = new SeoulAiHistoryPlanner.Mapping("1111051500", "I21201",
            "11110515", "CS100010", "v1", true, true, date, from, to);

    private SeoulAiHistoryPlanner.Evidence proof(String dataset, String version) {
        return new SeoulAiHistoryPlanner.Evidence(dataset, version, true, true, date, date, date, from, to, true, true);
    }

    @Test
    void published_store_and_explicitly_approved_supplements_produce_fit_request() {
        Instant time = Instant.parse("2026-10-01T00:00:00Z");
        var row = new SeoulPublishedStatisticsRepository.Row("STORE", "20263", 42, 5, "11110515", "1111051500",
                "CS100010", "VALID", "VERIFIED", "VALID", null,
                "{\"similr_induty_stor_co\":{\"value\":12,\"status\":\"AVAILABLE\"}}", "{}", time, time, time);
        when(repo.find("1111051500", "11110515", "CS100010", "20261", "20263", asOf)).thenReturn(List.of(row));
        var extra = List.of(new SeoulAiHistoryPlanner.Value(RESIDENT_POPULATION, "2026Q3", "11110515", null,
                        new BigDecimal("3000"), proof("OA-22183", "approved-v1")),
                new SeoulAiHistoryPlanner.Value(AREA_SQUARE_METERS, "2026Q3", "11110515", null,
                        new BigDecimal("2500000"), proof("OA-22160", "approved-v1")));
        var result = service.prepare(mapping, "20261", "20263", asOf, Map.of(42L, Map.of(STORE_COUNT,
                proof("OA-22172", "batch:42"))), extra);
        assertThat(result.get(SeoulAiHistoryPlanner.Metric.FIT).basePeriod()).isEqualTo("2026Q3");
        assertThat(result.get(SeoulAiHistoryPlanner.Metric.FIT).request().history().getFirst().areaKm2()).isEqualByComparingTo("2.5");
        assertThat(service.prepare(mapping, "20261", "20263", asOf, Map.of(), extra)
                .get(SeoulAiHistoryPlanner.Metric.FIT).request()).isNull();
    }

    @Test
    void cannot_inject_store_or_sales_as_supplement_or_read_without_mapping() {
        assertThat(service.prepare(null, "20261", "20263", asOf, Map.of(), List.of())
                .get(SeoulAiHistoryPlanner.Metric.FIT).reason()).isEqualTo("mapping_unverified");
        verifyNoInteractions(repo);
        var invalid = new SeoulAiHistoryPlanner.Value(SALES, "2026Q3", "11110515", "CS100010",
                BigDecimal.TEN, proof("OA-22175", "batch:42"));
        assertThatThrownBy(() -> service.prepare(mapping, "20261", "20263", asOf, Map.of(), List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
