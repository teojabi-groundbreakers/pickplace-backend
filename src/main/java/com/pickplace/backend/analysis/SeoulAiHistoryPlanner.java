package com.pickplace.backend.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.pickplace.backend.ai.AiPredictionRequest;

/** 승인 근거가 제공된 서울 분기 관측만 AI 요청 후보로 조립한다. DB나 AI 서버를 직접 호출하지 않는다. */
public final class SeoulAiHistoryPlanner {
    private static final Pattern PERIOD = Pattern.compile("[0-9]{4}Q[1-4]");
    private static final BigDecimal SQUARE_METERS_PER_KM2 = new BigDecimal("1000000");

    public enum Metric { GROWTH, RISK, FIT }
    public enum Kind { SALES, STORE_COUNT, CLOSING_COUNT, RESIDENT_POPULATION, AREA_SQUARE_METERS }

    /** 원천별 승인과 공개 시점, 해당 분기 전체의 범위·단위·경계 유효성이다. */
    public record Evidence(String dataset, String version, boolean official, boolean aiApproved,
            LocalDate approvedAt, LocalDate publishedAt, LocalDate availableAt,
            LocalDate validFrom, LocalDate validThrough, boolean unitVerified, boolean boundaryVerified) {}

    /** 서비스 코드와 서울 원천 코드 간 의미 대응의 승인과 유효 기간이다. */
    public record Mapping(String regionCode, String businessTypeCode, String sourceRegionCode,
            String sourceIndustryCode, String version, boolean regionApproved, boolean industryApproved,
            LocalDate approvedAt, LocalDate validFrom, LocalDate validThrough) {}

    public record Value(Kind kind, String period, String sourceRegionCode, String sourceIndustryCode,
            BigDecimal amount, Evidence evidence) {}

    public record Selection(String basePeriod, String targetPeriod, String reason,
            AiPredictionRequest request) {
        public boolean available() { return request != null; }
    }

    /** 서로 다른 지표의 기준 분기는 서로 독립적이다. 각 결과는 별도의 /predict 요청이다. */
    public Map<Metric, Selection> prepare(Mapping mapping, List<Value> values, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(values, "values");
        if (!validMapping(mapping, asOf)) {
            return Map.of(Metric.GROWTH, unavailable("mapping_unverified"),
                    Metric.RISK, unavailable("mapping_unverified"), Metric.FIT, unavailable("mapping_unverified"));
        }
        var byPeriod = new LinkedHashMap<String, Map<Kind, Value>>();
        for (Value value : values) {
            if (value == null || value.kind() == null || !validQuarter(value.period())) continue;
            // 검증 실패한 원천은 null이나 0으로 바꾸지 않고 AI 후보에서 제외한다.
            if (!validValue(value, mapping, asOf)) continue;
            var period = byPeriod.computeIfAbsent(value.period(), ignored -> new LinkedHashMap<>());
            if (period.putIfAbsent(value.kind(), value) != null) {
                throw new IllegalArgumentException("중복된 분기·지표 원천: " + value.period() + "/" + value.kind());
            }
        }
        var periods = byPeriod.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        var result = new LinkedHashMap<Metric, Selection>();
        for (Metric metric : Metric.values()) {
            result.put(metric, choose(metric, periods, byPeriod, mapping, asOf));
        }
        return Map.copyOf(result);
    }

    private static Selection choose(Metric metric, List<String> periods, Map<String, Map<Kind, Value>> values,
            Mapping mapping, LocalDate asOf) {
        for (String base : periods) {
            var current = values.get(base);
            if (!covers(mapping.validFrom(), mapping.validThrough(), base)) continue;
            List<String> required = switch (metric) {
                case FIT -> has(current, Kind.STORE_COUNT, true) && has(current, Kind.RESIDENT_POPULATION, false)
                        && has(current, Kind.AREA_SQUARE_METERS, true) ? List.of(base) : List.of();
                case RISK -> has(current, Kind.STORE_COUNT, true) && has(current, Kind.CLOSING_COUNT, false)
                        && has(current, Kind.SALES, false) && has(values.get(shift(base, -4)), Kind.SALES, true)
                        ? List.of(shift(base, -4), base) : List.of();
                case GROWTH -> growthPeriods(base, values);
            };
            if (required.isEmpty() || required.stream().anyMatch(p -> !covers(mapping.validFrom(), mapping.validThrough(), p))) continue;
            String target = metric == Metric.GROWTH ? shift(base, 1) : null;
            if (metric == Metric.GROWTH && !quarterEnd(target).isAfter(asOf)) {
                return new Selection(base, target, "target_not_future", null);
            }
            var history = new ArrayList<AiPredictionRequest.Observation>();
            for (String period : required) history.add(observation(period, values.get(period), mapping));
            return new Selection(base, target, null, new AiPredictionRequest(mapping.sourceRegionCode(),
                    "seoul-adstrd", mapping.sourceIndustryCode(), base, asOf, true, history));
        }
        return unavailable("insufficient_verified_data");
    }

    private static List<String> growthPeriods(String base, Map<String, Map<Kind, Value>> values) {
        var periods = new ArrayList<String>();
        for (int offset = -4; offset <= 0; offset++) {
            String period = shift(base, offset);
            if (!has(values.get(period), Kind.SALES, false)) return List.of();
            periods.add(period);
        }
        // AI 특징 생성에서 전년·전분기 및 이전 비교 분모는 0보다 커야 한다.
        if (!has(values.get(periods.get(0)), Kind.SALES, true)
                || !has(values.get(periods.get(1)), Kind.SALES, true)
                || !has(values.get(periods.get(3)), Kind.SALES, true)) return List.of();
        return periods;
    }

    private static AiPredictionRequest.Observation observation(String period, Map<Kind, Value> values, Mapping mapping) {
        LocalDate available = quarterEnd(period).isAfter(mapping.approvedAt()) ? quarterEnd(period) : mapping.approvedAt();
        var sources = new java.util.TreeSet<String>();
        for (Value value : values.values()) {
            var proof = value.evidence();
            if (proof.availableAt().isAfter(available)) available = proof.availableAt();
            if (proof.approvedAt().isAfter(available)) available = proof.approvedAt();
            sources.add(proof.dataset() + "@" + proof.version());
        }
        return new AiPredictionRequest.Observation(mapping.sourceRegionCode(), "seoul-adstrd", mapping.sourceIndustryCode(),
                period, available, String.join("+", sources), amount(values, Kind.SALES), integer(values, Kind.STORE_COUNT),
                null, integer(values, Kind.CLOSING_COUNT), amount(values, Kind.RESIDENT_POPULATION), null, null,
                values.containsKey(Kind.AREA_SQUARE_METERS)
                        ? values.get(Kind.AREA_SQUARE_METERS).amount().divide(SQUARE_METERS_PER_KM2) : null);
    }

    private static boolean validValue(Value value, Mapping mapping, LocalDate asOf) {
        Evidence e = value.evidence();
        if (e == null || value.amount() == null || value.amount().signum() < 0 || !e.official() || !e.aiApproved()
                || !e.unitVerified() || !e.boundaryVerified() || e.dataset() == null || e.version() == null
                || e.version().isBlank() || !mapping.sourceRegionCode().equals(value.sourceRegionCode())
                || !expectedDataset(value.kind()).equals(e.dataset())
                || !covers(e.validFrom(), e.validThrough(), value.period())
                || !covers(mapping.validFrom(), mapping.validThrough(), value.period())
                || e.approvedAt() == null || e.publishedAt() == null || e.availableAt() == null
                || e.approvedAt().isAfter(asOf) || e.availableAt().isAfter(asOf)
                || (value.kind() != Kind.AREA_SQUARE_METERS && e.publishedAt().isBefore(quarterEnd(value.period())))
                || e.availableAt().isBefore(e.publishedAt()) || quarterEnd(value.period()).isAfter(asOf)) return false;
        boolean industrySpecific = value.kind() == Kind.SALES || value.kind() == Kind.STORE_COUNT || value.kind() == Kind.CLOSING_COUNT;
        if (industrySpecific ? !mapping.sourceIndustryCode().equals(value.sourceIndustryCode())
                : value.sourceIndustryCode() != null) return false;
        if (value.kind() == Kind.STORE_COUNT || value.kind() == Kind.CLOSING_COUNT) {
            try { value.amount().longValueExact(); } catch (ArithmeticException invalid) { return false; }
        }
        return true;
    }

    private static String expectedDataset(Kind kind) {
        return switch (kind) {
            case SALES -> "OA-22175";
            case STORE_COUNT, CLOSING_COUNT -> "OA-22172";
            case RESIDENT_POPULATION -> "OA-22183";
            case AREA_SQUARE_METERS -> "OA-22160";
        };
    }

    private static boolean validMapping(Mapping m, LocalDate asOf) {
        return m != null && m.regionCode() != null && m.regionCode().matches("[0-9]{10}")
                && m.businessTypeCode() != null && m.businessTypeCode().matches("[A-Z][0-9]{5}")
                && m.sourceRegionCode() != null && m.sourceRegionCode().matches("[0-9]{8}")
                && m.sourceIndustryCode() != null && m.sourceIndustryCode().matches("CS[0-9]{6}")
                && m.version() != null && !m.version().isBlank() && m.regionApproved() && m.industryApproved()
                && m.approvedAt() != null && !m.approvedAt().isAfter(asOf)
                && m.validFrom() != null && m.validThrough() != null && !m.validFrom().isAfter(m.validThrough());
    }

    private static boolean has(Map<Kind, Value> values, Kind kind, boolean positive) {
        return values != null && values.containsKey(kind) && (!positive || values.get(kind).amount().signum() > 0);
    }
    private static BigDecimal amount(Map<Kind, Value> values, Kind kind) {
        return values.containsKey(kind) ? values.get(kind).amount() : null;
    }
    private static Long integer(Map<Kind, Value> values, Kind kind) {
        return values.containsKey(kind) ? values.get(kind).amount().longValueExact() : null;
    }
    private static Selection unavailable(String reason) { return new Selection(null, null, reason, null); }
    private static boolean validQuarter(String quarter) {
        return quarter != null && PERIOD.matcher(quarter).matches() && Integer.parseInt(quarter.substring(0, 4)) > 0;
    }
    private static boolean covers(LocalDate from, LocalDate through, String quarter) {
        if (from == null || through == null || from.isAfter(through)) return false;
        int year = Integer.parseInt(quarter.substring(0, 4));
        int startMonth = (quarter.charAt(5) - '1') * 3 + 1;
        return !from.isAfter(LocalDate.of(year, startMonth, 1)) && !through.isBefore(quarterEnd(quarter));
    }
    private static LocalDate quarterEnd(String quarter) {
        return YearMonth.of(Integer.parseInt(quarter.substring(0, 4)), (quarter.charAt(5) - '0') * 3).atEndOfMonth();
    }
    private static String shift(String quarter, int offset) {
        int index = Integer.parseInt(quarter.substring(0, 4)) * 4 + quarter.charAt(5) - '1' + offset;
        if (index < 4 || index >= 40000) throw new IllegalArgumentException("분기 범위를 벗어났습니다");
        return (index / 4) + "Q" + (index % 4 + 1);
    }
}
