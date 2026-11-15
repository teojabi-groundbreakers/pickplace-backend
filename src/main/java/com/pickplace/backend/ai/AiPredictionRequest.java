package com.pickplace.backend.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 호출자가 검토한 입력이다. 이 레코드는 값의 일관성을 확인하며 외부 출처 자체는 검증하지 않는다.
 * scopeAndSourceVerified는 호출자의 검증 완료 선언이며 지역·업종 코드 대응을 여기서 추정하지 않는다.
 */
public record AiPredictionRequest(String regionCode, String regionCodeSystem, String industryCode,
        String basePeriod, LocalDate asOf, boolean scopeAndSourceVerified, List<Observation> history) {
    private static final Pattern CODE = Pattern.compile("[0-9]{6,12}");
    private static final Pattern QUARTER = Pattern.compile("[0-9]{4}Q[1-4]");

    public record Observation(String regionCode, String regionCodeSystem, String industryCode,
            String period, LocalDate availableAt, String sourceDataset, BigDecimal salesAmount,
            Long storeCount, Long openingCount, Long closingCount, BigDecimal residentPopulation,
            BigDecimal workingPopulation, BigDecimal floatingPopulation, BigDecimal areaKm2) {
        @Override public String toString() { return "Observation[redacted]"; }
    }

    @Override public String toString() { return "AiPredictionRequest[redacted]"; }

    public AiPredictionRequest {
        if (!scopeAndSourceVerified) throw new IllegalArgumentException("source and mapping must be verified");
        validateCode(regionCode, regionCodeSystem, industryCode);
        int base = quarterIndex(basePeriod);
        Objects.requireNonNull(asOf, "asOf");
        if (endOfQuarter(basePeriod).isAfter(asOf)) throw new IllegalArgumentException("base period incomplete");
        if (history == null || history.isEmpty() || history.size() > 128) throw new IllegalArgumentException("history size");
        history = List.copyOf(history);
        Set<String> periods = new HashSet<>();
        boolean foundBase = false;
        for (Observation row : history) {
            if (row == null || !regionCode.equals(row.regionCode()) || !regionCodeSystem.equals(row.regionCodeSystem())
                    || !industryCode.equals(row.industryCode())) throw new IllegalArgumentException("history scope");
            int current = quarterIndex(row.period());
            if (current > base || !periods.add(row.period())) throw new IllegalArgumentException("history period");
            if (row.period().equals(basePeriod)) foundBase = true;
            if (row.availableAt() == null || row.availableAt().isAfter(asOf)
                    || row.availableAt().isBefore(endOfQuarter(row.period()))) throw new IllegalArgumentException("history availability");
            if (row.sourceDataset() == null || row.sourceDataset().isBlank() || row.sourceDataset().length() > 200)
                throw new IllegalArgumentException("history source");
            nonnegative(row.salesAmount()); nonnegative(row.residentPopulation()); nonnegative(row.workingPopulation());
            nonnegative(row.floatingPopulation()); nonnegative(row.storeCount()); nonnegative(row.openingCount()); nonnegative(row.closingCount());
            if (row.areaKm2() != null && row.areaKm2().signum() <= 0) throw new IllegalArgumentException("area");
        }
        if (!foundBase) throw new IllegalArgumentException("base period missing");
    }

    private static void validateCode(String region, String system, String industry) {
        if (region == null || !CODE.matcher(region).matches() || system == null || system.isBlank() || system.length() > 80
                || industry == null || industry.isBlank() || industry.length() > 80) throw new IllegalArgumentException("invalid source scope");
    }
    private static int quarterIndex(String value) {
        if (value == null || !QUARTER.matcher(value).matches()) throw new IllegalArgumentException("invalid quarter");
        int year = Integer.parseInt(value.substring(0, 4));
        if (year < 1) throw new IllegalArgumentException("invalid quarter");
        return year * 4 + Character.digit(value.charAt(5), 10) - 1;
    }
    private static LocalDate endOfQuarter(String quarter) {
        int year = Integer.parseInt(quarter.substring(0, 4));
        int month = Character.digit(quarter.charAt(5), 10) * 3;
        return YearMonth.of(year, month).atEndOfMonth();
    }
    private static void nonnegative(BigDecimal value) {
        if (value != null && value.signum() < 0) throw new IllegalArgumentException("negative value");
    }
    private static void nonnegative(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("negative value");
    }
}
