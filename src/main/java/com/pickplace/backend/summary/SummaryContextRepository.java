package com.pickplace.backend.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 조회 기준과 출처만 읽는다. T17의 인구/집계 조회와 달리 사실 테이블을 스캔하지 않는다. */
@Repository
public class SummaryContextRepository {
    private final JdbcTemplate jdbc;
    public SummaryContextRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> localityName(long releaseId, String code) {
        return jdbc.queryForList("""
                SELECT n.display_name FROM region_navigation n JOIN official_region_node o
                    ON o.release_id=n.release_id AND o.region_code=n.region_code
                WHERE n.release_id=? AND n.region_code=? AND n.selection_role='LOCALITY'
                    AND n.selectable=TRUE AND n.review_status='VERIFIED'
                    AND o.official_status='CURRENT' AND o.date_quality_status='VALID'
                """, String.class, releaseId, code).stream().findFirst();
    }
    public Optional<Industry> industry(long classificationId, String code) {
        return jdbc.query("""
                SELECT display_name,is_supported,support_release_status FROM national_industry_code
                WHERE classification_id=? AND industry_code=? AND industry_level='SMALL'
                """, (rs, n) -> new Industry(rs.getString(1), rs.getBoolean(2), rs.getString(3)), classificationId, code).stream().findFirst();
    }
    public Optional<DefaultSummary> defaultSummary() {
        // LEFT JOIN을 유지해야 포인터의 깨진 참조를 정상 미게시(503)와 구분할 수 있다.
        return jdbc.query("""
                SELECT p.summary_release_id,p.reference_month,p.revision,p.published_at,
                    s.status,s.reference_month AS summary_month,s.mapping_release_id,s.classification_id,
                    s.population_batch_id,s.store_batch_id,r.status AS mapping_status,r.validation_report,
                    c.status AS classification_status
                FROM national_summary_publication p
                LEFT JOIN national_summary_release s ON s.summary_release_id=p.summary_release_id
                LEFT JOIN region_release r ON r.release_id=s.mapping_release_id
                LEFT JOIN national_industry_release c ON c.classification_id=s.classification_id
                WHERE p.publication_key='DEFAULT'
                """, (rs, n) -> new DefaultSummary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, LocalDateTime.class),
                    rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                    rs.getString(11), rs.getString(12), rs.getString(13))).stream().findFirst();
    }
    public Optional<BatchSource> source(long batchId) {
        return jdbc.query("""
                SELECT b.batch_id,b.dataset_code,b.source_system,b.source_period_key,b.source_reference_on,b.source_label,
                    b.source_region_version,b.collected_at,b.status,b.mapping_release_id,b.classification_id,
                    b.data_version,a.sha256,a.artifact_kind,a.collected_at AS artifact_collected_at
                FROM national_import_batch b LEFT JOIN national_source_artifact a ON a.batch_id=b.batch_id AND a.artifact_no=1
                WHERE b.batch_id=?
                """, (rs, n) -> new BatchSource(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDateTime.class),
                    rs.getString(9), rs.getLong(10), rs.getObject(11, Long.class), rs.getString(12), rs.getString(13),
                    rs.getString(14), rs.getObject(15, LocalDateTime.class)), batchId).stream().findFirst();
    }

    public record Industry(String name, boolean supported, String supportStatus) {}
    public record DefaultSummary(long id, String month, long revision, LocalDateTime publishedAt, String status,
            String summaryMonth, long mappingId, long classificationId, long populationId, long storeId,
            String mappingStatus, String mappingReport, String classificationStatus) {}
    public record BatchSource(long id, String dataset, String system, String month, LocalDate referenceOn, String label,
            String sourceVersion, LocalDateTime collectedAt, String status, long mappingId, Long classificationId,
            String dataSha256, String artifactSha256, String artifactKind, LocalDateTime artifactCollectedAt) {}
}
