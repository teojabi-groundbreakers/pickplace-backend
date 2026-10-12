package com.pickplace.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class PickplaceBackendApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${local.server.port}")
	private int port;

	@Test
	void contextLoads() {
		assertThat(dataSource).isNotNull();
	}

	@Test
	void connectsToIsolatedMySql() {
		assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.11");
	}

	@Test
	void healthEndpointReportsUp() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}
	}

}
