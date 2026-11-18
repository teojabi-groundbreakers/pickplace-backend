package com.pickplace.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static com.pickplace.backend.analysis.SeoulAiHistoryPlanner.Kind.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SeoulAiSourceDecoderTests {
    private final SeoulAiSourceDecoder decoder = new SeoulAiSourceDecoder(JsonMapper.builder().build());
    private final LocalDate date = LocalDate.parse("2026-10-01");
    private final SeoulAiHistoryPlanner.Mapping mapping = new SeoulAiHistoryPlanner.Mapping("1111051500", "I21201",
            "11110515", "CS100010", "mapping-v1", true, true, date,
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

    private SeoulAiHistoryPlanner.Evidence evidence(String dataset, String version, boolean approved) {
        return new SeoulAiHistoryPlanner.Evidence(dataset, version, true, approved, date, date, date,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), true, true);
    }

    private SeoulPublishedStatisticsRepository.Row row(String dataset, String status, String metrics) {
        Instant time = Instant.parse("2026-10-01T00:00:00Z");
        return new SeoulPublishedStatisticsRepository.Row(dataset, "20262", 42, 5, "11110515", "1111051500",
                "CS100010", status, "VERIFIED", "VALID", null, metrics, "{}", time, time, time);
    }

    @Test
    void sales_source_value_requires_explicit_batch_approval_and_is_not_scaled() {
        var row = row("SALES", "VALID", """
                {"thsmon_selng_amt":{"value":123456,"status":"UNVERIFIED","reasonCode":"SOURCE_DEFINITION_UNREVIEWED"}}
                """);
        assertThat(decoder.decode(row, mapping, Map.of())).isEmpty();
        assertThat(decoder.decode(row, mapping, Map.of(SALES, evidence("OA-22175", "batch:41", true)))).isEmpty();
        var values = decoder.decode(row, mapping, Map.of(SALES, evidence("OA-22175", "batch:42", true)));
        assertThat(values).hasSize(1);
        assertThat(values.getFirst().amount()).isEqualByComparingTo(new BigDecimal("123456"));
        assertThat(values.getFirst().period()).isEqualTo("2026Q2");
    }

    @Test
    void store_uses_total_store_not_ordinary_store_and_preserves_zero() {
        var row = row("STORE", "VALID", """
                {"stor_co":{"value":9,"status":"AVAILABLE"},
                 "similr_induty_stor_co":{"value":12,"status":"AVAILABLE"},
                 "clsbiz_stor_co":{"value":0,"status":"AVAILABLE"}}
                """);
        var proof = evidence("OA-22172", "batch:42", true);
        var values = decoder.decode(row, mapping, Map.of(STORE_COUNT, proof, CLOSING_COUNT, proof));
        assertThat(values).extracting(v -> v.amount().intValue()).containsExactly(12, 0);
    }

    @Test
    void withheld_rows_and_unapproved_fields_are_not_converted() {
        var row = row("STORE", "HOLD", "{\"similr_induty_stor_co\":{\"value\":12,\"status\":\"AVAILABLE\"}}");
        assertThat(decoder.decode(row, mapping, Map.of(STORE_COUNT, evidence("OA-22172", "batch:42", true)))).isEmpty();
        var valid = row("STORE", "VALID", "{\"similr_induty_stor_co\":{\"value\":12,\"status\":\"AVAILABLE\"}}");
        assertThat(decoder.decode(valid, mapping, Map.of(STORE_COUNT, evidence("OA-22172", "batch:42", false)))).isEmpty();
    }
}
