package com.pickplace.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class RegionMapCoordinateSchemaTests {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsReviewTablesAndRejectsInvalidBatch() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='8' AND success=1", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO region_release (release_id, release_version, parser_version, policy_version, started_at) VALUES (98765, 'coordinate-test', 'test', 'test', NOW(6))");
        jdbc.update("""
            INSERT INTO region_map_coordinate_batch
              (region_release_id, snapshot_sha256, boundary_sha256, boundary_date, source_url, license_url,
               attribution, coordinate_crs, calculation_method, status, created_at)
            VALUES (98765, ?, ?, '2026-10-01', 'https://example.test', 'https://example.test/license',
                    'source', 'EPSG:4326', 'interior', 'REVIEW_ONLY', NOW(6))
            """, "a".repeat(64), "b".repeat(64));
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO region_map_coordinate_batch
              (region_release_id, snapshot_sha256, boundary_sha256, boundary_date, source_url, license_url,
               attribution, coordinate_crs, calculation_method, status, created_at)
            VALUES (98765, ?, ?, '2026-10-01', 'https://example.test', 'https://example.test/license',
                    'source', 'EPSG:4326', 'interior', 'PUBLISHED', NOW(6))
            """, "c".repeat(64), "d".repeat(64))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void requiresValidCoordinatesAndTheSameRegionRelease() {
        var fixture = new RegionTestData(jdbc);
        long release = fixture.release("coordinate-fixture", "APPROVED");
        fixture.insert(release,
                RegionTestData.Region.province("1100000000", "서울특별시"),
                RegionTestData.Region.group("1111000000", "종로구", "1100000000"),
                RegionTestData.Region.locality("1111051500", "청운효자동", "1100000000", "1111000000"));
        jdbc.update("""
            INSERT INTO region_map_coordinate_batch
              (region_release_id, snapshot_sha256, boundary_sha256, boundary_date, source_url, license_url,
               attribution, coordinate_crs, calculation_method, created_at)
            VALUES (?, ?, ?, '2026-10-01', 'https://example.test', 'https://example.test/license',
                    'source', 'EPSG:4326', 'interior', NOW(6))
            """, release, "a".repeat(64), "b".repeat(64));
        Long batch = jdbc.queryForObject("SELECT batch_id FROM region_map_coordinate_batch WHERE region_release_id = ?", Long.class, release);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO region_map_coordinate VALUES (?, ?, '1111051500', 'BOUNDARY_INTERIOR', 127.0, 37.5)
                """, batch, release)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO region_map_coordinate VALUES (?, ?, '1111051500', 'MISSING_BOUNDARY', 37.5, 126.9)
                """, batch, release)).isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO region_map_coordinate VALUES (?, ?, '1111051500', 'MISSING_BOUNDARY', NULL, NULL)", batch, release);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_map_coordinate WHERE batch_id = ?", Integer.class, batch)).isEqualTo(1);
    }
}
