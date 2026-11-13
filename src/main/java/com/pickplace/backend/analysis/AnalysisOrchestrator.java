package com.pickplace.backend.analysis;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pickplace.backend.ai.AiPredictionRequest;
import com.pickplace.backend.ai.AiPredictionResult;
import com.pickplace.backend.summary.MetricDecision;

/**
 * 게시 통계와 AI 결과를 조합한다. Spring Bean은 AnalysisConfiguration에서 연결한다.
 * 조회 포트는 읽기 트랜잭션 안에서 불변 스냅샷을 완성하고 종료한 뒤 반환해야 한다.
 * 코드 대응·공개 근거·값은 임의로 추정하지 않는다.
 */
public final class AnalysisOrchestrator {
    private static final Pattern REGION = Pattern.compile("[0-9]{10}");
    private static final Pattern INDUSTRY = Pattern.compile("[A-Z][0-9]{5}");
    private static final Pattern PERIOD = Pattern.compile("[0-9]{4}Q[1-4]");

    private final SnapshotReader reader;
    private final Predictor predictor;
    private final Clock clock;

    public AnalysisOrchestrator(SnapshotReader reader, Predictor predictor, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.predictor = Objects.requireNonNull(predictor);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 이 포트는 DB 읽기 트랜잭션을 완전히 종료한 뒤 결과를 반환해야 한다. */
    @FunctionalInterface
    public interface SnapshotReader {
        Snapshot read(Query query);
    }

    /** 조회 트랜잭션이 종료된 뒤에만 FastAPI를 호출할 수 있다. */
    @FunctionalInterface
    public interface Predictor {
        AiPredictionResult predict(AiPredictionRequest request);
    }

    public record Query(String regionCode, String businessTypeCode, String basePeriod, LocalDate asOf) {
        public Query {
            if (regionCode == null || !REGION.matcher(regionCode).matches()) throw new IllegalArgumentException("invalid region code");
            if (businessTypeCode == null || !INDUSTRY.matcher(businessTypeCode).matches()) throw new IllegalArgumentException("invalid business type");
            quarterIndex(basePeriod);
            Objects.requireNonNull(asOf, "asOf");
            if (quarterEnd(basePeriod).isAfter(asOf)) throw new IllegalArgumentException("incomplete base period");
        }
    }

    /** 지역·업종 코드 대응은 각각 승인되어야 하며 유효 기간의 양끝 날짜를 포함한다. */
    public record ScopeMapping(String selectedRegionCode, String selectedIndustryCode,
            String sourceRegionCode, String sourceRegionCodeSystem, String sourceIndustryCode,
            String version, boolean regionApproved, boolean industryApproved,
            LocalDate approvedAt, LocalDate validFrom, LocalDate validThrough) {}

    /** 각 선택 지표에는 출처·적용 범위·공개 시점·단위의 개별 근거가 필요하다. */
    public record SupplementalEvidence(String sourceDataset, boolean official, boolean approved,
            LocalDate approvedAt, boolean published, LocalDate publishedAt, LocalDate availableAt,
            boolean scopeVerified, boolean unitVerified) {}

    public record PublishedRow(AiPredictionRequest.Observation observation, boolean official,
            boolean approved, LocalDate approvedAt, boolean published, LocalDate publishedAt, boolean salesUnitVerified,
            Map<String, SupplementalEvidence> supplements) {
        public PublishedRow {
            // 잘못된 항목도 뒤에서 검증할 수 있게 보존한다. Map.copyOf는 null 값에서 즉시 예외를 낸다.
            supplements = supplements == null ? Map.of() : java.util.Collections.unmodifiableMap(new HashMap<>(supplements));
        }
    }

    /** 기존 통계는 AI 결과의 성공 여부와 관계없이 보존한다. */
    public record Statistics(String version, Map<String, MetricDecision> metrics) {
        public Statistics {
            if (version == null || version.isBlank()) throw new IllegalArgumentException("statistics version required");
            metrics = Map.copyOf(Objects.requireNonNull(metrics));
        }
    }

    public record Snapshot(Statistics statistics, ScopeMapping mapping, List<PublishedRow> history) {
        public Snapshot {
            Objects.requireNonNull(statistics, "statistics");
            // null 이력 행은 신뢰 가능한 통계를 보존한 뒤 아래 검증 단계에서 거부한다.
            history = history == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    public enum AiState { SKIPPED, AVAILABLE, FAILED }

    /** SKIPPED는 AI 미호출, FAILED는 호출 후 실패 또는 AI 설정 오류를 뜻한다. */
    public record AiOutcome(AiState state, AiPredictionResult prediction, String reason) {}

    public record Result(Query request, Statistics statistics, AiOutcome ai) {}

    public Result analyze(Query query) {
        Objects.requireNonNull(query, "query");
        if (query.asOf().isAfter(LocalDate.now(clock))) throw new IllegalArgumentException("analysis date is in the future");
        // 이후 AI 검증이나 호출에 실패해도 이미 조회한 통계 스냅샷을 보존한다.
        Snapshot snapshot = Objects.requireNonNull(reader.read(query), "snapshot");
        String mappingIssue = validateMapping(query, snapshot.mapping());
        if (mappingIssue != null) return unavailable(query, snapshot.statistics(), mappingIssue);
        var rows = selectHistory(query, snapshot);
        if (rows.reason() != null) return unavailable(query, snapshot.statistics(), rows.reason());
        // 외부 AI 요청을 보내는 동안 DB 트랜잭션을 열어 두지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return unavailable(query, snapshot.statistics(), "ai_call_forbidden_in_transaction");
        }
        AiPredictionRequest request;
        try {
            var m = snapshot.mapping();
            request = new AiPredictionRequest(m.sourceRegionCode(), m.sourceRegionCodeSystem(),
                    m.sourceIndustryCode(), query.basePeriod(), query.asOf(), true, rows.observations());
        } catch (IllegalArgumentException | NullPointerException failure) {
            return unavailable(query, snapshot.statistics(), "invalid_reviewed_history");
        }
        try {
            AiPredictionResult response = predictor.predict(request);
            if (response == null || response.status() == null
                    || (response.status() == AiPredictionResult.Status.AVAILABLE && response.response() == null)
                    || (response.status() != AiPredictionResult.Status.AVAILABLE && response.response() != null)) {
                return new Result(query, snapshot.statistics(),
                        new AiOutcome(AiState.FAILED, null, "invalid_ai_response"));
            }
            AiState state = response.status() == AiPredictionResult.Status.AVAILABLE
                    ? AiState.AVAILABLE : AiState.FAILED;
            return new Result(query, snapshot.statistics(), new AiOutcome(state, response, response.reason()));
        } catch (RuntimeException failure) {
            // 통신·구현 오류가 발생해도 별도로 확보한 통계는 버리지 않는다.
            return new Result(query, snapshot.statistics(), new AiOutcome(AiState.FAILED, null, "ai_request_failed"));
        }
    }

    private static Result unavailable(Query query, Statistics statistics, String reason) {
        return new Result(query, statistics, new AiOutcome(AiState.SKIPPED, null, reason));
    }

    // 코드 형식만으로는 부족하므로 원천 코드 대응의 승인과 시점 근거를 따로 확인한다.
    private static String validateMapping(Query query, ScopeMapping mapping) {
        if (mapping == null || !mapping.regionApproved() || !mapping.industryApproved()) return "mapping_unverified";
        if (!query.regionCode().equals(mapping.selectedRegionCode())
                || !query.businessTypeCode().equals(mapping.selectedIndustryCode())) return "mapping_scope_mismatch";
        if (mapping.sourceRegionCode() == null || !mapping.sourceRegionCode().matches("[0-9]{6,12}")
                || mapping.sourceRegionCodeSystem() == null || mapping.sourceRegionCodeSystem().isBlank()
                || mapping.sourceIndustryCode() == null || mapping.sourceIndustryCode().isBlank()
                || mapping.version() == null || mapping.version().isBlank()) return "mapping_unverified";
        if (mapping.approvedAt() == null || mapping.approvedAt().isAfter(query.asOf())
                || mapping.validFrom() == null || mapping.validThrough() == null
                || mapping.validFrom().isAfter(mapping.validThrough())) return "mapping_not_available";
        return null;
    }

    private record HistorySelection(List<AiPredictionRequest.Observation> observations, String reason) {}

    private static HistorySelection selectHistory(Query query, Snapshot snapshot) {
        if (snapshot.history().size() > 128) return new HistorySelection(null, "history_out_of_range");
        if (snapshot.history().isEmpty()) return new HistorySelection(null, "insufficient_history");
        Map<String, PublishedRow> byPeriod = new HashMap<>();
        int base = quarterIndex(query.basePeriod());
        for (PublishedRow row : snapshot.history()) {
            if (row == null || row.observation() == null) return new HistorySelection(null, "invalid_reviewed_history");
            AiPredictionRequest.Observation value = row.observation();
            int index;
            try { index = quarterIndex(value.period()); }
            catch (IllegalArgumentException failure) { return new HistorySelection(null, "invalid_reviewed_history"); }
            if (index > base || byPeriod.putIfAbsent(value.period(), row) != null)
                return new HistorySelection(null, "invalid_reviewed_history");
        }
        if (!byPeriod.containsKey(query.basePeriod())) return new HistorySelection(null, "missing_base_period");
        var scope = snapshot.mapping();
        var observations = new ArrayList<AiPredictionRequest.Observation>();
        boolean hasSales = false;
        for (String period : byPeriod.keySet().stream().sorted().toList()) {
            PublishedRow row = byPeriod.get(period);
            AiPredictionRequest.Observation item = row.observation();
            if (!scope.sourceRegionCode().equals(item.regionCode())
                    || !scope.sourceRegionCodeSystem().equals(item.regionCodeSystem())
                    || !scope.sourceIndustryCode().equals(item.industryCode()))
                return new HistorySelection(null, "history_scope_mismatch");
            if (scope.validFrom().isAfter(quarterStart(period))
                    || scope.validThrough().isBefore(quarterEnd(period)))
                return new HistorySelection(null, "mapping_period_unverified");
            // 분석일 이후의 승인이나 공개를 과거 분석일에 소급 적용하지 않는다.
            if (!row.official() || !row.approved()) return new HistorySelection(null, "source_not_approved");
            if (row.approvedAt() == null || row.approvedAt().isAfter(query.asOf()))
                return new HistorySelection(null, "source_approval_time_unverified");
            if (!row.published() || row.publishedAt() == null || row.publishedAt().isAfter(query.asOf())
                    || item.availableAt() == null || item.availableAt().isAfter(query.asOf())
                    || item.availableAt().isBefore(row.publishedAt())
                    || row.publishedAt().isBefore(quarterEnd(period)))
                return new HistorySelection(null, "history_publication_unverified");
            if (item.salesAmount() != null && !row.salesUnitVerified())
                return new HistorySelection(null, "sales_unit_unverified");
            if (item.salesAmount() != null && item.salesAmount().signum() < 0)
                return new HistorySelection(null, "invalid_reviewed_history");
            hasSales |= item.salesAmount() != null;
            var sources = new java.util.LinkedHashSet<String>();
            sources.add(item.sourceDataset());
            // AI 입력의 available_at은 이력·코드 대응·포함된 지표의 가용 시점 중 가장 늦은 날짜다.
            LocalDate available = item.availableAt().isAfter(row.approvedAt()) ? item.availableAt() : row.approvedAt();
            if (scope.approvedAt().isAfter(available)) available = scope.approvedAt();
            var verified = new java.util.HashSet<String>();
            var present = Map.of(
                    "store_count", item.storeCount() != null,
                    "opening_count", item.openingCount() != null,
                    "closing_count", item.closingCount() != null,
                    "resident_population", item.residentPopulation() != null,
                    "working_population", item.workingPopulation() != null,
                    "floating_population", item.floatingPopulation() != null,
                    "area_km2", item.areaKm2() != null);
            // 근거가 부족한 보조 지표만 제외하고 승인된 매출 이력은 유지한다.
            for (String name : present.keySet().stream().sorted().toList()) {
                SupplementalEvidence proof = row.supplements().get(name);
                if (!present.get(name) || !validSupplement(proof, period, query.asOf())) continue;
                verified.add(name);
                sources.add(proof.sourceDataset());
                if (proof.availableAt().isAfter(available)) available = proof.availableAt();
                if (proof.approvedAt().isAfter(available)) available = proof.approvedAt();
            }
            if (sources.contains(null)) return new HistorySelection(null, "invalid_reviewed_history");
            String dataset = String.join("+", sources);
            observations.add(new AiPredictionRequest.Observation(item.regionCode(), item.regionCodeSystem(),
                    item.industryCode(), period, available, dataset, item.salesAmount(),
                    verified.contains("store_count") ? item.storeCount() : null,
                    verified.contains("opening_count") ? item.openingCount() : null,
                    verified.contains("closing_count") ? item.closingCount() : null,
                    verified.contains("resident_population") ? item.residentPopulation() : null,
                    verified.contains("working_population") ? item.workingPopulation() : null,
                    verified.contains("floating_population") ? item.floatingPopulation() : null,
                    verified.contains("area_km2") ? item.areaKm2() : null));
        }
        if (!hasSales) return new HistorySelection(null, "missing_sales");
        return new HistorySelection(List.copyOf(observations), null);
    }

    private static boolean validSupplement(SupplementalEvidence proof, String period, LocalDate asOf) {
        return proof != null && proof.official() && proof.approved() && proof.published()
                && proof.approvedAt() != null && !proof.approvedAt().isAfter(asOf)
                && proof.scopeVerified() && proof.unitVerified()
                && proof.sourceDataset() != null && !proof.sourceDataset().isBlank()
                && proof.publishedAt() != null && !proof.publishedAt().isBefore(quarterEnd(period))
                && proof.availableAt() != null && !proof.availableAt().isBefore(proof.publishedAt())
                && !proof.availableAt().isAfter(asOf);
    }

    private static int quarterIndex(String period) {
        if (period == null || !PERIOD.matcher(period).matches()) throw new IllegalArgumentException("invalid quarter");
        int year = Integer.parseInt(period.substring(0, 4));
        if (year < 2) throw new IllegalArgumentException("invalid quarter year");
        return year * 4 + period.charAt(5) - '1';
    }

    private static LocalDate quarterStart(String period) {
        return YearMonth.of(Integer.parseInt(period.substring(0, 4)), (period.charAt(5) - '1') * 3 + 1).atDay(1);
    }

    private static LocalDate quarterEnd(String period) {
        return YearMonth.of(Integer.parseInt(period.substring(0, 4)), (period.charAt(5) - '0') * 3).atEndOfMonth();
    }
}
