package com.pickplace.backend;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DB 장애 중의 실제 요청 흐름을 확인하므로 테스트 전체를 트랜잭션으로 감싸지 않는다.
// 감싸면 요청 전에 이미 DB 연결이 열려 있어 연결 실패 상황을 재현할 수 없다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class BusinessTypeDatabaseFailureTests {

	@Autowired
	private MockMvc mockMvc;

	// 임시 MySQL은 다른 테스트와 함께 쓰므로 끄지 않고, 연결을 얻는 단계만 실패하게 만든다.
	@MockitoSpyBean
	private DataSource dataSource;

	// 테스트마다 DB 연결 요청이 모두 실패하는 상태로 시작한다.
	@BeforeEach
	void simulateDatabaseDown() throws SQLException {
		doThrow(new SQLException("Communications link failure: jdbc:mysql://secret-host:3307"))
				.when(dataSource).getConnection();
	}

	// DB 연결에 실패하면 빈 목록으로 위장하지 않고 503 DATA_SERVICE_UNAVAILABLE이며 DB 주소가 응답에 없는지 확인한다.
	@Test
	void returns503WhenDatabaseIsDown() throws Exception {
		mockMvc.perform(get("/api/business-types"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("DATA_SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(content().string(not(containsString("jdbc"))))
				.andExpect(content().string(not(containsString("secret-host"))));
	}

}
