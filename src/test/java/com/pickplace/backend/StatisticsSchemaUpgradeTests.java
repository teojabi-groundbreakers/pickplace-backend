package com.pickplace.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsSchemaUpgradeTests {

    @Test
    void upgradesV1ThroughV4WithoutChangingExistingReferenceOrStatisticsData() {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("pickplace_upgrade_test")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var jdbc = new JdbcTemplate(source);
            var v1 = Flyway.configure().dataSource(source).target("1").cleanDisabled(true).load();
            assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
            jdbc.update("""
                    INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
                    VALUES ('11110', '기존 자치구', 'original-v1', NOW(6), NOW(6), NOW(6))
                    """);
            jdbc.update("""
                    INSERT INTO administrative_dong
                        (dong_code, dong_name, district_code, region_version, official_code_status,
                         collected_at, created_at, updated_at)
                    VALUES ('11110515', '기존 행정동', '11110', 'original-v1', 'UNVERIFIED', NOW(6), NOW(6), NOW(6))
                    """);
            jdbc.update("""
                    INSERT INTO business_type
                        (business_type_code, source_business_type_name, display_name, data_version,
                         collected_at, created_at, updated_at)
                    VALUES ('CS100010', '커피-음료', '커피·음료', 'original-v1', NOW(6), NOW(6), NOW(6))
                    """);

            var latest = Flyway.configure().dataSource(source).target("2").cleanDisabled(true).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT data_version FROM district WHERE district_code = '11110'",
                    String.class)).isEqualTo("original-v1");
            assertThat(jdbc.queryForObject("SELECT region_version FROM administrative_dong", String.class))
                    .isEqualTo("original-v1");
            assertThat(jdbc.queryForObject("SELECT display_name FROM business_type", String.class))
                    .isEqualTo("커피·음료");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dong_resident_quarter", Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                    String.class)).containsExactly("1", "2");
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();

            jdbc.update("UPDATE business_type SET is_supported = TRUE, display_order = 1");
            jdbc.update("""
                    INSERT INTO data_import_batch
                        (batch_id, dataset_code, quarter_code, source_id, source_url, source_checksum,
                         parser_version, data_version, started_at)
                    VALUES (1, 'RESIDENT', '20262', 'fixture', 'https://example.test/source', ?,
                        'parser-v1', 'data-v1', NOW(6))
                    """, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO dong_resident_quarter
                        (batch_id, quarter_code, dong_code, region_version, resident_population, row_status, raw_payload)
                    VALUES (1, '20262', '11110515', 'original-v1', 0, 'AVAILABLE', '{"adstrd_cd":"11110515"}')
                    """);
            var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
            var v3 = Flyway.configure().dataSource(source).target("3").cleanDisabled(true).load();
            assertThat(v3.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IN ('1', '2') ORDER BY installed_rank"))
                    .isEqualTo(checksums);
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3");
            assertThat(jdbc.queryForObject("SELECT is_supported FROM business_type", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT display_order FROM business_type", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT region_version FROM administrative_dong", String.class)).isEqualTo("original-v1");
            assertThat(jdbc.queryForObject("SELECT dong_code FROM dong_resident_quarter", String.class)).isEqualTo("11110515");
            assertThat(jdbc.queryForObject("SELECT resident_population FROM dong_resident_quarter", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.adstrd_cd')) FROM dong_resident_quarter", String.class))
                    .isEqualTo("11110515");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_region", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_publication", Integer.class)).isZero();
            assertThat(v3.migrate().migrationsExecuted).isZero();
            assertThat(v3.validateWithResult().validationSuccessful).isTrue();

            jdbc.update("""
                    INSERT INTO source_region_reference
                        (source_system, source_region_code, source_region_version, source_region_name,
                         source_district_code, source_district_name, reference_snapshot_on, source_url,
                         reference_evidence, collected_at)
                    VALUES ('SEOUL_COMMERCIAL', '11110515', 'original-v1', '기존 원천명', '11110', '종로구',
                        '2026-09-30', 'https://example.test/source', '{"original":true}', NOW(6))
                    """);
            var originalSource = jdbc.queryForMap("SELECT * FROM source_region_reference");
            jdbc.update("""
                    INSERT INTO region_release (release_id, release_version, parser_version, policy_version, started_at)
                    VALUES (1, 'original-h10', 'v1', 'v1', NOW(6))
                    """);
            jdbc.update("INSERT INTO service_region (region_code, created_at) VALUES ('1111051500', NOW(6))");
            jdbc.update("""
                    INSERT INTO region_source_artifact
                        (release_id, artifact_role, notice_url, download_url, storage_key, sha256, byte_count,
                         collected_at, attachment_version, encoding)
                    VALUES (1, 'H_HISTORY_ZIP', 'https://example.test/notice', 'https://example.test/zip',
                        'original.zip', ?, 100, NOW(6), 'original-v1', 'cp949')
                    """, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO official_region_node
                        (release_id, region_code, province_name, raw_created_on, created_on,
                         date_quality_status, official_status, artifact_role, source_row_number)
                    VALUES (1, '1111051500', '서울특별시', '19880423', '1988-04-23',
                        'VALID', 'CURRENT', 'H_HISTORY_ZIP', 1)
                    """);
            jdbc.update("""
                    INSERT INTO region_stat_mapping
                        (release_id, source_system, source_region_code, source_region_version, region_code,
                         review_status, evidence, reference_snapshot_on)
                    VALUES (1, 'SEOUL_COMMERCIAL', '11110515', 'original-v1', '1111051500',
                        'VERIFIED', 'original-evidence', '2026-09-30')
                    """);
            var originalMapping = jdbc.queryForMap("SELECT * FROM region_stat_mapping");
            var v3Checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
            var v4 = Flyway.configure().dataSource(source).cleanDisabled(true).load();
            assertThat(v4.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM source_region_reference")).isEqualTo(originalSource);
            assertThat(jdbc.queryForMap("SELECT * FROM region_stat_mapping")).isEqualTo(originalMapping);
            assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IN ('1','2','3') ORDER BY installed_rank"))
                    .isEqualTo(v3Checksums);
            assertThat(jdbc.queryForObject("SELECT is_supported FROM business_type", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT resident_population FROM dong_resident_quarter", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_industry_code", Integer.class)).isZero();
            assertThat(v4.migrate().migrationsExecuted).isZero();
            assertThat(v4.validateWithResult().validationSuccessful).isTrue();
        }
    }
}
