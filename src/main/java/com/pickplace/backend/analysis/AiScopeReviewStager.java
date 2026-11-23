package com.pickplace.backend.analysis;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.analysis.AiInputApprovalRepository.IndustryMatch;

@Service
public class AiScopeReviewStager {
    private final JdbcTemplate jdbc;

    public AiScopeReviewStager(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Candidate(String reviewVersion, String regionVersion, String classificationVersion, String regionCode,
            String businessTypeCode, String sourceRegionCode, String sourceIndustryCode, String sourceIndustryName,
            IndustryMatch industryMatch, LocalDate validFrom, LocalDate validThrough, String evidence) {
    }

    /**
     * 검토 근거만 등록한다. AI 사용 승인과 자료별 승인은 별도 절차에서 기록한다.
     * 같은 게시 릴리스 행을 잠가 동시 등록·재실행에도 기존 심사를 보존한다.
     */
    @Transactional
    public long stage(Candidate candidate) {
        validate(candidate);
        var targets = jdbc.query("""
                SELECT r.release_id,c.classification_id
                FROM region_publication p
                JOIN region_release r ON r.release_id=p.release_id AND r.status='APPROVED' AND r.release_version=?
                JOIN region_navigation n ON n.release_id=r.release_id AND n.region_code=?
                JOIN official_region_node o ON o.release_id=n.release_id AND o.region_code=n.region_code
                JOIN national_industry_release c
                  ON c.classification_id=JSON_EXTRACT(r.validation_report,'$.classificationId')
                    AND c.version=? AND c.status='APPROVED'
                JOIN national_industry_code i ON i.classification_id=c.classification_id AND i.industry_code=?
                WHERE p.publication_key='NATIONWIDE_SELECTION'
                  AND n.province_code='1100000000' AND n.selectable=TRUE AND n.selection_role='LOCALITY'
                  AND n.review_status='VERIFIED' AND o.official_status='CURRENT' AND o.date_quality_status='VALID'
                  AND o.created_on <= ? AND i.industry_level='SMALL' AND i.is_supported=TRUE
                  AND i.support_release_status='APPROVED'
                FOR UPDATE
                """, (rs, index) -> new Target(rs.getLong(1), rs.getLong(2)), candidate.regionVersion(), candidate.regionCode(),
                candidate.classificationVersion(), candidate.businessTypeCode(), candidate.validFrom());
        if (targets.size() != 1) {
            throw new IllegalArgumentException("현재 게시본에서 승인된 서울 지역·업종 조합을 찾지 못했습니다.");
        }
        Target target = targets.getFirst();
        var ids = jdbc.queryForList("""
                SELECT review_id FROM ai_input_scope_review
                WHERE review_version=? AND region_release_id=? AND region_code=? AND classification_id=? AND business_type_code=?
                """, Long.class, candidate.reviewVersion(), target.regionRelease(), candidate.regionCode(),
                target.classification(), candidate.businessTypeCode());
        if (!ids.isEmpty()) {
            long id = ids.getFirst();
            var identical = jdbc.queryForList("""
                    SELECT review_id FROM ai_input_scope_review
                    WHERE review_id=? AND source_region_code=? AND source_industry_code=? AND BINARY source_industry_name=?
                      AND industry_match=? AND valid_from=? AND valid_through=? AND BINARY evidence=?
                    """, Long.class, id, candidate.sourceRegionCode(), candidate.sourceIndustryCode(), candidate.sourceIndustryName(),
                    candidate.industryMatch().name(), candidate.validFrom(), candidate.validThrough(), candidate.evidence());
            if (identical.isEmpty()) {
                throw new IllegalArgumentException("같은 심사 버전을 다른 근거로 다시 등록할 수 없습니다.");
            }
            return id;
        }
        jdbc.update("""
                INSERT INTO ai_input_scope_review
                  (region_release_id,region_code,classification_id,business_type_code,source_region_code,
                   source_industry_code,source_industry_name,industry_match,review_version,valid_from,valid_through,evidence)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, target.regionRelease(), candidate.regionCode(), target.classification(), candidate.businessTypeCode(),
                candidate.sourceRegionCode(), candidate.sourceIndustryCode(), candidate.sourceIndustryName(),
                candidate.industryMatch().name(), candidate.reviewVersion(), candidate.validFrom(), candidate.validThrough(), candidate.evidence());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Transactional
    public long approve(Candidate candidate, String reviewer) {
        if (reviewer == null || reviewer.isBlank() || reviewer.length() > 100) {
            throw new IllegalArgumentException("대응을 확정하는 검토자를 기록해 주세요.");
        }
        long id = stage(candidate);
        int updated = jdbc.update("""
                UPDATE ai_input_scope_review
                SET status='APPROVED', approved_at=UTC_TIMESTAMP(6), approved_by=?
                WHERE review_id=? AND status='PENDING'
                """, reviewer, id);
        if (updated == 0 && !"APPROVED".equals(jdbc.queryForObject(
                "SELECT status FROM ai_input_scope_review WHERE review_id=?", String.class, id))) {
            throw new IllegalArgumentException("반려 상태인 대응은 새 심사 버전으로 검토해야 합니다.");
        }
        return id;
    }

    private static void validate(Candidate candidate) {
        if (candidate == null || candidate.reviewVersion() == null || !candidate.reviewVersion().matches("[A-Za-z0-9._-]{1,100}")
                || candidate.regionCode() == null || !candidate.regionCode().matches("[0-9]{10}")
                || candidate.businessTypeCode() == null || !candidate.businessTypeCode().matches("[A-Z][0-9]{5}")
                || candidate.sourceRegionCode() == null || !candidate.sourceRegionCode().matches("[0-9]{8}")
                || candidate.sourceIndustryCode() == null || !candidate.sourceIndustryCode().matches("CS[0-9]{6}")
                || candidate.industryMatch() == null || candidate.validFrom() == null || candidate.validThrough() == null
                || candidate.validFrom().isAfter(candidate.validThrough())) {
            throw new IllegalArgumentException("검토 버전·코드·유효기간을 확인해 주세요.");
        }
        for (String required : new String[]{candidate.regionVersion(), candidate.classificationVersion(),
                candidate.sourceIndustryName(), candidate.evidence()}) {
            if (required == null || required.isBlank()) {
                throw new IllegalArgumentException("기준정보 버전·서울 업종 표시명·검토 근거가 필요합니다.");
            }
        }
    }

    private record Target(long regionRelease, long classification) {}
}
