package com.pickplace.backend.ingestion.statistics.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import tools.jackson.databind.json.JsonMapper;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 배치의 시작·재사용·실패·복구를 담당한다. 완료된 배치의 사실 행은 수정하지 않는다. */
@Service
public class StatisticsBatchService {
    public static final String WRITER_VERSION = "national-statistics-writer-v1";
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final TransactionTemplate transaction;

    public StatisticsBatchService(JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        // 외부 호출자가 트랜잭션을 열었더라도 한 청크가 끝나면 실제로 커밋되게 한다.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T inTransaction(Supplier<T> action) { return transaction.execute(status -> action.get()); }

    public Batch start(StatisticsImportInput input) {
        return inTransaction(() -> {
            // 아직 배치 행이 없는 첫 실행과 실패 후 동시 재시도도 같은 규칙으로 직렬화한다.
            jdbc.queryForObject("SELECT operation_key FROM national_statistics_mutex WHERE operation_key='IMPORT_START' FOR UPDATE", String.class);
            var r = input.report();
            var region = jdbc.queryForMap("SELECT release_id, status, validation_report FROM region_release WHERE release_version=?", r.mappingVersion());
            require("APPROVED".equals(region.get("status")), "지역 릴리스가 승인 전입니다.");
            var link = mapper.readTree(region.get("validation_report").toString());
            require(r.mappingSha256().equals(link.path("snapshotSha256").asText()), "DB 지역 릴리스의 내용 해시가 다릅니다.");
            long mapping = ((Number) region.get("release_id")).longValue();
            Long classification = null;
            if (r.dataset().equals("STORE_SNAPSHOT")) {
                var c = jdbc.queryForMap("SELECT classification_id, status, source_sha256 FROM national_industry_release WHERE version=?", r.classificationVersion());
                classification = ((Number) c.get("classification_id")).longValue();
                require("APPROVED".equals(c.get("status")) && r.rawSha256().equals(c.get("source_sha256"))
                        && link.path("classificationId").isIntegralNumber() && link.path("classificationId").canConvertToLong()
                        && link.path("classificationId").longValue() == classification, "DB 승인 분류 연결이 다릅니다.");
            }
            var identity = new LinkedHashMap<String, Object>();
            identity.put("raw", r.rawSha256()); identity.put("rows", r.rowsSha256());
            identity.put("parser", r.parserVersion()); identity.put("writer", WRITER_VERSION); identity.put("policy", r.policyVersion());
            identity.put("mapping", mapping); identity.put("snapshot", r.mappingSha256());
            identity.put("classification", classification); identity.put("sourceRegionVersion", r.sourceRegionVersion());
            String fingerprint = NationalReferenceLoader.sha256(mapper.writeValueAsBytes(identity));
            var active = jdbc.queryForList("SELECT batch_id, status FROM national_import_batch WHERE dataset_code=? AND active_fingerprint=? FOR UPDATE", r.dataset(), fingerprint);
            if (!active.isEmpty()) {
                var row = active.getFirst();
                require("SUCCEEDED".equals(row.get("status")), "동일 입력의 배치가 실행 중입니다. 실행을 확인한 뒤 명시적으로 복구하세요.");
                return new Batch(((Number) row.get("batch_id")).longValue(), mapping, classification, false);
            }
            int attempt = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM national_import_batch WHERE dataset_code=? AND fingerprint=?", Integer.class, r.dataset(), fingerprint);
            jdbc.update("""
                    INSERT INTO national_import_batch (dataset_code, source_system, source_period_type, source_period_key,
                        source_reference_on, source_label, source_region_version, mapping_release_id, classification_id, classification_status,
                        fingerprint, attempt_no, parser_version, transform_version, data_version, policy_version, collected_at, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, r.dataset(), input.source().sourceSystem(), input.source().periodType(), r.periodKey(),
                    LocalDate.parse(r.sourceReferenceOn()), r.sourceLabel(), r.sourceRegionVersion(), mapping, classification,
                    classification == null ? null : "APPROVED", fingerprint, attempt, r.parserVersion(), WRITER_VERSION,
                    r.rawSha256(), r.policyVersion(), LocalDateTime.ofInstant(r.collectedAt(), ZoneOffset.UTC));
            long id = jdbc.queryForObject("SELECT batch_id FROM national_import_batch WHERE dataset_code=? AND fingerprint=? AND attempt_no=?", Long.class, r.dataset(), fingerprint, attempt);
            return new Batch(id, mapping, classification, true);
        });
    }

    public void lockWritable(long id, String expectedStatus) {
        String state = jdbc.queryForObject("SELECT status FROM national_import_batch WHERE batch_id=? FOR UPDATE", String.class, id);
        require(expectedStatus.equals(state), "배치 상태가 변경되어 쓰기를 중단합니다: " + state);
    }

    public void failed(long id, String code) {
        inTransaction(() -> jdbc.update("""
                UPDATE national_import_batch SET status='FAILED', finished_at=UTC_TIMESTAMP(6), failure_code=?
                WHERE batch_id=? AND status IN ('RUNNING','VALIDATING')
                """, code, id));
    }

    // 호출한 쪽의 트랜잭션에서 배치 행을 잠근 다음 사용한다.
    void beginValidation(long id) { jdbc.update("UPDATE national_import_batch SET status='VALIDATING' WHERE batch_id=? AND status='RUNNING'", id); }

    // 죽은 작업인지 운영자가 확인한 뒤 호출한다. 살아 있는 writer도 다음 청크의 상태 검사에서 중단된다.
    public void recover(long id, String expectedStatus, String actor, String reason) {
        require(java.util.Set.of("RUNNING", "VALIDATING").contains(expectedStatus), "복구 대상 상태 오류");
        require(actor != null && !actor.isBlank() && actor.length() <= 100 && reason != null && !reason.isBlank(), "복구 주체·사유 필요");
        inTransaction(() -> {
            lockWritable(id, expectedStatus);
            jdbc.update("""
                    UPDATE national_import_batch SET status='FAILED', finished_at=UTC_TIMESTAMP(6), failure_code='OPERATOR_RECOVERY',
                        validation_report=? WHERE batch_id=?
                    """, mapper.writeValueAsString(Map.of("actor", actor, "reason", reason)), id);
            return null;
        });
    }

    public record Batch(long id, long mappingId, Long classificationId, boolean inserted) {}
}
