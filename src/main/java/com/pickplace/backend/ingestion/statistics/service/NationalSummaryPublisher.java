package com.pickplace.backend.ingestion.statistics.service;

import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 승인 묶음과 월별/기본 포인터를 관리한다. 월 정정이 DEFAULT를 암묵적으로 바꾸지 않게 한다. */
@Service
public class NationalSummaryPublisher {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final SummaryReadinessValidator readiness;
    private final TransactionTemplate transaction;

    public NationalSummaryPublisher(JdbcTemplate jdbc, JsonMapper mapper, SummaryReadinessValidator readiness,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.readiness = readiness; transaction = new TransactionTemplate(manager);
    }

    public long prepare(String version, long populationId, long storeId) {
        require(version != null && version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"), "현황 버전 형식 오류");
        return transaction.execute(status -> {
            var b = readiness.check(populationId, storeId);
            String report = mapper.writeValueAsString(Map.of("readinessVerified", true, "populationBatchId", populationId,
                    "storeBatchId", storeId, "writerVersion", StatisticsBatchService.WRITER_VERSION));
            jdbc.update("""
                    INSERT INTO national_summary_release (release_version, reference_month, population_batch_id, store_batch_id,
                        mapping_release_id, classification_id, policy_version, validation_report, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE summary_release_id=summary_release_id
                    """, version, b.month(), populationId, storeId, b.mappingId(), b.classificationId(), StatisticsBatchService.WRITER_VERSION, report);
            var row = jdbc.queryForMap("SELECT * FROM national_summary_release WHERE release_version=? FOR UPDATE", version);
            require(number(row, "population_batch_id") == populationId && number(row, "store_batch_id") == storeId,
                    "같은 현황 버전을 다른 내용으로 재사용할 수 없습니다.");
            return number(row, "summary_release_id");
        });
    }

    public void approve(String version, String actor) {
        actorAndReason(actor, "approval");
        transaction.executeWithoutResult(status -> {
            var row = summary(version, false);
            readiness.check(number(row, "population_batch_id"), number(row, "store_batch_id"));
            if (row.get("status").equals("APPROVED")) return;
            require(row.get("status").equals("READY"), "현황 묶음이 검토 준비 상태가 아닙니다.");
            jdbc.update("UPDATE national_summary_release SET status='APPROVED', approved_at=UTC_TIMESTAMP(6), approved_by=? WHERE summary_release_id=?",
                    actor, row.get("summary_release_id"));
        });
    }

    public long publishMonth(String version, Expected previousMonth, Expected previousDefault,
            String actor, String reason, boolean rollback) {
        actorAndReason(actor, reason);
        return transaction.execute(status -> {
            publicationLock();
            var target = summary(version, true);
            String month = target.get("reference_month").toString(), key = "MONTH:" + month;
            long id = number(target, "summary_release_id");
            Pointer before = pointer(key);
            expect(before, previousMonth);
            if (rollback) {
                require(before != null && wasPublished(key, id), "해당 월에 게시된 적 있는 묶음만 복구할 수 있습니다.");
            }
            Pointer defaultBefore = null;
            if (previousDefault != null) {
                // 기본 조회가 실제로 정정 대상 묶음을 가리킬 때만 동기화한다. 같은 월인 것만으로 부족하다.
                defaultBefore = pointer("DEFAULT"); expect(defaultBefore, previousDefault);
                require(before != null && defaultBefore != null && before.summaryId() == defaultBefore.summaryId(),
                        "DEFAULT가 교체할 기존 월 묶음을 가리키지 않습니다. 기본 변경은 별도로 요청하세요.");
            }
            if (before != null && before.summaryId() == id) return before.revision();
            String action = rollback ? "ROLLBACK" : "PUBLISH";
            long revision = change(key, month, id, before, action, previousDefault != null, actor, reason);
            if (previousDefault != null) change("DEFAULT", month, id, defaultBefore, action, true, actor, reason);
            return revision;
        });
    }

    public long setDefault(String version, Expected expected, String actor, String reason) {
        actorAndReason(actor, reason);
        return transaction.execute(status -> {
            publicationLock();
            var target = summary(version, true);
            long id = number(target, "summary_release_id"); String month = target.get("reference_month").toString();
            require(wasPublished("MONTH:" + month, id), "먼저 월별로 게시한 승인 묶음만 DEFAULT로 선택할 수 있습니다.");
            Pointer before = pointer("DEFAULT"); expect(before, expected);
            if (before != null && before.summaryId() == id) return before.revision();
            return change("DEFAULT", month, id, before, "SET_DEFAULT", false, actor, reason);
        });
    }

    public java.util.List<Map<String, Object>> pointers() {
        return jdbc.queryForList("SELECT publication_key, reference_month, summary_release_id, revision, published_at FROM national_summary_publication ORDER BY publication_key");
    }

    private Map<String, Object> summary(String version, boolean approved) {
        var row = jdbc.queryForMap("SELECT * FROM national_summary_release WHERE release_version=? FOR UPDATE", version);
        require(!approved || row.get("status").equals("APPROVED"), "승인된 현황 묶음만 게시할 수 있습니다.");
        return row;
    }

    private void publicationLock() {
        // 모든 게시 작업은 같은 고정 행부터 잠근다. 첫 MONTH/DEFAULT 생성과 서로 다른 월의 동시 변경도 보호한다.
        jdbc.queryForObject("SELECT operation_key FROM national_statistics_mutex WHERE operation_key='SUMMARY_PUBLICATION' FOR UPDATE", String.class);
    }

    private Pointer pointer(String key) {
        var rows = jdbc.queryForList("SELECT summary_release_id, reference_month, revision FROM national_summary_publication WHERE publication_key=? FOR UPDATE", key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst(); return new Pointer(number(row, "summary_release_id"), row.get("reference_month").toString(), number(row, "revision"));
    }

    private boolean wasPublished(String key, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM national_summary_publication_event WHERE publication_key=? AND to_summary_release_id=?", Long.class, key, id) > 0;
    }

    private static void expect(Pointer current, Expected expected) {
        require(expected != null && expected.revision() >= 0 && (expected.revision() == 0 ? expected.summaryId() == null
                : expected.summaryId() != null && expected.summaryId() > 0), "예상 포인터에는 기존 묶음 ID와 revision이 필요합니다.");
        require(current == null ? expected.revision() == 0 : current.revision() == expected.revision()
                && Objects.equals(current.summaryId(), expected.summaryId()), "게시 포인터가 변경되었습니다. 현재 값을 확인하고 재검토하세요.");
    }

    private long change(String key, String month, long target, Pointer before, String action,
            boolean synchronizedDefault, String actor, String reason) {
        long revision = before == null ? 1 : before.revision() + 1;
        if (before == null) jdbc.update("""
                INSERT INTO national_summary_publication (publication_key, reference_month, summary_release_id, revision, published_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, key, month, target, revision);
        else jdbc.update("""
                UPDATE national_summary_publication SET reference_month=?, summary_release_id=?, revision=?, published_at=UTC_TIMESTAMP(6)
                WHERE publication_key=?
                """, month, target, revision, key);
        // 포인터와 이력은 같은 트랜잭션이다. 두 번째 포인터나 이력 기록이 실패하면 첫 변경도 롤백된다.
        jdbc.update("""
                INSERT INTO national_summary_publication_event (publication_key, from_summary_release_id, from_reference_month,
                    to_summary_release_id, to_reference_month, from_revision, to_revision, action, default_synchronized, actor, reason, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, key, before == null ? null : before.summaryId(), before == null ? null : before.month(), target, month,
                before == null ? 0 : before.revision(), revision, action, synchronizedDefault, actor, reason);
        return revision;
    }

    private static void actorAndReason(String actor, String reason) {
        require(actor != null && !actor.isBlank() && actor.length() <= 100 && reason != null && !reason.isBlank() && reason.length() <= 10000, "작업 주체·사유가 필요합니다.");
    }
    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    public record Expected(long revision, Long summaryId) {}
    private record Pointer(long summaryId, String month, long revision) {}
}
