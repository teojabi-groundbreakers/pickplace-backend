package com.pickplace.backend.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTests {

	private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseHealthChecker databaseHealthChecker;

	// 생존 확인이 200 UP과 서버가 만든 requestId를 돌려주는지 확인한다.
	@Test
	void livenessReturnsUpWithRequestId() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("UP"))
				.andExpect(jsonPath("$.meta.requestId").value(matchesPattern(UUID_PATTERN)))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
	}

	// DB 확인이 성공하면 준비 확인이 200 UP인지 확인한다.
	@Test
	void readinessReturnsUpWhenDatabaseIsReachable() throws Exception {
		mockMvc.perform(get("/api/health/ready"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("UP"));
	}

	// DB 연결이 실패하면 준비 확인이 503이고, DB 주소가 응답에 없는지 확인한다.
	@Test
	void readinessReturns503WhenDatabaseIsDown() throws Exception {
		doThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection: jdbc:mysql://secret-host:3307"))
				.when(databaseHealthChecker).check();

		mockMvc.perform(get("/api/health/ready"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("DATA_SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.meta.requestId").exists())
				.andExpect(content().string(not(containsString("jdbc"))))
				.andExpect(content().string(not(containsString("secret-host"))));
	}

	// DB 연결이 실패해도 생존 확인은 200 UP인지 확인한다.
	@Test
	void livenessStaysUpWhenDatabaseIsDown() throws Exception {
		doThrow(new CannotGetJdbcConnectionException("DB down")).when(databaseHealthChecker).check();

		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("UP"));
	}

	// 안전한 형식의 X-Request-Id를 보내면 그 값이 그대로 돌아오는지 확인한다.
	@Test
	void safeRequestIdHeaderIsEchoed() throws Exception {
		mockMvc.perform(get("/api/health").header("X-Request-Id", "fe-req_01"))
				.andExpect(header().string("X-Request-Id", "fe-req_01"))
				.andExpect(jsonPath("$.meta.requestId").value("fe-req_01"));
	}

	// 64자 requestId는 허용되는지 확인한다(경계값).
	@Test
	void requestIdOf64CharsIsAccepted() throws Exception {
		String max = "a".repeat(64);
		mockMvc.perform(get("/api/health").header("X-Request-Id", max))
				.andExpect(jsonPath("$.meta.requestId").value(max));
	}

	// 65자 requestId는 새 UUID로 바뀌는지 확인한다(경계값).
	@Test
	void requestIdOf65CharsIsReplaced() throws Exception {
		mockMvc.perform(get("/api/health").header("X-Request-Id", "a".repeat(65)))
				.andExpect(jsonPath("$.meta.requestId").value(matchesPattern(UUID_PATTERN)));
	}

	// 공백·특수문자가 섞인 requestId는 새 UUID로 바뀌는지 확인한다.
	@Test
	void requestIdWithUnsafeCharactersIsReplaced() throws Exception {
		mockMvc.perform(get("/api/health").header("X-Request-Id", "abc def;<script>"))
				.andExpect(jsonPath("$.meta.requestId").value(matchesPattern(UUID_PATTERN)))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
	}

}
