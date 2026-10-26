package com.pickplace.backend.common.error;

import java.util.List;

import jakarta.validation.constraints.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.response.ErrorDetail;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTests.TestController.class)
@Import(GlobalExceptionHandlerTests.TestController.class)
class GlobalExceptionHandlerTests {

	// 응답에 나오면 안 되는 내부 정보. 예외 메시지에 일부러 넣어 노출 여부를 확인한다.
	private static final String SECRET = "SELECT * FROM secret_table WHERE password='hunter2'";

	@Autowired
	private MockMvc mockMvc;

	@RestController
	static class TestController {

		// 서비스 코드가 ApiException을 던지는 상황을 만든다.
		@GetMapping("/test/api-exception")
		String apiException() {
			throw new ApiException(ErrorCode.REGION_NOT_FOUND, List.of(new ErrorDetail("districtCode", "NOT_REGISTERED")));
		}

		// 숫자 필수 파라미터를 받는다. 누락·타입 불일치 확인용이다.
		@GetMapping("/test/number")
		String number(@RequestParam int value) {
			return "ok";
		}

		// 5자리 숫자만 허용하는 파라미터를 받는다. 검증 실패 확인용이다.
		@GetMapping("/test/validated")
		String validated(@RequestParam @Pattern(regexp = "\\d{5}") String districtCode) {
			return "ok";
		}

		// 내부 정보가 담긴 예상하지 못한 예외를 던진다.
		@GetMapping("/test/internal")
		String internal() {
			throw new IllegalStateException(SECRET);
		}

		// DB 주소가 담긴 DB 접근 예외를 던진다.
		@GetMapping("/test/data-access")
		String dataAccess() {
			throw new DataRetrievalFailureException("jdbc:mysql://secret-host:3307/pickplace " + SECRET);
		}

	}

	// ApiException의 오류 코드·메시지·details가 그대로 응답되는지 확인한다.
	@Test
	void apiExceptionUsesItsErrorCode() throws Exception {
		mockMvc.perform(get("/test/api-exception"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REGION_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value(ErrorCode.REGION_NOT_FOUND.message()))
				.andExpect(jsonPath("$.error.details[0].field").value("districtCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_REGISTERED"))
				.andExpect(jsonPath("$.meta.requestId", notNullValue()));
	}

	// 오류 응답의 requestId가 응답 헤더의 값과 같은지 확인한다.
	@Test
	void errorResponseRequestIdMatchesHeader() throws Exception {
		mockMvc.perform(get("/test/api-exception").header("X-Request-Id", "trace-123"))
				.andExpect(header().string("X-Request-Id", "trace-123"))
				.andExpect(jsonPath("$.meta.requestId").value("trace-123"));
	}

	// 필수 파라미터가 빠지면 400 INVALID_REQUEST와 REQUIRED가 오는지 확인한다.
	@Test
	void missingParameterIsInvalidRequest() throws Exception {
		mockMvc.perform(get("/test/number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("value"))
				.andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
	}

	// 타입이 맞지 않으면 400 INVALID_REQUEST와 INVALID_FORMAT이 오는지 확인한다.
	@Test
	void typeMismatchIsInvalidRequest() throws Exception {
		mockMvc.perform(get("/test/number").param("value", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("value"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"))
				// 사용자가 보낸 값을 응답에 그대로 되돌려주지 않는다.
				.andExpect(content().string(not(containsString("abc"))));
	}

	// 파라미터 검증에 실패하면 400 INVALID_REQUEST와 INVALID_VALUE가 오는지 확인한다.
	@Test
	void parameterValidationFailureIsInvalidRequest() throws Exception {
		mockMvc.perform(get("/test/validated").param("districtCode", "12"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("districtCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_VALUE"));
	}

	// 없는 주소면 404 NOT_FOUND와 빈 details 배열이 오는지 확인한다.
	@Test
	void unknownPathIsNotFound() throws Exception {
		mockMvc.perform(get("/api/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.details").isArray())
				.andExpect(jsonPath("$.error.details").isEmpty());
	}

	// GET 전용 주소에 POST로 요청하면 405 METHOD_NOT_ALLOWED가 오는지 확인한다.
	@Test
	void unsupportedMethodIsMethodNotAllowed() throws Exception {
		mockMvc.perform(post("/test/internal"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	// 예상하지 못한 예외는 500이고, SQL·비밀번호·스택트레이스가 응답에 없는지 확인한다.
	@Test
	void unexpectedExceptionHidesInternalDetails() throws Exception {
		mockMvc.perform(get("/test/internal"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.message()))
				.andExpect(content().string(not(containsString("secret_table"))))
				.andExpect(content().string(not(containsString("hunter2"))))
				.andExpect(content().string(not(containsString("Exception"))))
				.andExpect(content().string(not(containsString("at com."))));
	}

	// DB 접근 실패는 503이고, DB 주소·SQL이 응답에 없는지 확인한다.
	@Test
	void dataAccessFailureIsServiceUnavailableWithoutDbDetails() throws Exception {
		mockMvc.perform(get("/test/data-access"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("DATA_SERVICE_UNAVAILABLE"))
				.andExpect(content().string(not(containsString("jdbc"))))
				.andExpect(content().string(not(containsString("secret-host"))))
				.andExpect(content().string(not(containsString("secret_table"))));
	}

}
