package com.pickplace.backend;

import java.sql.SQLException;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class NationalStatisticsSchemaTests {
    private static final String REGION = "1111051500";
    private static final String SOURCE_VERSION = "source-202609";
    private static final long MAPPING = 9101;
    private static final long CLASSIFICATION = 9201;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareApprovedReferencesAndBatches() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        jdbc.update("""
                INSERT INTO region_release (release_id, release_version, status, parser_version, policy_version,
                    started_at, finished_at, approved_at, approved_by, expected_counts, actual_counts, validation_report)
                VALUES (?, 'national-statistics-fixture', 'APPROVED', 'v1', 'v1', NOW(6), NOW(6), NOW(6), 'test', '{}', '{}', '{}')
                """, MAPPING);
        jdbc.update("INSERT INTO service_region (region_code, created_at) VALUES (?, NOW(6))", REGION);
        jdbc.update("""
                INSERT INTO region_source_artifact (release_id, artifact_role, notice_url, download_url, storage_key,
                    sha256, byte_count, collected_at, attachment_version, encoding)
                VALUES (?, 'H_HISTORY_ZIP', 'https://example.test/notice', 'https://example.test/source', 'test.zip', ?, 10, NOW(6), 'v1', 'xlsx')
                """, MAPPING, "a".repeat(64));
        jdbc.update("""
                INSERT INTO official_region_node (release_id, region_code, province_name, raw_created_on, created_on,
                    date_quality_status, official_status, artifact_role, source_row_number)
                VALUES (?, ?, '서울특별시', '20000101', '2000-01-01', 'VALID', 'CURRENT', 'H_HISTORY_ZIP', 1)
                """, MAPPING, REGION);
        for (String system : new String[] {"MOIS_RESIDENT", "SEMAS_STORE"}) {
            String code = system.equals("MOIS_RESIDENT") ? REGION : "11110515";
            jdbc.update("""
                    INSERT INTO source_region_reference (source_system, source_region_code, source_region_version,
                        source_region_name, source_district_code, reference_snapshot_on, source_url, reference_evidence, collected_at)
                    VALUES (?, ?, ?, '원천명', ?, '2026-09-30', 'https://example.test/source', '{}', NOW(6))
                    """, system, code, SOURCE_VERSION, system.equals("MOIS_RESIDENT") ? null : "11110");
            jdbc.update("""
                    INSERT INTO region_stat_mapping (release_id, source_system, source_region_code, source_region_version,
                        region_code, review_status, reference_snapshot_on, evidence)
                    VALUES (?, ?, ?, ?, ?, 'VERIFIED', '2026-09-30', 'test reviewed')
                    """, MAPPING, system, code, SOURCE_VERSION, REGION);
        }
        jdbc.update("""
                INSERT INTO national_industry_release (classification_id, version, source_url, source_sha256,
                    reference_snapshot_on, collected_at, status, validation_report, approved_at, approved_by)
                VALUES (?, 'statistics-classification', 'https://example.test/source', ?, '2026-09-30', NOW(6), 'APPROVED', '{}', NOW(6), 'test')
                """, CLASSIFICATION, "b".repeat(64));
        jdbc.update("""
                INSERT INTO national_industry_code (classification_id, industry_code, industry_name, industry_level, display_name)
                VALUES (?, 'I', '음식', 'LARGE', '음식')
                """, CLASSIFICATION);
        jdbc.update("""
                INSERT INTO national_industry_code (classification_id, industry_code, industry_name, industry_level, parent_code, parent_level, display_name)
                VALUES (?, 'I2', '음식', 'MIDDLE', 'I', 'LARGE', '음식'), (?, 'I21201', '카페', 'SMALL', 'I2', 'MIDDLE', '카페')
                """, CLASSIFICATION, CLASSIFICATION);
        batch(1, false, "202609");
        batch(2, true, "202609");
        artifact(1, 1, false);
        artifact(2, 1, true);
        jdbc.update("""
                INSERT INTO national_source_artifact (batch_id, artifact_no, artifact_kind, parent_artifact_no, source_url,
                    storage_key, member_path, sha256, byte_count, crc32, encoding, column_names, source_row_count, collected_at)
                VALUES (2, 2, 'ZIP_MEMBER', 1, 'https://example.test/source', 'stores.zip', 'seoul.csv', ?, 100, '12345678', 'utf-8', '[]', 1, NOW(6))
                """, "c".repeat(64));
    }

    @Test
    void createsTenTablesWithoutPublishingOrInsertingStatistics() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class))
                .contains("national_import_batch", "national_source_artifact", "resident_population_month", "store_snapshot_entry",
                        "store_region_coverage", "store_industry_count", "national_quality_issue", "national_summary_release",
                        "national_summary_publication", "national_summary_publication_event");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resident_population_month", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_summary_publication", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_period_key='202600'", "source_period_key='202613'", "source_period_key='20263'",
            "source_period_key='202610'", "source_reference_on='2026-09-29'", "source_period_type='SNAPSHOT'",
            "source_system='SEMAS_STORE'", "classification_id=9201, classification_status='APPROVED'", "mapping_release_status='READY'"})
    void rejectsWrongPopulationPeriodOrSourceScope(String change) {
        rejected(() -> jdbc.update("UPDATE national_import_batch SET " + change + " WHERE batch_id=1"));
    }

    @Test
    void preservesFailedAttemptsButAllowsOnlyOneActiveOrSuccessfulFingerprint() {
        rejected(() -> batch(3, true, "202609", fingerprint(2), 2));
        jdbc.update("UPDATE national_import_batch SET status='FAILED', failure_code='SOURCE_REJECTED' WHERE batch_id=2");
        batch(3, true, "202609", fingerprint(2), 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_import_batch WHERE fingerprint=?", Integer.class, fingerprint(2))).isEqualTo(2);
        rejected(() -> jdbc.update("UPDATE national_import_batch SET status='SUCCEEDED', failure_code=NULL WHERE batch_id=2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_row_count=2", "accepted_row_count=-1, source_row_count=-1", "held_row_count=NULL",
            "validation_report=NULL", "finished_at=NULL", "attempt_no=0"})
    void rejectsIncompleteSuccessfulBatchOrUnbalancedRowAccounting(String change) {
        rejected(() -> jdbc.update("UPDATE national_import_batch SET " + change + " WHERE batch_id=1"));
    }

    @Test
    void acceptsRealZeroPopulationAndKeepsMissingValuesDistinct() {
        population();
        assertThat(jdbc.queryForObject("SELECT total_population FROM resident_population_month", Long.class)).isZero();
        jdbc.update("""
                UPDATE resident_population_month SET total_population=NULL, male_population=NULL, female_population=NULL,
                    age_sex_counts=NULL, row_status='NO_DATA', reason_code='SOURCE_VALUE_MISSING'
                """);
        assertThat(jdbc.queryForObject("SELECT total_population FROM resident_population_month", Long.class)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"total_population=-1", "total_population=9007199254740992", "total_population=1",
            "male_population=NULL", "age_sex_counts='{}'", "age_sex_counts='{\"male\":[],\"female\":[]}'",
            "row_status='INVALID'", "reference_month='202610'", "reference_on='2026-09-29'",
            "source_system='SEMAS_STORE'", "source_region_version='missing'", "source_artifact_no=2",
            "region_code='11110515'", "batch_id=2", "source_row_number=0"})
    void rejectsInvalidPopulationValuesAndCrossBatchProvenance(String change) {
        population();
        rejected(() -> jdbc.update("UPDATE resident_population_month SET " + change));
    }

    @Test
    void acceptedStoreMayLackCoordinatesWithoutChangingCountQuality() {
        store();
        assertThat(jdbc.queryForObject("SELECT row_status FROM store_snapshot_entry", String.class)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT latitude FROM store_snapshot_entry", Double.class)).isNull();
        jdbc.update("UPDATE store_snapshot_entry SET region_code=NULL, industry_small_code=NULL, row_status='HOLD', reason_code='UNRESOLVED', temporal_region_status='PENDING'");
        assertThat(jdbc.queryForObject("SELECT source_store_id FROM store_snapshot_entry", String.class)).isEqualTo("store-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"region_code=NULL", "industry_small_code=NULL", "classification_id=9999",
            "industry_small_code='I'", "temporal_region_status='BOUNDARY_MISMATCH'", "reason_code='UNRESOLVED'",
            "source_region_code='99999999'", "source_region_version='other'", "source_period_key='202610'",
            "source_artifact_no=9", "legal_dong_code='11110515'", "batch_id=1",
            "location_quality_status='VALID'", "location_quality_status='VALID', longitude=181, latitude=37"})
    void rejectsAcceptedStoreMissingReferencesOrOutsideSourceScope(String change) {
        store();
        rejected(() -> jdbc.update("UPDATE store_snapshot_entry SET " + change));
    }

    @Test
    void rejectsDuplicateStoreIdAndSourceLineInsteadOfHidingItWithDistinct() {
        store();
        rejected(this::store);
        rejected(() -> jdbc.update("""
                INSERT INTO store_snapshot_entry SELECT batch_id, 'store-2', dataset_code, source_system, source_period_key,
                    source_region_code, source_region_version, legal_dong_code, mapping_release_id, region_code, classification_id,
                    industry_small_code, industry_level, raw_industry_large, raw_industry_middle, raw_industry_small,
                    store_name, road_address, longitude, latitude, location_quality_status, row_status, reason_code,
                    temporal_region_status, source_artifact_no, source_row_number FROM store_snapshot_entry
                """));
    }

    @Test
    void retainsInternal97ButSuppressesPublicCountWhenThreeRowsAffectTheAggregate() {
        aggregate(97, "HOLD", "COUNT_AFFECTING_ROWS", 3);
        assertThat(jdbc.queryForObject("SELECT accepted_store_count FROM store_industry_count", Long.class)).isEqualTo(97);
        assertThat(jdbc.queryForObject("SELECT CASE WHEN count_quality_status='VALID' THEN accepted_store_count ELSE NULL END FROM store_industry_count", Long.class)).isNull();
        rejected(() -> jdbc.update("UPDATE store_industry_count SET count_quality_status='VALID', count_quality_reason=NULL"));
    }

    @Test
    void preservesValidatedZeroAndRejectsUnexplainedHold() {
        aggregate(0, "VALID", null, 0);
        assertThat(jdbc.queryForObject("SELECT accepted_store_count FROM store_industry_count", Long.class)).isZero();
        rejected(() -> jdbc.update("UPDATE store_industry_count SET count_quality_status='HOLD', count_quality_reason='UNKNOWN'"));
        rejected(() -> jdbc.update("UPDATE store_industry_count SET classification_id=9999"));
    }

    @Test
    void coverageRecordsOnlyObservedSourceRegionsAndBalancesHeldRows() {
        jdbc.update("""
                INSERT INTO store_region_coverage (batch_id, source_period_key, source_region_code, source_region_version,
                    mapping_release_id, region_code, source_row_count, accepted_row_count, held_row_count,
                    rejected_row_count, duplicate_row_count, industry_distribution, temporal_region_status, coverage_status)
                VALUES (2, '202609', '11110515', ?, ?, ?, 100, 97, 3, 0, 0, '{"I21201":100}', 'VALID', 'VALID')
                """, SOURCE_VERSION, MAPPING, REGION);
        rejected(() -> jdbc.update("UPDATE store_region_coverage SET source_row_count=0"));
        rejected(() -> jdbc.update("UPDATE store_region_coverage SET held_row_count=0"));
        rejected(() -> jdbc.update("UPDATE store_region_coverage SET source_region_code='99999999'"));
    }

    @Test
    void unknownIndustryMayHoldWholeRegionAndUnboundedErrorsAreExplicitlyGlobal() {
        jdbc.update("""
                INSERT INTO national_quality_issue (batch_id, source_artifact_no, source_row_number, source_region_code,
                    field_name, reason_code, severity, affects_count, impact_scope, affected_region_code, evidence)
                VALUES (2, 2, 1, '11110515', 'industry', 'UNKNOWN_INDUSTRY', 'HOLD', TRUE, 'REGION', ?, '{}')
                """, REGION);
        rejected(() -> jdbc.update("UPDATE national_quality_issue SET impact_scope='REGION_INDUSTRY'"));
        jdbc.update("UPDATE national_quality_issue SET impact_scope='GLOBAL', affected_region_code=NULL");
        assertThat(jdbc.queryForObject("SELECT impact_scope FROM national_quality_issue", String.class)).isEqualTo("GLOBAL");
        rejected(() -> jdbc.update("UPDATE national_quality_issue SET impact_scope='NONE'"));
    }

    @Test
    void summaryRequiresTwoSuccessfulSameMonthBatchesAndMatchingClassification() {
        jdbc.update("UPDATE national_import_batch SET status='VALIDATING', finished_at=NULL WHERE batch_id=1");
        rejected(() -> summary(1, "202609", 1, 2, false));
        jdbc.update("UPDATE national_import_batch SET status='SUCCEEDED', finished_at=NOW(6) WHERE batch_id=1");
        summary(1, "202609", 1, 2, false);
        rejected(() -> jdbc.update("UPDATE national_summary_release SET population_batch_id=2"));
        rejected(() -> jdbc.update("UPDATE national_summary_release SET classification_id=9999"));
        rejected(() -> jdbc.update("UPDATE national_summary_release SET reference_month='202610'"));
        rejected(() -> jdbc.update("UPDATE national_import_batch SET status='FAILED', failure_code='FAIL' WHERE batch_id=2"));
    }

    @Test
    void onlyApprovedSummaryMayBePublishedAndMonthKeyMustMatch() {
        summary(1, "202609", 1, 2, false);
        rejected(() -> publication("MONTH:202609", "202609", 1));
        jdbc.update("UPDATE national_summary_release SET status='APPROVED', approved_at=NOW(6), approved_by='test'");
        rejected(() -> publication("MONTH:202610", "202609", 1));
        publication("MONTH:202609", "202609", 1);
        rejected(() -> jdbc.update("UPDATE national_summary_release SET status='READY', approved_at=NULL, approved_by=NULL"));
        rejected(() -> jdbc.update("UPDATE national_summary_publication SET revision=0"));
    }

    @Test
    void correctingSeptemberDoesNotImplicitlyMoveDecemberDefault() {
        summary(1, "202609", 1, 2, true);
        summary(2, "202609", 1, 2, true);
        batch(3, false, "202612");
        batch(4, true, "202612");
        summary(3, "202612", 3, 4, true);
        publication("MONTH:202609", "202609", 1);
        publication("MONTH:202612", "202612", 3);
        publication("DEFAULT", "202612", 3);
        jdbc.update("UPDATE national_summary_publication SET summary_release_id=2, revision=2 WHERE publication_key='MONTH:202609'");
        assertThat(jdbc.queryForObject("SELECT summary_release_id FROM national_summary_publication WHERE publication_key='DEFAULT'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT revision FROM national_summary_publication WHERE publication_key='DEFAULT'", Long.class)).isEqualTo(1);
    }

    @Test
    void failedPublicationEventRollsBackPointerInSameTransaction() {
        summary(1, "202609", 1, 2, true);
        summary(2, "202609", 1, 2, true);
        publication("MONTH:202609", "202609", 1);
        // 테스트의 준비 데이터까지 롤백하지 않도록 같은 연결의 저장점 안에서 게시 쓰기를 검사한다.
        assertThatThrownBy(() -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try {
                jdbc.update("UPDATE national_summary_publication SET summary_release_id=2, revision=2 WHERE publication_key='MONTH:202609'");
                jdbc.update("""
                        INSERT INTO national_summary_publication_event (publication_key, to_summary_release_id, to_reference_month,
                            from_revision, to_revision, action, actor, reason, occurred_at)
                        VALUES ('MONTH:202609', 2, '202609', 0, 3, 'PUBLISH', 'test', 'invalid revision', NOW(6))
                        """);
                return null;
            } catch (DataAccessException exception) {
                connection.rollback(savepoint);
                throw exception;
            } finally {
                connection.releaseSavepoint(savepoint);
            }
        })).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT summary_release_id FROM national_summary_publication", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_summary_publication_event", Long.class)).isZero();
    }

    @Test
    void eventsPreserveApprovedTargetsAndRejectDuplicateRevision() {
        summary(1, "202609", 1, 2, true);
        jdbc.update("""
                INSERT INTO national_summary_publication_event (publication_key, to_summary_release_id, to_reference_month,
                    from_revision, to_revision, action, actor, reason, occurred_at)
                VALUES ('MONTH:202609', 1, '202609', 0, 1, 'PUBLISH', 'test', 'initial', NOW(6))
                """);
        rejected(() -> jdbc.update("UPDATE national_summary_publication_event SET action='SET_DEFAULT'"));
        rejected(() -> jdbc.update("UPDATE national_summary_publication_event SET to_reference_month='202610'"));
        rejected(() -> jdbc.update("INSERT INTO national_summary_publication_event SELECT NULL, publication_key, from_summary_release_id, from_reference_month, to_summary_release_id, to_reference_month, release_status, from_revision, to_revision, action, default_synchronized, actor, reason, occurred_at FROM national_summary_publication_event"));
    }

    private void batch(long id, boolean store, String month) {
        batch(id, store, month, fingerprint(id), 1);
    }

    private void batch(long id, boolean store, String month, String fingerprint, int attempt) {
        LocalDate reference = LocalDate.of(Integer.parseInt(month.substring(0, 4)), Integer.parseInt(month.substring(4)), 1);
        reference = reference.withDayOfMonth(reference.lengthOfMonth());
        jdbc.update("""
                INSERT INTO national_import_batch (batch_id, dataset_code, source_system, source_period_type, source_period_key,
                    source_reference_on, source_label, source_region_version, mapping_release_id, classification_id, classification_status,
                    fingerprint, attempt_no, parser_version, transform_version, data_version, policy_version, status,
                    collected_at, started_at, finished_at, source_row_count, accepted_row_count, held_row_count,
                    rejected_row_count, duplicate_row_count, validation_report)
                VALUES (?, ?, ?, ?, ?, ?, 'fixture', ?, ?, ?, ?, ?, ?, 'v1', 'v1', 'v1', 'v1', 'SUCCEEDED',
                    NOW(6), NOW(6), NOW(6), 1, 1, 0, 0, 0, '{}')
                """, id, store ? "STORE_SNAPSHOT" : "POPULATION_MONTH", store ? "SEMAS_STORE" : "MOIS_RESIDENT",
                store ? "SNAPSHOT" : "MONTH", month, reference, SOURCE_VERSION, MAPPING,
                store ? CLASSIFICATION : null, store ? "APPROVED" : null, fingerprint, attempt);
    }

    private String fingerprint(long id) {
        return String.format("%064x", id);
    }

    private void artifact(long batch, int number, boolean zip) {
        jdbc.update("""
                INSERT INTO national_source_artifact (batch_id, artifact_no, artifact_kind, source_url, storage_key,
                    sha256, byte_count, encoding, column_names, collected_at)
                VALUES (?, ?, ?, 'https://example.test/source', 'fixture', ?, 100, 'utf-8', '[]', NOW(6))
                """, batch, number, zip ? "ZIP" : "CSV", "a".repeat(64));
    }

    private void population() {
        String ages = "[" + "0,".repeat(110) + "0]";
        jdbc.update("""
                INSERT INTO resident_population_month (batch_id, reference_month, reference_on, region_code, source_region_code,
                    source_region_version, mapping_release_id, total_population, male_population, female_population,
                    age_sex_counts, row_status, temporal_region_status, source_artifact_no, source_row_number)
                VALUES (1, '202609', '2026-09-30', ?, ?, ?, ?, 0, 0, 0, ?, 'AVAILABLE', 'VALID', 1, 1)
                """, REGION, REGION, SOURCE_VERSION, MAPPING, "{\"male\":" + ages + ",\"female\":" + ages + "}");
    }

    private void store() {
        jdbc.update("""
                INSERT INTO store_snapshot_entry (batch_id, source_store_id, source_period_key, source_region_code,
                    source_region_version, mapping_release_id, region_code, classification_id, industry_small_code,
                    location_quality_status, row_status, temporal_region_status, source_artifact_no, source_row_number)
                VALUES (2, 'store-1', '202609', '11110515', ?, ?, ?, ?, 'I21201', 'MISSING', 'ACCEPTED', 'VALID', 2, 1)
                """, SOURCE_VERSION, MAPPING, REGION, CLASSIFICATION);
    }

    private void aggregate(long count, String quality, String reason, long issues) {
        jdbc.update("""
                INSERT INTO store_industry_count (batch_id, source_period_key, source_region_version, mapping_release_id,
                    region_code, classification_id, industry_code, accepted_store_count, count_quality_status,
                    count_quality_reason, count_affecting_issue_count)
                VALUES (2, '202609', ?, ?, ?, ?, 'I21201', ?, ?, ?, ?)
                """, SOURCE_VERSION, MAPPING, REGION, CLASSIFICATION, count, quality, reason, issues);
    }

    private void summary(long id, String month, long population, long store, boolean approved) {
        jdbc.update("""
                INSERT INTO national_summary_release (summary_release_id, release_version, reference_month,
                    population_batch_id, store_batch_id, mapping_release_id, classification_id, policy_version,
                    status, validation_report, created_at, approved_at, approved_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'v1', ?, '{}', NOW(6), ?, ?)
                """, id, "summary-" + id, month, population, store, MAPPING, CLASSIFICATION,
                approved ? "APPROVED" : "READY", approved ? java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(1) : null,
                approved ? "test" : null);
    }

    private void publication(String key, String month, long summary) {
        jdbc.update("INSERT INTO national_summary_publication (publication_key, reference_month, summary_release_id, revision, published_at) VALUES (?, ?, ?, 1, NOW(6))",
                key, month, summary);
    }

    private void rejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DataAccessException.class, ex ->
                assertThat(ex.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getErrorCode()).isIn(3819, 1452, 1451, 1062, 1048)));
    }
}
