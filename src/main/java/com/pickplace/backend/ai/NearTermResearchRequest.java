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
 * 서울 2026Q2 연구 모델의 원천 코드 요청이다. 값과 범위의 형태만 검사한다.
 * H10·소진공 업종의 공식 대응이나 원본 값의 진위가 확인됐다는 뜻은 아니다.
 */
public record NearTermResearchRequest(String regionCode, String regionCodeSystem, String industryCode,
        String basePeriod, String targetPeriod, LocalDate asOf, String sourceKind, List<Observation> history) {
    private static final Pattern REGION = Pattern.compile("11[0-9]{6}");
    private static final Pattern INDUSTRY = Pattern.compile("CS[0-9]{6}");
    private static final Set<String> REQUIRED_PERIODS = Set.of("2025Q2", "2025Q3", "2025Q4", "2026Q1", "2026Q2");

    public record Observation(String regionCode, String regionCodeSystem, String industryCode,
            String period, LocalDate availableAt, String sourceKind, String sourceDataset,
            BigDecimal salesAmount, Long storeCount, Long openingCount, Long closingCount,
            BigDecimal residentPopulation, BigDecimal workingPopulation,
            BigDecimal floatingPopulation, BigDecimal areaKm2) {
        @Override public String toString() { return "Observation[redacted]"; }
    }

    @Override public String toString() { return "NearTermResearchRequest[redacted]"; }

    public NearTermResearchRequest {
        if (regionCode == null || !REGION.matcher(regionCode).matches()
                || !"seoul-adstrd".equals(regionCodeSystem)
                || industryCode == null || !INDUSTRY.matcher(industryCode).matches())
            throw new IllegalArgumentException("unsupported research source scope");
        if (!"2026Q2".equals(basePeriod) || !Set.of("2026Q3", "2026Q4").contains(targetPeriod))
            throw new IllegalArgumentException("unsupported research period");
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isBefore(endOfQuarter(basePeriod)) || !"official".equals(sourceKind))
            throw new IllegalArgumentException("invalid research source date or kind");
        if (history == null || history.size() != REQUIRED_PERIODS.size())
            throw new IllegalArgumentException("five research quarters required");
        history = List.copyOf(history);
        Set<String> periods = new HashSet<>();
        for (Observation row : history) {
            if (row == null || !regionCode.equals(row.regionCode())
                    || !regionCodeSystem.equals(row.regionCodeSystem())
                    || !industryCode.equals(row.industryCode())
                    || !sourceKind.equals(row.sourceKind())
                    || !REQUIRED_PERIODS.contains(row.period()) || !periods.add(row.period()))
                throw new IllegalArgumentException("research history scope or period mismatch");
            if (row.availableAt() == null || row.availableAt().isBefore(endOfQuarter(row.period()))
                    || row.availableAt().isAfter(asOf)
                    || row.sourceDataset() == null || row.sourceDataset().isBlank()
                    || row.sourceDataset().length() > 200
                    || row.sourceDataset().codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("research history provenance invalid");
            nonnegative(row.salesAmount()); nonnegative(row.residentPopulation());
            nonnegative(row.workingPopulation()); nonnegative(row.floatingPopulation());
            nonnegative(row.storeCount()); nonnegative(row.openingCount()); nonnegative(row.closingCount());
            if (row.areaKm2() != null && row.areaKm2().signum() <= 0)
                throw new IllegalArgumentException("research area invalid");
        }
        if (!periods.equals(REQUIRED_PERIODS)) throw new IllegalArgumentException("research history incomplete");
    }

    private static LocalDate endOfQuarter(String period) {
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Character.digit(period.charAt(5), 10) * 3;
        return YearMonth.of(year, month).atEndOfMonth();
    }

    private static void nonnegative(BigDecimal value) {
        if (value != null && value.signum() < 0) throw new IllegalArgumentException("negative research value");
    }

    private static void nonnegative(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("negative research count");
    }
}
