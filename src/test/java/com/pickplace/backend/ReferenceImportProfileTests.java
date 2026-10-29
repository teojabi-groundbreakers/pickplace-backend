package com.pickplace.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "pickplace.reference-import.confirm=true")
@ActiveProfiles({"test", "reference-import"})
@Import(MySqlTestConfiguration.class)
class ReferenceImportProfileTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void confirmedImportProfileCommitsToIsolatedDatabase() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM district", Integer.class)).isEqualTo(25);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM administrative_dong", Integer.class)).isEqualTo(425);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_type", Integer.class)).isEqualTo(100);
	}
}
