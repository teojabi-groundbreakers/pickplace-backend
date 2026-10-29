package com.pickplace.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class HealthApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Value("${local.server.port}")
	private int port;

	// 실제 서버에서 생존 확인이 공통 성공 형식이고, 본문과 헤더의 requestId가 같은지 확인한다.
	@Test
	void livenessReturnsCommonSuccessFormat() throws Exception {
		HttpResponse<String> response = get("/api/health");
		JsonNode body = JSON.readTree(response.body());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(body.path("data").path("status").asString()).isEqualTo("UP");
		String requestId = body.path("meta").path("requestId").asString();
		assertThat(requestId).isNotBlank();
		assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
	}

	// 실제 임시 MySQL에 연결된 상태에서 준비 확인이 200 UP이고, DB 정보가 응답에 없는지 확인한다.
	@Test
	void readinessReturnsUpWithRealDatabase() throws Exception {
		HttpResponse<String> response = get("/api/health/ready");
		JsonNode body = JSON.readTree(response.body());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(body.path("data").path("status").asString()).isEqualTo("UP");
		assertThat(response.body()).doesNotContain("mysql", "jdbc", "8.4");
	}

	// 실제 서버에서 없는 주소가 공통 오류 형식의 404로 오는지 확인한다.
	@Test
	void unknownPathReturnsCommonErrorFormat() throws Exception {
		HttpResponse<String> response = get("/api/does-not-exist");
		JsonNode body = JSON.readTree(response.body());

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(body.path("error").path("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(body.path("error").path("details").isArray()).isTrue();
		assertThat(body.path("meta").path("requestId").asString()).isNotBlank();
	}

	// 테스트 서버에 GET 요청을 보내고 응답을 돌려준다.
	private HttpResponse<String> get(String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

}
