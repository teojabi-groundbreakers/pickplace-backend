package com.pickplace.backend.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 서울 상세 원천의 게시 기록을 읽는다. 조회 결과 자체는 AI 사용 승인 근거가 아니다. */
@Repository
public class SeoulPublishedStatisticsRepository {
    private final JdbcTemplate jdbc;

    public SeoulPublishedStatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(String dataset, String quarter, long batchId, long mappingReleaseId,
            String sourceRegionCode, String regionCode, String sourceIndustryCode,
            String rowStatus, String mappingStatus, String temporalStatus, String reason,
            String metrics, String definition, Instant approvedAt, Instant publishedAt, Instant collectedAt) {}

    /** 승인된 지역·업종 코드의 원천 행만 조회한다. 호출자는 대응 승인을 별도로 확인해야 한다. */
    public List<Row> find(String regionCode, String sourceRegionCode, String sourceIndustryCode,
            String fromQuarter, String toQuarter, LocalDate asOf) {
        if (regionCode == null || !regionCode.matches("[0-9]{10}")
                || sourceRegionCode == null || !sourceRegionCode.matches("[0-9]{8}")
                || sourceIndustryCode == null || !sourceIndustryCode.matches("CS[0-9]{6}")
                || fromQuarter == null || !fromQuarter.matches("[0-9]{4}[1-4]")
                || toQuarter == null || !toQuarter.matches("[0-9]{4}[1-4]")
                || fromQuarter.compareTo(toQuarter) > 0 || asOf == null) {
            throw new IllegalArgumentException("서울 상세 통계 조회 범위가 올바르지 않습니다");
        }
        // DB의 DATETIME은 UTC로 저장된다. JDBC 세션 시간대에 따라 날짜 경계가 바뀌지 않게 UTC 값으로 비교한다.
        var cutoff = LocalDateTime.ofInstant(asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        return jdbc.query("""
                SELECT p.dataset_code,p.period_key,p.batch_id,b.mapping_release_id,
                       r.source_region_code,r.region_code,r.source_industry_code,
                       r.row_status,r.mapping_status,r.temporal_status,r.reason_code,
                       r.metrics,b.definition_json,b.approved_at,p.published_at,b.collected_at
                FROM regional_statistic_publication p
                JOIN regional_statistic_batch b ON b.batch_id=p.batch_id
                    AND b.source_system=p.source_system AND b.dataset_code=p.dataset_code AND b.period_type=p.period_type
                JOIN regional_statistic_row r ON r.batch_id=p.batch_id AND r.period_key=p.period_key
                WHERE p.source_system='SEOUL_COMMERCIAL' AND p.dataset_code IN ('SALES','STORE','RESIDENT')
                  AND p.period_type='QUARTER' AND b.status='APPROVED'
                  AND b.approved_at < ? AND p.published_at < ?
                  AND p.period_key BETWEEN ? AND ? AND r.region_code=?
                  AND r.source_region_code=?
                  AND ((p.dataset_code IN ('SALES','STORE') AND b.industry_scheme='SEOUL_CS100' AND r.source_industry_code=?)
                    OR (p.dataset_code='RESIDENT' AND b.industry_scheme='NONE' AND r.source_industry_code=''))
                ORDER BY p.period_key,p.dataset_code,r.source_region_code
                """, (rs, rowNum) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), instant(rs.getObject(14, LocalDateTime.class)),
                instant(rs.getObject(15, LocalDateTime.class)), instant(rs.getObject(16, LocalDateTime.class))),
                cutoff, cutoff, fromQuarter, toQuarter, regionCode, sourceRegionCode, sourceIndustryCode);
    }

    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
