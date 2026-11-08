package com.pickplace.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class NationwideReferenceSchemaTests {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareClassification() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        insertClassification(2001, "semas-fixture-1");
        insertClassification(2002, "semas-fixture-2");
    }

    @Test
    void acceptsPopulationH10WithoutInventingParentAndPreservesBlankSejongName() {
        insertSource("MOIS_RESIDENT", "3611055800", "population-202609", null, " ");
        assertThat(jdbc.queryForMap("SELECT source_region_code, source_district_code, source_district_name FROM source_region_reference"))
                .containsEntry("source_region_code", "3611055800")
                .containsEntry("source_district_code", null).containsEntry("source_district_name", " ");
    }

    @Test
    void keepsSourceSystemsAndVersionsSeparate() {
        insertSource("SEMAS_STORE", "11110515", "v1", "11110", "종로구");
        insertSource("SEOUL_COMMERCIAL", "11110515", "v1", "11110", "종로구");
        insertSource("SEMAS_STORE", "11110515", "v2", "11110", "종로구");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_region_reference", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> insertSource("SEMAS_STORE", "11110515", "v1", "11110", "종로구"))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "MOIS_RESIDENT,11110515,1111000000", "MOIS_RESIDENT,1111051500,11110",
            "SEMAS_STORE,1111051500,11110", "SEMAS_STORE,11110515,1111000000",
            "SEOUL_COMMERCIAL,1111051500,11110", "OTHER,11110515,11110",
            "MOIS_RESIDENT,111105150X,1111000000", "SEMAS_STORE,1111051X,11110"
    })
    void rejectsCodesOutsideTheirSourceContract(String system, String code, String parent) {
        assertThatThrownBy(() -> insertSource(system, code, "v1", parent, "원문 부모"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsMissingRequiredStoreParentButAllowsMissingPopulationParent() {
        assertThatThrownBy(() -> insertSource("SEMAS_STORE", "11110515", "v1", null, null))
                .isInstanceOf(DataAccessException.class);
        insertSource("MOIS_RESIDENT", "1111051500", "v1", null, null);
    }

    @Test
    void mapsPopulationH10UsingExactSourceSystemVersionAndOfficialRelease() {
        jdbc.update("""
                INSERT INTO region_release (release_id, release_version, parser_version, policy_version, started_at)
                VALUES (3001, 'national-fixture', 'v1', 'v1', NOW(6))
                """);
        jdbc.update("INSERT INTO service_region (region_code, created_at) VALUES ('1111051500', NOW(6))");
        jdbc.update("""
                INSERT INTO region_source_artifact
                    (release_id, artifact_role, notice_url, download_url, storage_key, sha256, byte_count,
                     collected_at, attachment_version, encoding)
                VALUES (3001, 'H_HISTORY_ZIP', 'https://example.test/notice', 'https://example.test/zip',
                    'fixture.zip', ?, 100, NOW(6), 'v1', 'cp949')
                """, "a".repeat(64));
        jdbc.update("""
                INSERT INTO official_region_node
                    (release_id, region_code, province_name, raw_created_on, created_on,
                     date_quality_status, official_status, artifact_role, source_row_number)
                VALUES (3001, '1111051500', '서울특별시', '19880423', '1988-04-23',
                    'VALID', 'CURRENT', 'H_HISTORY_ZIP', 1)
                """);
        insertSource("MOIS_RESIDENT", "1111051500", "population-202609", null, null);
        jdbc.update("""
                INSERT INTO region_stat_mapping
                    (release_id, source_system, source_region_code, source_region_version, region_code, reference_snapshot_on)
                VALUES (3001, 'MOIS_RESIDENT', '1111051500', 'population-202609', '1111051500', '2026-09-30')
                """);
        assertThat(jdbc.queryForObject("SELECT source_region_code FROM region_stat_mapping", String.class)).isEqualTo("1111051500");
        for (String update : new String[] {
                "source_region_version = 'missing-version'", "source_system = 'SEMAS_STORE'",
                "release_id = 3002", "region_code = '1111051600'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE region_stat_mapping SET " + update))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM source_region_reference"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void acceptsVersionedHierarchyWithSupportDisabledByDefault() {
        insertHierarchy(2001);
        insertHierarchy(2002);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_industry_code", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_industry_code WHERE is_supported", Integer.class)).isZero();
        assertThatThrownBy(() -> insertIndustry(2001, "I21201", "SMALL", "I21", "MIDDLE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsMissingParentWrongLevelAndCrossClassificationParent() {
        insertIndustry(2001, "I", "LARGE", null, null);
        assertThatThrownBy(() -> insertIndustry(2002, "I21", "MIDDLE", "I", "LARGE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertIndustry(2001, "I21201", "SMALL", "I", "MIDDLE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertIndustry(2001, "I21201", "SMALL", "I", "LARGE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsSelfParentAndHierarchyCycles() {
        insertHierarchy(2001);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_code SET parent_code = industry_code WHERE industry_code = 'I21'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_code SET parent_code = 'I21201', parent_level = 'SMALL' WHERE industry_code = 'I'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM national_industry_code WHERE industry_code = 'I'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void onlyApprovedSmallIndustryCanBeEnabled() {
        insertHierarchy(2001);
        assertThatThrownBy(() -> enable("I21201")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_code SET is_supported = TRUE WHERE industry_code = 'I21201'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_release SET status = 'APPROVED' WHERE classification_id = 2001"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("""
                UPDATE national_industry_release SET status = 'APPROVED', validation_report = '{}',
                    approved_at = '2026-11-09 01:00:00', approved_by = 'test' WHERE classification_id = 2001
                """);
        enable("I21201");
        assertThat(jdbc.queryForObject("SELECT is_supported FROM national_industry_code WHERE industry_code = 'I21201'", Boolean.class)).isTrue();
        assertThatThrownBy(() -> enable("I21")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE national_industry_release SET status = 'READY', approved_at = NULL, approved_by = NULL
                WHERE classification_id = 2001
                """)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidClassificationMetadata() {
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_release SET source_sha256 = 'invalid'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_release SET code_system = 'KSIC'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE national_industry_release SET version = 'semas-fixture-1' WHERE classification_id = 2002"))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertSource(String system, String code, String version, String parent, String parentName) {
        jdbc.update("""
                INSERT INTO source_region_reference
                    (source_system, source_region_code, source_region_version, source_region_name,
                     source_district_code, source_district_name, reference_snapshot_on, source_url,
                     reference_evidence, collected_at)
                VALUES (?, ?, ?, '원문 지역명', ?, ?, '2026-09-30', 'https://example.test/source', '{}', NOW(6))
                """, system, code, version, parent, parentName);
    }

    private void insertClassification(long id, String version) {
        jdbc.update("""
                INSERT INTO national_industry_release
                    (classification_id, version, source_url, source_sha256, reference_snapshot_on, collected_at)
                VALUES (?, ?, 'https://example.test/source', ?, '2026-09-30', '2026-11-09 00:00:00')
                """, id, version, "a".repeat(64));
    }

    private void insertHierarchy(long id) {
        insertIndustry(id, "I", "LARGE", null, null);
        insertIndustry(id, "I21", "MIDDLE", "I", "LARGE");
        insertIndustry(id, "I21201", "SMALL", "I21", "MIDDLE");
    }

    private void insertIndustry(long id, String code, String level, String parent, String parentLevel) {
        jdbc.update("""
                INSERT INTO national_industry_code
                    (classification_id, industry_code, industry_name, industry_level, parent_code, parent_level, display_name)
                VALUES (?, ?, '원천 업종명', ?, ?, ?, '표시명')
                """, id, code, level, parent, parentLevel);
    }

    private void enable(String code) {
        jdbc.update("""
                UPDATE national_industry_code SET is_supported = TRUE, support_release_status = 'APPROVED'
                WHERE classification_id = 2001 AND industry_code = ?
                """, code);
    }
}
