package com.pickplace.backend;

import java.sql.SQLException;

import org.flywaydb.core.Flyway;
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
class H10ReferenceSchemaTests {

    private static final long RELEASE = 1001;
    private static final long OTHER_RELEASE = 1002;
    private static final String ROOT = "1100000000";
    private static final String GROUP = "1111000000";
    private static final String DONG = "1111051500";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void prepareUnpublishedReference() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        insertRelease(RELEASE, "h10-test-1");
        insertRelease(OTHER_RELEASE, "h10-test-2");
        insertRegion(ROOT);
        insertRegion(GROUP);
        insertRegion(DONG);
        insertNode(RELEASE, ROOT, 1);
        insertNode(RELEASE, GROUP, 2);
        insertNode(RELEASE, DONG, 3);
        insertSource("source-v1");
    }

    @Test
    void createsAllReferenceTablesWithoutPublishingOrSeeding() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class))
                .contains("service_region", "region_release", "region_source_artifact", "official_region_node",
                        "region_navigation", "source_region_reference", "region_stat_mapping",
                        "region_publication", "region_publication_event");
        assertThat(count("region_publication")).isZero();
        assertThat(count("region_stat_mapping")).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11110515", "111105150", "11110515A0", "111105150 ", "111105150\n"})
    void rejectsInvalidH10Codes(String code) {
        assertThatThrownBy(() -> insertRegion(code)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsMissingNodeSourceAndWrongReleaseParents() {
        reject(1452, """
                INSERT INTO official_region_node
                    (release_id, region_code, province_name, raw_created_on, created_on,
                     date_quality_status, official_status, artifact_role, source_row_number)
                VALUES (?, '9999999999', '서울특별시', '19880423', '1988-04-23', 'VALID', 'CURRENT', 'H_HISTORY_ZIP', 99)
                """, RELEASE);
        insertNode(OTHER_RELEASE, DONG, 3);
        reject(1452, navigationSql(), OTHER_RELEASE, DONG, GROUP, ROOT, GROUP);
        reject(1452, mappingSql(), RELEASE, "missing-version", DONG);
        reject(1452, mappingSql(), OTHER_RELEASE, "source-v1", GROUP);
    }

    @Test
    void storesSeparateSnapshotsAndRejectsDuplicateSourceRows() {
        insertNode(OTHER_RELEASE, DONG, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM official_region_node WHERE region_code = ?", Integer.class, DONG))
                .isEqualTo(2);
        reject(1062, "UPDATE official_region_node SET source_row_number = 1 WHERE release_id = ? AND region_code = ?", RELEASE, DONG);
        reject(1451, "DELETE FROM region_source_artifact WHERE release_id = ?", RELEASE);
        reject(1451, "DELETE FROM service_region WHERE region_code = ?", DONG);
    }

    @Test
    void preservesInvalidRawDatesButRejectsValidatedReversedDates() {
        insertRegion("4311073000");
        jdbc.update("""
                INSERT INTO official_region_node
                    (release_id, region_code, province_name, raw_created_on, raw_abolished_on,
                     date_quality_status, date_issue_code, official_status, artifact_role, source_row_number)
                VALUES (?, '4311073000', '원문 지역', '19900401', '19900331',
                    'INVALID', 'CREATED_AFTER_ABOLISHED', 'LEGACY', 'H_HISTORY_ZIP', 4)
                """, RELEASE);
        assertThat(jdbc.queryForMap("SELECT raw_created_on, raw_abolished_on FROM official_region_node WHERE region_code = '4311073000'"))
                .containsEntry("raw_created_on", "19900401").containsEntry("raw_abolished_on", "19900331");
        reject(3819, """
                UPDATE official_region_node SET date_quality_status = 'VALID', date_issue_code = NULL,
                    created_on = '1990-04-01', abolished_on = '1990-03-31' WHERE region_code = '4311073000'
                """);
        reject(3819, "UPDATE official_region_node SET date_issue_code = NULL WHERE region_code = '4311073000'");
        reject(3819, "UPDATE official_region_node SET official_status = 'LEGACY' WHERE region_code = ?", DONG);
    }

    @Test
    void keepsValidLegacyDatesAndRawSourceNames() {
        jdbc.update("""
                UPDATE official_region_node SET official_status = 'LEGACY', raw_abolished_on = '20250701',
                    abolished_on = '2025-07-01' WHERE release_id = ? AND region_code = ?
                """, RELEASE, DONG);
        assertThat(jdbc.queryForMap("SELECT official_status, raw_abolished_on FROM official_region_node WHERE region_code = ?", DONG))
                .containsEntry("official_status", "LEGACY").containsEntry("raw_abolished_on", "20250701");
        assertThat(jdbc.queryForObject("SELECT source_region_code FROM source_region_reference", String.class)).isEqualTo("11110515");
        assertThat(jdbc.queryForObject("SELECT source_region_name FROM source_region_reference", String.class)).isEqualTo("청운효자동");
    }

    @Test
    void pendingNavigationCannotBeSelectedOrUseItselfAsParent() {
        jdbc.update(navigationSql(), RELEASE, DONG, GROUP, ROOT, GROUP);
        reject(3819, "UPDATE region_navigation SET selectable = TRUE");
        reject(3819, "UPDATE region_navigation SET review_status = 'VERIFIED'");
        reject(3819, "UPDATE region_navigation SET selection_parent_code = region_code");
        reject(3819, "UPDATE region_navigation SET hierarchy_parent_code = region_code");
        reject(3819, "UPDATE region_navigation SET auto_select_group = TRUE");
        jdbc.update("UPDATE region_navigation SET review_status = 'VERIFIED', review_evidence = 'fixture', selectable = TRUE");
        assertThat(jdbc.queryForObject("SELECT selectable FROM region_navigation", Boolean.class)).isTrue();
    }

    @Test
    void preservesRawSourceVersionsAndAmbiguousMappingCandidates() {
        insertSource("source-v2");
        jdbc.update(mappingSql(), RELEASE, "source-v1", DONG);
        jdbc.update(mappingSql(), RELEASE, "source-v1", GROUP);
        jdbc.update(mappingSql(), RELEASE, "source-v2", DONG);
        assertThat(count("source_region_reference")).isEqualTo(2);
        assertThat(count("region_stat_mapping")).isEqualTo(3);
        // 후보의 복수 대응은 저장하되, T07/T13에서 유일 대응만 채택한다.
        reject(1062, mappingSql(), RELEASE, "source-v1", DONG);
        reject(3819, "UPDATE region_stat_mapping SET review_status = 'VERIFIED'");
        reject(1451, "DELETE FROM source_region_reference WHERE source_region_version = 'source-v1'");
    }

    @Test
    void publicationRequiresApprovalAndProtectsReferencedStatus() {
        reject(1452, publicationSql(), RELEASE);
        reject(3819, "UPDATE region_release SET status = 'APPROVED' WHERE release_id = ?", RELEASE);
        approve(RELEASE);
        jdbc.update(publicationSql(), RELEASE);
        assertThat(count("region_publication")).isEqualTo(1);
        reject(1451, """
                UPDATE region_release SET status = 'READY', approved_by = NULL, approved_at = NULL WHERE release_id = ?
                """, RELEASE);
        reject(3819, "UPDATE region_publication SET revision = 0");
        reject(3819, "UPDATE region_publication SET release_status = 'READY'");
    }

    @Test
    void eventRequiresConsecutiveRevisionAndPreservesRollbackHistory() {
        approve(RELEASE);
        approve(OTHER_RELEASE);
        jdbc.update(publicationSql(), RELEASE);
        insertEvent(null, RELEASE, 0, 1, "PUBLISH");
        jdbc.update("UPDATE region_publication SET release_id = ?, revision = 2", OTHER_RELEASE);
        insertEvent(RELEASE, OTHER_RELEASE, 1, 2, "PUBLISH");
        jdbc.update("UPDATE region_publication SET release_id = ?, revision = 3", RELEASE);
        insertEvent(OTHER_RELEASE, RELEASE, 2, 3, "ROLLBACK");
        assertThat(count("region_publication_event")).isEqualTo(3);
        assertThatThrownBy(() -> insertEvent(RELEASE, OTHER_RELEASE, 3, 5, "PUBLISH"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertEvent(OTHER_RELEASE, RELEASE, 2, 3, "ROLLBACK"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsMalformedReleaseAndArtifactMetadata() {
        reject(3819, "UPDATE region_release SET release_version = ' bad version' WHERE release_id = ?", RELEASE);
        reject(1062, "UPDATE region_release SET release_version = 'h10-test-1' WHERE release_id = ?", OTHER_RELEASE);
        reject(3819, "UPDATE region_source_artifact SET byte_count = -1");
        reject(3819, "UPDATE region_source_artifact SET sha256 = 'bad'");
        reject(3819, "UPDATE region_source_artifact SET usage_review_status = 'VERIFIED'");
        reject(3819, "UPDATE region_release SET status = 'FAILED' WHERE release_id = ?", RELEASE);
        reject(3819, "UPDATE source_region_reference SET source_region_code = '1111051 '");
        reject(3819, "UPDATE source_region_reference SET source_system = 'OTHER'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"version\n", "version ", "-version", "", "version/1"})
    void rejectsInvalidReleaseVersions(String version) {
        assertThatThrownBy(() -> jdbc.update("UPDATE region_release SET release_version = ? WHERE release_id = ?", version, RELEASE))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertRelease(long id, String version) {
        jdbc.update("""
                INSERT INTO region_release (release_id, release_version, parser_version, policy_version, started_at)
                VALUES (?, ?, 'parser-v1', 'policy-v1', '2026-11-08 00:00:00')
                """, id, version);
        jdbc.update("""
                INSERT INTO region_source_artifact
                    (release_id, artifact_role, notice_url, download_url, storage_key, sha256,
                     byte_count, collected_at, attachment_version, encoding)
                VALUES (?, 'H_HISTORY_ZIP', 'https://example.test/notice', 'https://example.test/archive',
                    'fixture.zip', ?, 100, '2026-11-08 00:00:00', '20261026', 'cp949')
                """, id, "a".repeat(64));
    }

    private void insertRegion(String code) {
        jdbc.update("INSERT INTO service_region (region_code, created_at) VALUES (?, NOW(6))", code);
    }

    private void insertNode(long release, String code, int row) {
        jdbc.update("""
                INSERT INTO official_region_node
                    (release_id, region_code, province_name, raw_created_on, created_on,
                     date_quality_status, official_status, artifact_role, source_row_number)
                VALUES (?, ?, '서울특별시', '19880423', '1988-04-23', 'VALID', 'CURRENT', 'H_HISTORY_ZIP', ?)
                """, release, code, row);
    }

    private void insertSource(String version) {
        jdbc.update("""
                INSERT INTO source_region_reference
                    (source_system, source_region_code, source_region_version, source_region_name,
                     source_district_code, source_district_name, reference_snapshot_on, source_url,
                     reference_evidence, collected_at)
                VALUES ('SEOUL_COMMERCIAL', '11110515', ?, '청운효자동', '11110', '종로구', '2026-10-12',
                    'https://example.test/source', '{}', NOW(6))
                """, version);
    }

    private void approve(long release) {
        jdbc.update("""
                UPDATE region_release SET status = 'APPROVED', finished_at = '2026-11-08 00:01:00',
                    approved_at = '2026-11-08 00:02:00', approved_by = 'test',
                    validation_report = '{}', expected_counts = '{}', actual_counts = '{}' WHERE release_id = ?
                """, release);
    }

    private void insertEvent(Long from, long to, long fromRevision, long toRevision, String action) {
        jdbc.update("""
                INSERT INTO region_publication_event
                    (publication_key, from_release_id, to_release_id, from_revision, to_revision, action, actor, reason, occurred_at)
                VALUES ('NATIONWIDE_SELECTION', ?, ?, ?, ?, ?, 'test', 'fixture', NOW(6))
                """, from, to, fromRevision, toRevision, action);
    }

    private String navigationSql() {
        return """
                INSERT INTO region_navigation
                    (release_id, region_code, node_kind, hierarchy_parent_code, province_code, selection_parent_code,
                     display_name, selection_role)
                VALUES (?, ?, 'DONG', ?, ?, ?, '청운효자동', 'LOCALITY')
                """;
    }

    private String mappingSql() {
        return """
                INSERT INTO region_stat_mapping
                    (release_id, source_system, source_region_code, source_region_version, region_code, reference_snapshot_on)
                VALUES (?, 'SEOUL_COMMERCIAL', '11110515', ?, ?, '2026-10-12')
                """;
    }

    private String publicationSql() {
        return """
                INSERT INTO region_publication (publication_key, release_id, revision, published_at)
                VALUES ('NATIONWIDE_SELECTION', ?, 1, NOW(6))
                """;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void reject(int errorCode, String sql, Object... parameters) {
        assertThatThrownBy(() -> jdbc.update(sql, parameters)).isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isEqualTo(errorCode);
                });
    }
}
