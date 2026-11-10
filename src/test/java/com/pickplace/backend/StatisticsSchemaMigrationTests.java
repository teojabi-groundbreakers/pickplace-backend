package com.pickplace.backend;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class StatisticsSchemaMigrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertReferenceData() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        jdbc.update("""
                INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
                VALUES ('11110', '테스트구', 'test-v1', NOW(6), NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO administrative_dong
                    (dong_code, dong_name, district_code, region_version, official_code_status,
                     collected_at, created_at, updated_at)
                VALUES ('11110515', '테스트동', '11110', 'region-v1', 'UNVERIFIED', NOW(6), NOW(6), NOW(6))
                """);
        for (String code : List.of("CS100010", "CS100008")) {
            jdbc.update("""
                    INSERT INTO business_type
                        (business_type_code, source_business_type_name, display_name, data_version,
                         collected_at, created_at, updated_at)
                    VALUES (?, '테스트업종', '테스트업종', 'test-v1', NOW(6), NOW(6), NOW(6))
                    """, code);
        }
    }

    @Test
    void createsStatisticsTablesAndLookupIndexes() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                """, String.class)).contains("data_import_batch", "dataset_publication", "data_quality_issue",
                "dong_resident_quarter", "dong_worker_quarter", "dong_facility_quarter",
                "dong_store_quarter", "dong_sales_quarter", "dong_flow_quarter");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                    AND index_name IN ('ix_resident_lookup', 'ix_worker_lookup', 'ix_facility_lookup',
                        'ix_store_lookup', 'ix_sales_lookup', 'ix_flow_lookup')
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1
                """, Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
            "resident,RESIDENT,resident_population",
            "worker,WORKER,worker_population",
            "facility,FACILITY,total_facility_count",
            "store,STORE,total_store_count",
            "sales,SALES,source_sales_amount",
            "flow,FLOW,source_flow_population"
    })
    void storesZeroMissingAndInvalidValuesWithoutConvertingThem(String kind, String dataset, String metric) {
        long batch = insertBatch(dataset, "20262");
        insertMetric(kind, metric, batch, 0L, "AVAILABLE", null);
        assertRejected(1062, "INSERT INTO dong_" + kind + "_quarter SELECT * FROM dong_" + kind + "_quarter");
        assertRejected(1452, "UPDATE dong_" + kind + "_quarter SET quarter_code = '20261'");
        assertThat(jdbc.queryForObject("SELECT " + metric + " FROM dong_" + kind + "_quarter", Long.class))
                .isZero();
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET " + metric + " = -1");
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET " + metric + " = NULL");

        jdbc.update("UPDATE dong_" + kind + "_quarter SET " + metric
                + " = NULL, row_status = 'NO_DATA', reason_code = 'SOURCE_VALUE_MISSING'");
        assertThat(jdbc.queryForObject("SELECT " + metric + " FROM dong_" + kind + "_quarter", Long.class))
                .isNull();
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET reason_code = NULL");
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET reason_code = 'NOT_INGESTED'");
        jdbc.update("UPDATE dong_" + kind + "_quarter SET row_status = 'INVALID',"
                + " reason_code = 'QUALITY_CHECK_FAILED', raw_payload = '{\"invalidSourceValue\": -1}'");
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(raw_payload, '$.invalidSourceValue')"
                + " FROM dong_" + kind + "_quarter", Integer.class)).isEqualTo(-1);
    }

    @ParameterizedTest
    @CsvSource({"resident,RESIDENT,resident_population", "worker,WORKER,worker_population",
            "facility,FACILITY,total_facility_count", "store,STORE,total_store_count"})
    void rejectsValuesOutsideFrontendSafeIntegerRange(String kind, String dataset, String metric) {
        long batch = insertBatch(dataset, "20262");
        insertMetric(kind, metric, batch, 9007199254740991L, "AVAILABLE", null);
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET " + metric + " = 9007199254740992");
    }

    @Test
    void populationIsIndustryIndependentAndUniqueWithinEachBatch() {
        long first = insertBatch("RESIDENT", "20262");
        long retry = insertBatch("RESIDENT", "20262");
        insertMetric("resident", "resident_population", first, 10L, "AVAILABLE", null);
        assertRejected(1062, """
                INSERT INTO dong_resident_quarter SELECT * FROM dong_resident_quarter WHERE batch_id = ?
                """, first);
        insertMetric("resident", "resident_population", retry, 20L, "AVAILABLE", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dong_resident_quarter", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                    AND table_name IN ('dong_resident_quarter', 'dong_worker_quarter',
                        'dong_facility_quarter', 'dong_flow_quarter')
                    AND column_name = 'business_type_code'
                """, Integer.class)).isZero();
    }

    @Test
    void industryStatisticsAllowDifferentIndustriesButRejectDuplicateOrUnknownKeys() {
        long batch = insertBatch("STORE", "20262");
        insertMetric("store", "total_store_count", batch, 8L, "AVAILABLE", null);
        jdbc.update("""
                INSERT INTO dong_store_quarter
                    (batch_id, quarter_code, dong_code, business_type_code, region_version,
                     total_store_count, row_status, raw_payload)
                VALUES (?, '20262', '11110515', 'CS100008', 'region-v1', 3, 'AVAILABLE', '{}')
                """, batch);
        assertRejected(1062, "INSERT INTO dong_store_quarter SELECT * FROM dong_store_quarter WHERE business_type_code = 'CS100010'");
        assertRejected(1452, "UPDATE dong_store_quarter SET business_type_code = 'CS999999'");
        assertRejected(1452, "UPDATE dong_store_quarter SET dong_code = '99999999'");
    }

    @Test
    void rejectsWrongDatasetAndQuarterOrMissingBatch() {
        long batch = insertBatch("RESIDENT", "20262");
        insertMetric("resident", "resident_population", batch, 5L, "AVAILABLE", null);
        assertRejected(1452, "UPDATE dong_resident_quarter SET quarter_code = '20261'");
        assertRejected(1452, "UPDATE dong_resident_quarter SET batch_id = -1");
        long worker = insertBatch("WORKER", "20262");
        assertRejected(1452, "UPDATE dong_resident_quarter SET batch_id = ?", worker);
        assertRejected(3819, "UPDATE dong_resident_quarter SET region_version = ' '");
    }

    @Test
    void checksStoreCompositionWhenAllValuesExistWithoutFillingMissingComponents() {
        long batch = insertBatch("STORE", "20262");
        insertMetric("store", "total_store_count", batch, 8L, "AVAILABLE", null);
        jdbc.update("UPDATE dong_store_quarter SET general_store_count = 6, franchise_store_count = 2");
        assertRejected(3819, "UPDATE dong_store_quarter SET franchise_store_count = 3");
        assertRejected(3819, "UPDATE dong_store_quarter SET opened_store_count = -1");
        jdbc.update("UPDATE dong_store_quarter SET general_store_count = NULL");
        assertThat(jdbc.queryForObject("SELECT general_store_count FROM dong_store_quarter", Long.class)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260", "20265", "2026Q", "2026", "2026 "})
    void rejectsMalformedQuarter(String quarter) {
        insertBatch("RESIDENT", "20262");
        assertRejected(3819, "UPDATE data_import_batch SET quarter_code = ?", quarter);
    }

    @Test
    void requiresSourceFingerprintAndVersions() {
        insertBatch("RESIDENT", "20262");
        assertRejected(3819, "UPDATE data_import_batch SET source_url = ' '");
        assertRejected(3819, "UPDATE data_import_batch SET source_checksum = 'not-a-sha256'");
        assertRejected(3819, "UPDATE data_import_batch SET parser_version = ''");
        assertRejected(3819, "UPDATE data_import_batch SET data_version = ' '");
        assertRejected(3819, "UPDATE data_import_batch SET dataset_code = 'UNKNOWN'");
    }

    @Test
    void validatesCountAccountingAndCompletionState() {
        long batch = insertBatch("RESIDENT", "20262");
        assertRejected(3819, "UPDATE data_import_batch SET source_row_count = 1");
        assertRejected(3819, """
                UPDATE data_import_batch SET source_row_count = 3, accepted_row_count = 1,
                    rejected_row_count = 1, duplicate_row_count = 0
                """);
        assertRejected(3819, """
                UPDATE data_import_batch SET source_row_count = 0, accepted_row_count = -1,
                    rejected_row_count = 1, duplicate_row_count = 0
                """);
        assertRejected(3819, "UPDATE data_import_batch SET status = 'SUCCEEDED'");
        assertRejected(3819, "UPDATE data_import_batch SET status = 'FAILED', finished_at = NOW(6)");
        assertRejected(3819, "UPDATE data_import_batch SET finished_at = '2000-01-01'");
        succeed(batch);
        assertThat(jdbc.queryForObject("SELECT status FROM data_import_batch WHERE batch_id = ?",
                String.class, batch)).isEqualTo("SUCCEEDED");
    }

    @Test
    void publicationRequiresMatchingSucceededBatch() {
        long running = insertBatch("RESIDENT", "20262");
        assertRejected(1452, """
                INSERT INTO dataset_publication (dataset_code, quarter_code, batch_id, published_at)
                VALUES ('RESIDENT', '20262', ?, NOW(6))
                """, running);
        fail(running);
        assertRejected(1452, """
                INSERT INTO dataset_publication (dataset_code, quarter_code, batch_id, published_at)
                VALUES ('RESIDENT', '20262', ?, NOW(6))
                """, running);
        long valid = insertBatch("RESIDENT", "20262");
        succeed(valid);
        publish("RESIDENT", valid);
        assertRejected(1452, "UPDATE dataset_publication SET quarter_code = '20261'");
        assertRejected(3819, "UPDATE dataset_publication SET batch_status = 'FAILED'");
        assertRejected(1451, """
                UPDATE data_import_batch SET status = 'FAILED', failure_code = 'REVOKED' WHERE batch_id = ?
                """, valid);
        assertRejected(1451, "DELETE FROM data_import_batch WHERE batch_id = ?", valid);
    }

    @Test
    void failedRetryKeepsLastPublicationAndSuccessfulReplacementKeepsPreviousStatistics() {
        long original = insertBatch("RESIDENT", "20262");
        insertMetric("resident", "resident_population", original, 10L, "AVAILABLE", null);
        succeed(original);
        publish("RESIDENT", original);

        long failed = insertBatch("RESIDENT", "20262");
        insertMetric("resident", "resident_population", failed, 99L, "AVAILABLE", null);
        fail(failed);
        assertRejected(1452, "UPDATE dataset_publication SET batch_id = ?", failed);
        assertPublishedPopulation(10L);

        long replacement = insertBatch("RESIDENT", "20262");
        insertMetric("resident", "resident_population", replacement, 20L, "AVAILABLE", null);
        succeed(replacement);
        jdbc.update("UPDATE dataset_publication SET batch_id = ?, published_at = NOW(6)", replacement);
        assertPublishedPopulation(20L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dong_resident_quarter", Integer.class)).isEqualTo(3);
        assertRejected(1062, """
                INSERT INTO dataset_publication (dataset_code, quarter_code, batch_id, published_at)
                VALUES ('RESIDENT', '20262', ?, NOW(6))
                """, original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES", "FLOW"})
    void blocksPublicationOfUnverifiedAggregates(String dataset) {
        long batch = insertBatch(dataset, "20262");
        succeed(batch);
        assertRejected(3819, """
                INSERT INTO dataset_publication (dataset_code, quarter_code, batch_id, published_at)
                VALUES (?, '20262', ?, NOW(6))
                """, dataset, batch);
    }

    @ParameterizedTest
    @CsvSource({"sales,SALES,source_sales_amount", "flow,FLOW,source_flow_population"})
    void doesNotAllowMarkingUnverifiedAggregatesAsVerified(String kind, String dataset, String metric) {
        long batch = insertBatch(dataset, "20262");
        insertMetric(kind, metric, batch, 1L, "AVAILABLE", null);
        assertRejected(3819, "UPDATE dong_" + kind + "_quarter SET aggregation_status = 'VERIFIED'");
    }

    @Test
    void retainsBadSourceKeysInQualityJournal() {
        long batch = insertBatch("STORE", "20262");
        jdbc.update("""
                INSERT INTO data_quality_issue
                    (batch_id, source_row_number, raw_dong_code, raw_business_type_code,
                     field_name, issue_code, severity, raw_value)
                VALUES (?, 1, '잘못된 동 코드', 'UNKNOWN', 'dong_code', 'UNKNOWN_REGION', 'ERROR', '"raw"')
                """, batch);
        assertThat(jdbc.queryForObject("SELECT raw_dong_code FROM data_quality_issue", String.class))
                .isEqualTo("잘못된 동 코드");
        assertRejected(3819, "UPDATE data_quality_issue SET source_row_number = 0");
        assertRejected(3819, "UPDATE data_quality_issue SET severity = 'INFO'");
        assertRejected(1452, "UPDATE data_quality_issue SET batch_id = -1");
    }

    private long insertBatch(String dataset, String quarter) {
        jdbc.update("""
                INSERT INTO data_import_batch
                    (dataset_code, quarter_code, source_id, source_url, source_checksum,
                     parser_version, data_version, started_at)
                VALUES (?, ?, 'test-source', 'https://example.com/public-source', REPEAT('a', 64),
                    'parser-v1', 'test-v1', NOW(6))
                """, dataset, quarter);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertMetric(String kind, String metric, long batch, Long value, String status, String reason) {
        boolean industry = kind.equals("store") || kind.equals("sales");
        jdbc.update("INSERT INTO dong_" + kind + "_quarter"
                + " (batch_id, quarter_code, dong_code, region_version, " + metric
                + (industry ? ", business_type_code" : "") + ", row_status, reason_code, raw_payload)"
                + " VALUES (?, '20262', '11110515', 'region-v1', ?"
                + (industry ? ", 'CS100010'" : "") + ", ?, ?, '{}')", batch, value, status, reason);
    }

    private void succeed(long batch) {
        jdbc.update("""
                UPDATE data_import_batch SET status = 'SUCCEEDED', collected_at = NOW(6),
                    finished_at = NOW(6), source_row_count = 1, accepted_row_count = 1,
                    rejected_row_count = 0, duplicate_row_count = 0, validation_report = '{"passed":true}'
                WHERE batch_id = ?
                """, batch);
    }

    private void fail(long batch) {
        jdbc.update("""
                UPDATE data_import_batch SET status = 'FAILED', finished_at = NOW(6),
                    failure_code = 'VALIDATION_FAILED' WHERE batch_id = ?
                """, batch);
    }

    private void publish(String dataset, long batch) {
        jdbc.update("""
                INSERT INTO dataset_publication (dataset_code, quarter_code, batch_id, published_at)
                VALUES (?, '20262', ?, NOW(6))
                """, dataset, batch);
    }

    private void assertPublishedPopulation(long expected) {
        assertThat(jdbc.queryForObject("""
                SELECT s.resident_population FROM dataset_publication p
                JOIN dong_resident_quarter s ON s.batch_id = p.batch_id
                    AND s.dataset_code = p.dataset_code AND s.quarter_code = p.quarter_code
                WHERE p.dataset_code = 'RESIDENT' AND p.quarter_code = '20262' AND s.dong_code = '11110515'
                """, Long.class)).isEqualTo(expected);
    }

    private void assertRejected(int errorCode, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
