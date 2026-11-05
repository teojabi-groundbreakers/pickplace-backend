package com.pickplace.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsSchemaUpgradeTests {

    @Test
    void upgradesExistingV1DatabaseWithoutChangingReferenceDataAndDoesNotReapply() {
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

            var latest = Flyway.configure().dataSource(source).target(MigrationVersion.LATEST).cleanDisabled(true).load();
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
        }
    }
}
