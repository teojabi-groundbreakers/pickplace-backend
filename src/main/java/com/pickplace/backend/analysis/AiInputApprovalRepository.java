package com.pickplace.backend.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 현재 게시된 지역·업종에 대한 별도 AI 사용 심사만 조회한다. */
@Repository
public class AiInputApprovalRepository {
    private final JdbcTemplate jdbc;

    public AiInputApprovalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public enum IndustryMatch { EXACT, PARTIAL }

    public record ApprovedScope(long reviewId, SeoulAiHistoryPlanner.Mapping mapping,
            String sourceIndustryName, IndustryMatch industryMatch) {}

    public Optional<ApprovedScope> findScope(String regionCode, String industryCode, LocalDate asOf) {
        if (regionCode == null || !regionCode.matches("[0-9]{10}") || industryCode == null
                || !industryCode.matches("[A-Z][0-9]{5}") || asOf == null)
            throw new IllegalArgumentException("AI 대응 조회 범위 오류");
        LocalDateTime cutoff = LocalDateTime.ofInstant(asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        var rows = jdbc.query("""
                SELECT a.review_id,a.region_code,a.business_type_code,a.source_region_code,a.source_industry_code,
                       a.review_version,a.approved_at,a.valid_from,a.valid_through,a.source_industry_name,a.industry_match
                FROM ai_input_scope_review a
                JOIN region_publication p ON p.release_id=a.region_release_id AND p.publication_key='NATIONWIDE_SELECTION'
                JOIN region_release r ON r.release_id=p.release_id AND r.status='APPROVED'
                JOIN region_navigation n ON n.release_id=a.region_release_id AND n.region_code=a.region_code
                JOIN national_industry_release c ON c.classification_id=a.classification_id AND c.status='APPROVED'
                JOIN national_industry_code i ON i.classification_id=a.classification_id AND i.industry_code=a.business_type_code
                WHERE a.region_code=? AND a.business_type_code=? AND a.status='APPROVED' AND a.approved_at < ?
                  AND n.selectable=TRUE AND n.selection_role='LOCALITY' AND i.is_supported=TRUE
                  AND a.classification_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(r.validation_report,'$.classificationId')) AS UNSIGNED)
                """, (rs, index) -> new ApprovedScope(rs.getLong(1), new SeoulAiHistoryPlanner.Mapping(
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), true, true,
                rs.getObject(7, LocalDateTime.class).toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class)),
                rs.getString(10), IndustryMatch.valueOf(rs.getString(11))), regionCode, industryCode, cutoff);
        // 복수 승인본의 적용 범위가 겹칠 수 있으므로 임의로 하나를 고르지 않는다.
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    /** 배치의 내부 게시와 별도로 승인된 지표만 반환한다. 모호한 중복 심사는 보류한다. */
    public Map<Long, Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence>> findMetricEvidence(long scopeReviewId, LocalDate asOf) {
        if (scopeReviewId <= 0 || asOf == null) throw new IllegalArgumentException("AI 지표 심사 조회 오류");
        var cutoff = LocalDateTime.ofInstant(asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        var rows = jdbc.query("""
                SELECT m.batch_id,m.metric_kind,m.valid_from,m.valid_through,m.approved_at,
                       b.approved_at,b.collected_at,MAX(p.published_at) AS latest_publication
                FROM ai_input_metric_review m
                JOIN ai_input_scope_review s ON s.review_id=m.scope_review_id AND s.status='APPROVED' AND s.approved_at < ?
                JOIN regional_statistic_batch b ON b.batch_id=m.batch_id AND b.status='APPROVED'
                  AND b.mapping_release_id=s.region_release_id
                  AND b.source_system='SEOUL_COMMERCIAL' AND b.period_type='QUARTER' AND b.approved_at < ?
                JOIN regional_statistic_publication p ON p.batch_id=b.batch_id AND p.source_system=b.source_system
                  AND p.dataset_code=b.dataset_code AND p.period_type=b.period_type AND p.published_at < ?
                WHERE m.scope_review_id=? AND m.status='APPROVED' AND m.approved_at < ? AND m.boundary_verified=TRUE
                  AND ((m.metric_kind='SALES' AND b.dataset_code='SALES')
                    OR (m.metric_kind IN ('STORE_COUNT','CLOSING_COUNT') AND b.dataset_code='STORE')
                    OR (m.metric_kind='RESIDENT_POPULATION' AND b.dataset_code='RESIDENT'))
                GROUP BY m.review_id,m.batch_id,m.metric_kind,m.valid_from,m.valid_through,m.approved_at,b.approved_at,b.collected_at
                """, (rs, index) -> {
            long batchId = rs.getLong(1);
            var kind = SeoulAiHistoryPlanner.Kind.valueOf(rs.getString(2));
            var reviewAt = seoulDate(rs.getObject(5, LocalDateTime.class));
            var batchAt = seoulDate(rs.getObject(6, LocalDateTime.class));
            var collectedAt = seoulDate(rs.getObject(7, LocalDateTime.class));
            var publishedAt = seoulDate(rs.getObject(8, LocalDateTime.class));
            var availableAt = collectedAt.isAfter(publishedAt) ? collectedAt : publishedAt;
            String dataset = switch (kind) {
                case SALES -> "OA-22175";
                case STORE_COUNT, CLOSING_COUNT -> "OA-22172";
                case RESIDENT_POPULATION -> "OA-22183";
                default -> throw new IllegalStateException("허용되지 않은 지표");
            };
            return new MetricEvidence(batchId, kind, new SeoulAiHistoryPlanner.Evidence(dataset, "batch:" + batchId,
                    true, true, reviewAt.isAfter(batchAt) ? reviewAt : batchAt, publishedAt, availableAt,
                    rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), true, true));
        }, cutoff, cutoff, cutoff, scopeReviewId, cutoff);
        var result = new HashMap<Long, Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence>>();
        var duplicates = new HashSet<String>();
        for (var row : rows) {
            String key = row.batchId() + ":" + row.kind();
            var metrics = result.computeIfAbsent(row.batchId(), ignored -> new HashMap<>());
            if (duplicates.contains(key)) continue;
            if (metrics.putIfAbsent(row.kind(), row.evidence()) != null) {
                metrics.remove(row.kind());
                duplicates.add(key);
            }
        }
        var immutable = new HashMap<Long, Map<SeoulAiHistoryPlanner.Kind, SeoulAiHistoryPlanner.Evidence>>();
        result.forEach((key, value) -> immutable.put(key, Map.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private record MetricEvidence(long batchId, SeoulAiHistoryPlanner.Kind kind, SeoulAiHistoryPlanner.Evidence evidence) {}

    /** 분기 경계가 명시적으로 확인·승인된 면적만 반환한다. 중복 승인 분기는 제외한다. */
    public List<SeoulAiHistoryPlanner.Value> findAreaEvidence(ApprovedScope scope, String fromQuarter, String toQuarter, LocalDate asOf) {
        if (scope == null || asOf == null || fromQuarter == null || !fromQuarter.matches("[0-9]{4}[1-4]")
                || toQuarter == null || !toQuarter.matches("[0-9]{4}[1-4]")) throw new IllegalArgumentException("면적 조회 분기 오류");
        int first = quarterIndex(fromQuarter), last = quarterIndex(toQuarter);
        if (first < 4 || first > last || last - first >= 128) throw new IllegalArgumentException("면적 조회 범위 오류");
        var cutoff = LocalDateTime.ofInstant(asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        var areas = jdbc.query("""
                SELECT source_version,area_square_meters,valid_from,valid_through,source_published_at,collected_at,approved_at
                FROM ai_input_area_review
                WHERE scope_review_id=? AND status='APPROVED' AND boundary_verified=TRUE
                  AND source_dataset='OA-22160' AND approved_at < ? AND source_published_at < ? AND collected_at < ?
                """, (rs, index) -> new Area(rs.getString(1), rs.getBigDecimal(2), rs.getObject(3, LocalDate.class),
                rs.getObject(4, LocalDate.class), seoulDate(rs.getObject(5, LocalDateTime.class)),
                seoulDate(rs.getObject(6, LocalDateTime.class)), seoulDate(rs.getObject(7, LocalDateTime.class))),
                scope.reviewId(), cutoff, cutoff, cutoff);
        var byPeriod = new HashMap<String, SeoulAiHistoryPlanner.Value>();
        var duplicates = new HashSet<String>();
        for (int q = first; q <= last; q++) {
            int year = q / 4, number = q % 4 + 1;
            String period = year + "Q" + number;
            var start = LocalDate.of(year, (number - 1) * 3 + 1, 1);
            var end = YearMonth.of(year, number * 3).atEndOfMonth();
            if (end.isAfter(asOf)) continue;
            for (var area : areas) {
                if (area.from().isAfter(start) || area.through().isBefore(end) || duplicates.contains(period)) continue;
                var available = area.collectedAt().isAfter(area.publishedAt()) ? area.collectedAt() : area.publishedAt();
                var proof = new SeoulAiHistoryPlanner.Evidence("OA-22160", area.version(), true, true,
                        area.approvedAt(), area.publishedAt(), available, area.from(), area.through(), true, true);
                var value = new SeoulAiHistoryPlanner.Value(SeoulAiHistoryPlanner.Kind.AREA_SQUARE_METERS,
                        period, scope.mapping().sourceRegionCode(), null, area.squareMeters(), proof);
                if (byPeriod.putIfAbsent(period, value) != null) {
                    byPeriod.remove(period);
                    duplicates.add(period);
                }
            }
        }
        return List.copyOf(byPeriod.values());
    }

    private static int quarterIndex(String period) {
        return Integer.parseInt(period.substring(0, 4)) * 4 + period.charAt(4) - '1';
    }
    private record Area(String version, java.math.BigDecimal squareMeters, LocalDate from, LocalDate through,
            LocalDate publishedAt, LocalDate collectedAt, LocalDate approvedAt) {}
    private static LocalDate seoulDate(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }
}
