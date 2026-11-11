package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.Metric;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/** 청크 커밋·실패 이력·재실행과 승인/게시는 전국 배치와 독립적으로 관리한다. */
@Service
public class DetailBatchService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;

    public DetailBatchService(
            JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T transaction(Supplier<T> action) {
        return tx.execute(s -> action.get());
    }

    public Batch start(DetailInputLoader.Input input, long mappingId) {
        var m = input.manifest();
        // 저장 경로·재다운로드 시각이 달라도 원문과 처리 의미가 같으면 같은 적재다.
        var identity = new TreeMap<String, Object>();
        identity.put("writer", DetailInputLoader.WRITER_VERSION);
        identity.put("raw", m.rawSha256());
        identity.put("source", m.sourceSystem());
        identity.put("dataset", m.datasetCode());
        identity.put("adapter", m.adapter());
        identity.put("periodType", m.periodType());
        identity.put("periods", new TreeSet<>(m.periods()));
        identity.put("regionScheme", m.regionScheme());
        identity.put("industryScheme", m.industryScheme());
        identity.put("reference", m.referenceSha256());
        identity.put("mapping", input.mappingHash());
        identity.put("encoding", m.encoding());
        identity.put("rows", m.expectedRows());
        identity.put("aliases", new TreeMap<>(m.aliases()));
        identity.put(
                "metrics",
                m.metrics().stream().sorted(Comparator.comparing(Metric::field)).toList());
        String fingerprint = NationalReferenceLoader.sha256(mapper.writeValueAsBytes(identity));
        return transaction(
                () -> {
                    lock("IMPORT_START");
                    var active =
                            jdbc.queryForList(
                                    "SELECT batch_id,status FROM regional_statistic_batch WHERE"
                                            + " source_system=? AND dataset_code=? AND"
                                            + " active_fingerprint=? FOR UPDATE",
                                    m.sourceSystem(),
                                    m.datasetCode(),
                                    fingerprint);
                    if (!active.isEmpty()) {
                        var row = active.getFirst();
                        require(
                                !"RUNNING".equals(row.get("status")),
                                "동일 입력이 실행 중입니다. 확인 후 recover 명령으로 복구하세요.");
                        return new Batch(
                                ((Number) row.get("batch_id")).longValue(), mappingId, false);
                    }
                    int attempt =
                            jdbc.queryForObject(
                                    "SELECT COALESCE(MAX(attempt_no),0)+1 FROM"
                                            + " regional_statistic_batch WHERE source_system=? AND"
                                            + " dataset_code=? AND fingerprint=?",
                                    Integer.class,
                                    m.sourceSystem(),
                                    m.datasetCode(),
                                    fingerprint);
                    jdbc.update(
                            """
                            INSERT INTO regional_statistic_batch(source_system,dataset_code,period_type,period_keys,region_scheme,industry_scheme,
                                mapping_release_id,fingerprint,attempt_no,definition_json,mapping_json,collected_at,started_at)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                            """,
                            m.sourceSystem(),
                            m.datasetCode(),
                            m.periodType(),
                            mapper.writeValueAsString(m.periods()),
                            m.regionScheme(),
                            m.industryScheme(),
                            mappingId,
                            fingerprint,
                            attempt,
                            mapper.writeValueAsString(m),
                            input.mappingJson(),
                            LocalDateTime.ofInstant(m.collectionTime(), ZoneOffset.UTC));
                    long id =
                            jdbc.queryForObject(
                                    "SELECT batch_id FROM regional_statistic_batch WHERE"
                                        + " source_system=? AND dataset_code=? AND fingerprint=?"
                                        + " AND attempt_no=?",
                                    Long.class,
                                    m.sourceSystem(),
                                    m.datasetCode(),
                                    fingerprint,
                                    attempt);
                    jdbc.update(
                            "INSERT INTO regional_statistic_artifact VALUES(?,?,?,?,?,?)",
                            id,
                            input.raw().toString(),
                            m.sourceUrl(),
                            m.rawSha256(),
                            fileSize(input),
                            mapper.writeValueAsString(m.metadata()));
                    return new Batch(id, mappingId, true);
                });
    }

    public void writable(long id) {
        require(
                "RUNNING"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT status FROM regional_statistic_batch WHERE"
                                                + " batch_id=? FOR UPDATE",
                                        String.class,
                                        id)),
                "배치가 더 이상 실행 상태가 아닙니다.");
    }

    public void failed(long id, String reason) {
        transaction(
                () ->
                        jdbc.update(
                                "UPDATE regional_statistic_batch SET"
                                    + " status='FAILED',finished_at=UTC_TIMESTAMP(6),failure_code=?"
                                    + " WHERE batch_id=? AND status='RUNNING'",
                                reason,
                                id));
    }

    public void recover(long id, String actor, String reason) {
        actor(actor, reason);
        transaction(
                () -> {
                    writable(id);
                    return jdbc.update(
                            "UPDATE regional_statistic_batch SET"
                                + " status='FAILED',finished_at=UTC_TIMESTAMP(6),failure_code='OPERATOR_RECOVERY',validation_report=?"
                                + " WHERE batch_id=?",
                            mapper.writeValueAsString(Map.of("actor", actor, "reason", reason)),
                            id);
                });
    }

    public void approve(long id, String actor) {
        actor(actor, "approve");
        transaction(
                () -> {
                    var batch =
                            jdbc.queryForMap(
                                    "SELECT status,invalid_row_count,accepted_row_count FROM"
                                        + " regional_statistic_batch WHERE batch_id=? FOR UPDATE",
                                    id);
                    if ("APPROVED".equals(batch.get("status"))) return null;
                    require(
                            "READY".equals(batch.get("status"))
                                    && ((Number) batch.get("accepted_row_count")).longValue() > 0,
                            "검증이 완료된 값이 필요합니다.");
                    // 오류 수치는 원문·검증 이력에 보존하되, 공개 읽기 뷰에서는 INVALID/HOLD를 제외한다.
                    jdbc.update(
                            "UPDATE regional_statistic_batch SET"
                                + " status='APPROVED',approved_by=?,approved_at=UTC_TIMESTAMP(6)"
                                + " WHERE batch_id=?",
                            actor,
                            id);
                    return null;
                });
    }

    public long publish(
            long id,
            String period,
            long expectedRevision,
            Long previousId,
            String actor,
            String reason) {
        actor(actor, reason);
        require(
                expectedRevision >= 0
                        && (expectedRevision == 0 ? previousId == null : previousId != null),
                "기존 revision·배치를 함께 지정하세요.");
        return transaction(
                () -> {
                    lock("PUBLICATION");
                    var batch =
                            jdbc.queryForMap(
                                    "SELECT"
                                        + " source_system,dataset_code,period_type,period_keys,status"
                                        + " FROM regional_statistic_batch WHERE batch_id=? FOR"
                                        + " UPDATE",
                                    id);
                    require("APPROVED".equals(batch.get("status")), "승인한 배치만 게시할 수 있습니다.");
                    require(
                            mapper.readTree(batch.get("period_keys").toString())
                                    .valueStream()
                                    .anyMatch(n -> n.asText().equals(period)),
                            "배치에 없는 기간입니다.");
                    require(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM regional_statistic_row WHERE"
                                                    + " batch_id=? AND period_key=? AND"
                                                    + " row_status='VALID'",
                                            Long.class,
                                            id,
                                            period)
                                    > 0,
                            "게시할 정상 행이 없습니다.");
                    Object system = batch.get("source_system"),
                            dataset = batch.get("dataset_code"),
                            type = batch.get("period_type");
                    var pointers =
                            jdbc.queryForList(
                                    "SELECT batch_id,revision FROM regional_statistic_publication"
                                            + " WHERE source_system=? AND dataset_code=? AND"
                                            + " period_type=? AND period_key=? FOR UPDATE",
                                    system,
                                    dataset,
                                    type,
                                    period);
                    long revision =
                            pointers.isEmpty()
                                    ? 0
                                    : ((Number) pointers.getFirst().get("revision")).longValue();
                    Long old =
                            pointers.isEmpty()
                                    ? null
                                    : ((Number) pointers.getFirst().get("batch_id")).longValue();
                    require(
                            revision == expectedRevision && Objects.equals(old, previousId),
                            "게시 기준이 변경됐습니다.");
                    if (Objects.equals(old, id)) return revision;
                    long next = revision + 1;
                    jdbc.update(
                            """
                            INSERT INTO regional_statistic_publication VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6))
                            ON DUPLICATE KEY UPDATE batch_id=?,revision=?,published_at=UTC_TIMESTAMP(6)
                            """,
                            system,
                            dataset,
                            type,
                            period,
                            id,
                            next,
                            id,
                            next);
                    jdbc.update(
                            "INSERT INTO"
                                + " regional_statistic_publication_event(source_system,dataset_code,period_type,period_key,previous_batch_id,batch_id,revision,actor,reason,published_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                            system,
                            dataset,
                            type,
                            period,
                            old,
                            id,
                            next,
                            actor,
                            reason);
                    return next;
                });
    }

    private void lock(String key) {
        jdbc.queryForObject(
                "SELECT operation_key FROM regional_statistic_mutex WHERE operation_key=? FOR"
                        + " UPDATE",
                String.class,
                key);
    }

    private static void actor(String actor, String reason) {
        require(
                actor != null
                        && !actor.isBlank()
                        && actor.length() <= 100
                        && reason != null
                        && !reason.isBlank(),
                "주체·사유 필요");
    }

    private static long fileSize(DetailInputLoader.Input input) {
        try {
            return java.nio.file.Files.size(input.raw());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public record Batch(long id, long mappingId, boolean inserted) {}
}
