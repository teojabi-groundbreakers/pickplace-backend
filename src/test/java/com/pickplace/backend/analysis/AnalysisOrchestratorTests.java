package com.pickplace.backend.analysis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.pickplace.backend.ai.AiPredictionRequest;
import com.pickplace.backend.ai.AiPredictionResult;
import com.pickplace.backend.analysis.AnalysisOrchestrator.*;
import com.pickplace.backend.summary.MetricDecision;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class AnalysisOrchestratorTests {
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private static final Query QUERY = new Query("1111051500", "I21201", "2026Q2", AS_OF);
    private static final Statistics STATS = new Statistics("snapshot-1", Map.of("population", MetricDecision.available(0)));

    static ScopeMapping mapping() {
        return new ScopeMapping("1111051500", "I21201", "11110515", "seoul-adstrd", "CS100010", "v1",
                true, true, LocalDate.of(2025, 1, 1), LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
    }

    static AiPredictionRequest.Observation observation(BigDecimal sales) {
        return new AiPredictionRequest.Observation("11110515", "seoul-adstrd", "CS100010", "2026Q2",
                LocalDate.of(2026, 8, 31), "approved-source", sales, 3L, 1L, 0L,
                BigDecimal.valueOf(100), null, null, BigDecimal.ONE);
    }

    static Map<String, SupplementalEvidence> supplementalProofs() {
        var proof = new SupplementalEvidence("supplement-source", true, true, LocalDate.of(2026, 9, 3), true, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2), true, true);
        return Map.of("store_count", proof, "opening_count", proof, "closing_count", proof,
                "resident_population", proof, "area_km2", proof);
    }

    static PublishedRow row() {
        return new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), true, supplementalProofs());
    }

    static Snapshot snapshot(ScopeMapping mapping, PublishedRow... rows) {
        return new Snapshot(STATS, mapping, List.of(rows));
    }

    static AiPredictionResult available() {
        var json = JsonMapper.builder().build().readTree("{\"growth\":{\"status\":\"unavailable\"},\"risk\":{\"status\":\"available\"}}");
        return new AiPredictionResult(AiPredictionResult.Status.AVAILABLE, json, null);
    }

    static AnalysisOrchestrator service(Snapshot snapshot, Predictor predictor) {
        return new AnalysisOrchestrator(query -> snapshot, predictor, CLOCK);
    }

    @Test void validSingleQuarterCanCallAndPreservesSourceAndPartialAiResults() {
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        var result = service(snapshot(mapping(), row()), request -> { sent.set(request); return available(); }).analyze(QUERY);
        assertThat(result.request().regionCode()).isEqualTo("1111051500");
        assertThat(result.statistics().metrics().get("population").value()).isZero();
        assertThat(result.ai().state()).isEqualTo(AiState.AVAILABLE);
        assertThat(result.ai().prediction().response().path("growth").path("status").asString()).isEqualTo("unavailable");
        assertThat(result.ai().prediction().response().path("risk").path("status").asString()).isEqualTo("available");
        assertThat(sent.get().regionCode()).isEqualTo("11110515");
        assertThat(sent.get().history()).hasSize(1);
        assertThat(sent.get().history().getFirst().storeCount()).isEqualTo(3);
        assertThat(sent.get().history().getFirst().availableAt()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(sent.get().history().getFirst().sourceDataset()).contains("supplement-source");
    }

    @Test void unverifiedExtraFieldsAreNotSent() {
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        var row = new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), true, Map.of());
        service(snapshot(mapping(), row), request -> { sent.set(request); return available(); }).analyze(QUERY);
        var submitted = sent.get().history().getFirst();
        assertThat(submitted.salesAmount()).isEqualByComparingTo("10");
        assertThat(submitted.storeCount()).isNull();
        assertThat(submitted.residentPopulation()).isNull();
        assertThat(submitted.areaKm2()).isNull();
    }

    @Test void futureOrUnapprovedSupplementIsIndividuallyWithheld() {
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        var future = new SupplementalEvidence("future", true, true, LocalDate.of(2026, 8, 30), true, LocalDate.of(2026, 11, 11),
                LocalDate.of(2026, 11, 12), true, true);
        var unapproved = new SupplementalEvidence("unapproved", true, true, LocalDate.of(2026, 8, 30), true, LocalDate.of(2026, 8, 30),
                LocalDate.of(2026, 8, 31), false, true);
        var safe = supplementalProofs().get("store_count");
        var extra = Map.of("store_count", safe, "resident_population", future, "area_km2", unapproved);
        var row = new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), true, extra);
        service(snapshot(mapping(), row), request -> { sent.set(request); return available(); }).analyze(QUERY);
        var submitted = sent.get().history().getFirst();
        assertThat(submitted.storeCount()).isEqualTo(3);
        assertThat(submitted.residentPopulation()).isNull();
        assertThat(submitted.areaKm2()).isNull();
        assertThat(submitted.sourceDataset()).doesNotContain("future", "unapproved");
    }

    @Test void noApprovedMappingKeepsStatisticsAndNeverCallsAi() {
        var called = new AtomicBoolean();
        var result = service(snapshot(null, row()), request -> { called.set(true); return available(); }).analyze(QUERY);
        assertThat(called).isFalse();
        assertThat(result.ai().state()).isEqualTo(AiState.SKIPPED);
        assertThat(result.ai().reason()).isEqualTo("mapping_unverified");
        assertThat(result.statistics()).isEqualTo(STATS);
    }

    @Test void misalignedOrNotEffectiveMappingNeverCallsAi() {
        var old = mapping();
        var mismatched = new ScopeMapping("1111051500", "I21001", old.sourceRegionCode(), old.sourceRegionCodeSystem(), old.sourceIndustryCode(), old.version(), true, true, old.approvedAt(), old.validFrom(), old.validThrough());
        var late = new ScopeMapping(old.selectedRegionCode(), old.selectedIndustryCode(), old.sourceRegionCode(), old.sourceRegionCodeSystem(), old.sourceIndustryCode(), old.version(), true, true, old.approvedAt(), LocalDate.of(2026, 5, 1), old.validThrough());
        assertSkip(snapshot(mismatched, row()), "mapping_scope_mismatch");
        assertSkip(snapshot(late, row()), "mapping_period_unverified");
    }

    @Test void nonOfficialOrUnapprovedSalesNeverCallAi() {
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), false, true, LocalDate.of(2026, 8, 29), true,
                LocalDate.of(2026, 8, 30), true, Map.of())), "source_not_approved");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, false, LocalDate.of(2026, 8, 29), true,
                LocalDate.of(2026, 8, 30), true, Map.of())), "source_not_approved");
    }

    @Test void missingOrFutureSalesApprovalDoesNotCallAi() {
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, null,
                true, LocalDate.of(2026, 8, 30), true, Map.of())), "source_approval_time_unverified");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 11, 11),
                true, LocalDate.of(2026, 8, 30), true, Map.of())), "source_approval_time_unverified");
    }

    @Test void sourceApprovalAfterAcquisitionSetsEffectiveAvailabilityWithoutChangingSource() {
        var original = observation(BigDecimal.TEN);
        var row = new PublishedRow(original, true, true, LocalDate.of(2026, 9, 15),
                true, LocalDate.of(2026, 8, 30), true, Map.of());
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        service(snapshot(mapping(), row), request -> { sent.set(request); return available(); }).analyze(QUERY);
        assertThat(original.availableAt()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(sent.get().history().getFirst().availableAt()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test void supplementalApprovalMissingOrInFutureIsNotSent() {
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        var missing = new SupplementalEvidence("missing", true, true, null, true,
                LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31), true, true);
        var future = new SupplementalEvidence("future", true, true, LocalDate.of(2026, 11, 11), true,
                LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31), true, true);
        var extras = Map.of("store_count", missing, "resident_population", future);
        var row = new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29),
                true, LocalDate.of(2026, 8, 30), true, extras);
        service(snapshot(mapping(), row), request -> { sent.set(request); return available(); }).analyze(QUERY);
        var submitted = sent.get().history().getFirst();
        assertThat(submitted.storeCount()).isNull();
        assertThat(submitted.residentPopulation()).isNull();
        assertThat(submitted.sourceDataset()).doesNotContain("future", "missing");
        assertThat(submitted.availableAt()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test void unknownOrFuturePublicationAndUnitAreRejected() {
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), false, LocalDate.of(2026, 8, 30), true, supplementalProofs())), "history_publication_unverified");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, null, true, supplementalProofs())), "history_publication_unverified");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 11, 11), true, supplementalProofs())), "history_publication_unverified");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), false, supplementalProofs())), "sales_unit_unverified");
    }

    @Test void emptyOrEntirelyMissingSalesIsNotFakedOrSent() {
        assertSkip(snapshot(mapping()), "insufficient_history");
        assertSkip(snapshot(mapping(), new PublishedRow(observation(null), true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), true, supplementalProofs())), "missing_sales");
    }

    @Test void nullHistoryRowIsSafelyRejectedWithoutLosingStatistics() {
        var rows = new java.util.ArrayList<PublishedRow>();
        rows.add(null);
        var data = new Snapshot(STATS, mapping(), rows);
        rows.clear(); // Snapshot holds its own immutable copy.
        assertThat(data.history()).hasSize(1);
        assertSkip(data, "invalid_reviewed_history");
    }

    @Test void nullSupplementEvidenceOnlyRemovesThatMetric() {
        var proofs = new java.util.HashMap<String, SupplementalEvidence>();
        proofs.put("store_count", null);
        var row = new PublishedRow(observation(BigDecimal.TEN), true, true, LocalDate.of(2026, 8, 29),
                true, LocalDate.of(2026, 8, 30), true, proofs);
        proofs.put("store_count", supplementalProofs().get("store_count"));
        var sent = new java.util.concurrent.atomic.AtomicReference<AiPredictionRequest>();
        var result = service(snapshot(mapping(), row), request -> { sent.set(request); return available(); }).analyze(QUERY);
        assertThat(result.statistics()).isEqualTo(STATS);
        assertThat(result.ai().state()).isEqualTo(AiState.AVAILABLE);
        assertThat(sent.get().history().getFirst().storeCount()).isNull();
    }

    @Test void duplicatesAndMismatchedSourceScopeAreRejected() {
        assertSkip(snapshot(mapping(), row(), row()), "invalid_reviewed_history");
        var other = new AiPredictionRequest.Observation("11110516", "seoul-adstrd", "CS100010", "2026Q2", LocalDate.of(2026, 8, 31), "source", BigDecimal.ONE, null, null, null, null, null, null, null);
        assertSkip(snapshot(mapping(), new PublishedRow(other, true, true, LocalDate.of(2026, 8, 29), true, LocalDate.of(2026, 8, 30), true, Map.of())), "history_scope_mismatch");
    }

    @Test void readTransactionMustEndBeforeNetwork() {
        var insideRead = new AtomicBoolean();
        var service = new AnalysisOrchestrator(q -> { insideRead.set(true); try { return snapshot(mapping(), row()); } finally { insideRead.set(false); } },
                request -> { assertThat(insideRead).isFalse(); return available(); }, CLOCK);
        assertThat(service.analyze(QUERY).ai().state()).isEqualTo(AiState.AVAILABLE);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(service.analyze(QUERY).ai().reason()).isEqualTo("ai_call_forbidden_in_transaction"); }
        finally { TransactionSynchronizationManager.clear(); }
    }

    @Test void upstreamFailuresKeepStatisticsAndSpecificStatus() {
        for (var status : List.of(AiPredictionResult.Status.TIMEOUT, AiPredictionResult.Status.AUTH_FAILURE,
                AiPredictionResult.Status.UPSTREAM_UNAVAILABLE, AiPredictionResult.Status.DISABLED)) {
            var result = service(snapshot(mapping(), row()), request -> AiPredictionResult.unavailable(status, "safe_code")).analyze(QUERY);
            assertThat(result.statistics()).isEqualTo(STATS);
            assertThat(result.ai().state()).isEqualTo(AiState.FAILED);
            assertThat(result.ai().prediction().status()).isEqualTo(status);
            assertThat(result.ai().reason()).isEqualTo("safe_code");
        }
    }

    @Test void unexpectedAiErrorsAndMalformedResponsesCannotDiscardStatistics() {
        var error = service(snapshot(mapping(), row()), request -> { throw new IllegalStateException("secret"); }).analyze(QUERY);
        assertThat(error.statistics()).isEqualTo(STATS);
        assertThat(error.ai().reason()).isEqualTo("ai_request_failed");
        var bad = service(snapshot(mapping(), row()), request -> new AiPredictionResult(AiPredictionResult.Status.AVAILABLE, null, null)).analyze(QUERY);
        assertThat(bad.statistics()).isEqualTo(STATS);
        assertThat(bad.ai().reason()).isEqualTo("invalid_ai_response");
    }

    @Test void inputOrDatabaseFailuresAreNotDisguisedAsAiMissingData() {
        assertThatThrownBy(() -> new Query("11110515", "I21201", "2026Q2", AS_OF)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("1111051500", "I21201", "2026Q2", LocalDate.of(2026, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        var future = new Query("1111051500", "I21201", "2026Q2", LocalDate.of(2027, 1, 1));
        assertThatThrownBy(() -> service(snapshot(mapping(), row()), request -> available()).analyze(future)).isInstanceOf(IllegalArgumentException.class);
        var broken = new AnalysisOrchestrator(q -> { throw new IllegalStateException("db failed"); }, request -> available(), CLOCK);
        assertThatThrownBy(() -> broken.analyze(QUERY)).isInstanceOf(IllegalStateException.class).hasMessage("db failed");
    }

    private static void assertSkip(Snapshot snapshot, String reason) {
        var called = new AtomicBoolean();
        var result = service(snapshot, request -> { called.set(true); return available(); }).analyze(QUERY);
        assertThat(called).isFalse();
        assertThat(result.ai().state()).isEqualTo(AiState.SKIPPED);
        assertThat(result.ai().reason()).isEqualTo(reason);
        assertThat(result.statistics()).isEqualTo(STATS);
    }
}
