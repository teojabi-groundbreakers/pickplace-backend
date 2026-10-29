package com.pickplace.backend;

import java.sql.SQLException;
import java.time.LocalDateTime;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
class ReferenceSchemaMigrationTests {

	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 16, 0, 0);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Flyway flyway;

	@BeforeEach
	void insertParentDistrict() {
		assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		jdbcTemplate.update("""
				INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
				VALUES ('11110', '테스트구', 'test-v1', ?, ?, ?)
				""", COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

	@Test
	void createsReferenceTablesAndRecordsSuccessfulMigration() {
		assertThat(jdbcTemplate.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = DATABASE() ORDER BY table_name
				""", String.class))
				.contains("district", "administrative_dong", "business_type", "flyway_schema_history");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1
				""", Integer.class)).isEqualTo(1);
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
		assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
		assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
		assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
	}

	@Test
	void repeatedMigrationDoesNotReapplySqlOrChangeExistingData() {
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT district_name FROM district WHERE district_code = '11110'
				""", String.class)).isEqualTo("테스트구");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void rejectsDuplicatePrimaryKey() {
		assertRejected(1062, "INSERT INTO district SELECT * FROM district WHERE district_code = '11110'");
		insertDong("11110515", "11110", "UNVERIFIED", null, null, null);
		assertRejected(1062, "INSERT INTO administrative_dong SELECT * FROM administrative_dong WHERE dong_code = '11110515'");
		insertBusinessType("CS100010");
		assertRejected(1062, "INSERT INTO business_type SELECT * FROM business_type WHERE business_type_code = 'CS100010'");
	}

	@Test
	void storesCurrentLegacyAndUnverifiedRegionsWithoutChangingSourceCodes() {
		insertDong("11110515", "11110", "CURRENT", "1111051500", "공식동", null);
		insertDong("11110516", "11110", "LEGACY", "1111051600", "과거동", "2025-07-01");
		insertDong("11110517", "11110", "UNVERIFIED", null, null, null);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM administrative_dong", Integer.class))
				.isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT official_dong_code FROM administrative_dong WHERE dong_code = '11110516'
				""", String.class)).isEqualTo("1111051600");
	}

	@Test
	void rejectsUnknownParentDistrict() {
		assertViolation(() -> insertDong("11110515", "99999", "UNVERIFIED", null, null, null), 1452);
	}

	@ParameterizedTest
	@ValueSource(strings = {"123", "1111A", "1111 "})
	void rejectsMalformedDistrictCode(String code) {
		assertRejected("UPDATE district SET district_code = ? WHERE district_code = '11110'", code);
	}

	@ParameterizedTest
	@ValueSource(strings = {"123", "1111051A", "1111051 "})
	void rejectsMalformedDongCode(String code) {
		assertViolation(() -> insertDong(code, "11110", "UNVERIFIED", null, null, null), 3819);
	}

	@Test
	void rejectsInconsistentOfficialRegionMetadata() {
		assertViolation(() -> insertDong("11110515", "11110", "CURRENT", "1111051500", "공식동", "2025-07-01"), 3819);
		assertViolation(() -> insertDong("11110515", "11110", "LEGACY", "1111051500", "공식동", null), 3819);
		assertViolation(() -> insertDong("11110515", "11110", "CURRENT", null, null, null), 3819);
		assertViolation(() -> insertDong("11110515", "11110", "UNVERIFIED", "1111051500", "공식동", null), 3819);
	}

	@Test
	void rejectsInvalidOfficialCodeStatusAndDates() {
		insertDong("11110515", "11110", "CURRENT", "1111051500", "공식동", null);
		assertRejected("UPDATE administrative_dong SET official_dong_code = 'invalid' WHERE dong_code = '11110515'");
		assertRejected("UPDATE administrative_dong SET official_code_status = 'UNKNOWN' WHERE dong_code = '11110515'");
		assertRejected("UPDATE administrative_dong SET reference_snapshot_on = NULL WHERE dong_code = '11110515'");
		assertRejected("""
				UPDATE administrative_dong SET official_code_status = 'LEGACY',
				official_created_on = '2025-01-01', official_abolished_on = '2024-01-01'
				WHERE dong_code = '11110515'
				""");
	}

	@Test
	void preventsDeletingOrChangingReferencedParent() {
		insertDong("11110515", "11110", "UNVERIFIED", null, null, null);
		assertRejected(1451, "DELETE FROM district WHERE district_code = '11110'");
		assertRejected(1451, "UPDATE district SET district_code = '11111' WHERE district_code = '11110'");
	}

	@Test
	void businessTypeDefaultsToUnsupportedAndKeepsSourceAndDisplayNames() {
		insertBusinessType("CS100010");
		assertThat(jdbcTemplate.queryForObject("SELECT is_supported FROM business_type", Boolean.class)).isFalse();
		jdbcTemplate.update("UPDATE business_type SET display_name = '커피·음료', is_supported = TRUE, display_order = 1");
		assertThat(jdbcTemplate.queryForObject("SELECT source_business_type_name FROM business_type", String.class))
				.isEqualTo("커피-음료");
		assertRejected("UPDATE business_type SET is_supported = 2");
		assertRejected("UPDATE business_type SET display_order = -1");
		assertRejected("UPDATE business_type SET display_name = '   '");
	}

	@ParameterizedTest
	@ValueSource(strings = {"cs100010", "CS10001A", "CS10001 ", "100010"})
	void rejectsMalformedBusinessTypeCode(String code) {
		assertViolation(() -> insertBusinessType(code), 3819);
	}

	@Test
	void rejectsMissingRequiredFieldsAndBlankNames() {
		assertRejected(1048, "UPDATE district SET district_name = NULL WHERE district_code = '11110'");
		assertRejected("UPDATE district SET district_name = '   ' WHERE district_code = '11110'");
		assertRejected(1048, "UPDATE district SET collected_at = NULL WHERE district_code = '11110'");
		insertDong("11110515", "11110", "UNVERIFIED", null, null, null);
		assertRejected("UPDATE administrative_dong SET dong_name = '   '");
		insertBusinessType("CS100010");
		assertRejected("UPDATE business_type SET source_business_type_name = '   '");
	}

	private void insertDong(String code, String districtCode, String status, String officialCode,
			String officialName, String abolishedOn) {
		jdbcTemplate.update("""
				INSERT INTO administrative_dong (dong_code, dong_name, district_code, region_version,
				    official_dong_code, official_dong_name, official_code_status, official_abolished_on,
				    reference_snapshot_on, collected_at, created_at, updated_at)
				VALUES (?, '테스트동', ?, 'test-region-v1', ?, ?, ?, ?, ?, ?, ?, ?)
				""", code, districtCode, officialCode, officialName, status, abolishedOn,
				"UNVERIFIED".equals(status) ? null : "2026-10-12", COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

	private void insertBusinessType(String code) {
		jdbcTemplate.update("""
				INSERT INTO business_type (business_type_code, source_business_type_name, display_name,
				    data_version, collected_at, created_at, updated_at)
				VALUES (?, '커피-음료', '커피-음료', 'test-v1', ?, ?, ?)
				""", code, COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

	private void assertRejected(String sql, Object... args) {
		assertRejected(3819, sql, args);
	}

	private void assertRejected(int errorCode, String sql, Object... args) {
		assertViolation(() -> jdbcTemplate.update(sql, args), errorCode);
	}

	private void assertViolation(ThrowingCallable action, int errorCode) {
		assertThatThrownBy(action).isInstanceOf(DataAccessException.class)
				.rootCause().isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
	}
}
