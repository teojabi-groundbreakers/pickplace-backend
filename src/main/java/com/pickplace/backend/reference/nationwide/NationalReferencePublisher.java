package com.pickplace.backend.reference.nationwide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static com.pickplace.backend.reference.nationwide.NationalReferenceValidator.require;

@Service
public class NationalReferencePublisher {
    private static final String KEY = "NATIONWIDE_SELECTION";
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final TransactionTemplate transaction;

    public NationalReferencePublisher(JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
    }

    public void approve(String version, String actor) {
        require(actor != null && !actor.isBlank(), "Approval actor required");
        transaction.executeWithoutResult(status -> {
            var release = jdbc.queryForMap("SELECT * FROM region_release WHERE release_version = ? FOR UPDATE", version);
            var report = mapper.readTree(String.valueOf(release.get("validation_report")));
            require(report.path("approvable").asBoolean(false), "Pending source, hierarchy or usage review; import a reviewed new snapshot");
            if (release.get("status").equals("APPROVED")) return;
            require(release.get("status").equals("READY"), "Release is not ready");
            long classificationId = report.path("classificationId").asLong();
            var classification = jdbc.queryForMap("SELECT status FROM national_industry_release WHERE classification_id = ? FOR UPDATE", classificationId);
            require(classification.get("status").equals("READY") || classification.get("status").equals("APPROVED"), "Classification is not ready");
            jdbc.update("""
                    UPDATE national_industry_release SET status = 'APPROVED', approved_at = UTC_TIMESTAMP(6), approved_by = ?
                    WHERE classification_id = ? AND status = 'READY'
                    """, actor, classificationId);
            for (var code : report.path("supportedCodes")) {
                jdbc.update("""
                        UPDATE national_industry_code SET is_supported = TRUE, support_release_status = 'APPROVED'
                        WHERE classification_id = ? AND industry_code = ? AND industry_level = 'SMALL'
                        """, classificationId, code.asText());
            }
            jdbc.update("""
                    UPDATE region_release SET status = 'APPROVED', approved_at = UTC_TIMESTAMP(6), approved_by = ?
                    WHERE release_id = ? AND status = 'READY'
                    """, actor, release.get("release_id"));
        });
    }

    public long publish(String version, long expectedRevision, String actor, String reason, boolean rollback) {
        require(expectedRevision >= 0 && actor != null && !actor.isBlank() && reason != null && !reason.isBlank(), "Publication metadata required");
        return transaction.execute(status -> {
            var release = jdbc.queryForMap("SELECT release_id, status FROM region_release WHERE release_version = ? FOR UPDATE", version);
            require(release.get("status").equals("APPROVED"), "Only approved releases can be published");
            long target = ((Number) release.get("release_id")).longValue();
            var current = jdbc.queryForList("SELECT release_id, revision FROM region_publication WHERE publication_key = ? FOR UPDATE", KEY);
            long revision = current.isEmpty() ? 0 : ((Number) current.getFirst().get("revision")).longValue();
            Long previous = current.isEmpty() ? null : ((Number) current.getFirst().get("release_id")).longValue();
            require(revision == expectedRevision, "Publication revision conflict");
            if (rollback) {
                require(jdbc.queryForObject("SELECT COUNT(*) FROM region_publication_event WHERE to_release_id = ?", Integer.class, target) > 0,
                        "Rollback target has never been published");
            }
            if (previous != null && previous == target) return revision;
            long next = revision + 1;
            if (current.isEmpty()) {
                jdbc.update("INSERT INTO region_publication (publication_key, release_id, revision, published_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                        KEY, target, next);
            } else {
                jdbc.update("UPDATE region_publication SET release_id = ?, revision = ?, published_at = UTC_TIMESTAMP(6) WHERE publication_key = ?",
                        target, next, KEY);
            }
            jdbc.update("""
                    INSERT INTO region_publication_event
                        (publication_key, from_release_id, to_release_id, from_revision, to_revision, action, actor, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, KEY, previous, target, revision, next, rollback ? "ROLLBACK" : "PUBLISH", actor, reason);
            return next;
        });
    }
}
