package com.pickplace.backend;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.reference.ReferenceDataImporter;
import com.pickplace.backend.reference.ReferenceSnapshotLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트마다 넣은 행을 롤백해 다른 테스트(일반 서버는 기준정보 0건)와 서로 영향을 주지 않게 한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class BusinessTypeApiTests {

	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 15, 7, 12, 34);
	private static final String BUSINESS_TYPES_URL = "/api/business-types";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ReferenceDataImporter importer;

	@Autowired
	private ReferenceSnapshotLoader loader;

	// 임시 DB의 기준정보를 비워 테스트마다 같은 조건에서 시작한다.
	@BeforeEach
	void clearReferenceTables() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		jdbc.update("DELETE FROM administrative_dong");
		jdbc.update("DELETE FROM business_type");
		jdbc.update("DELETE FROM district");
	}

	// 지원 업종만 표시 순서대로 나오고, 이름은 표시명이며 원천명·표시 순서 같은 관리용 컬럼은 응답에 없는지 확인한다.
	@Test
	void returnsOnlySupportedTypesInDisplayOrder() throws Exception {
		insertBusinessType("CS100005", "제과점", "제과점", true, 3);
		insertBusinessType("CS100001", "한식음식점", "한식음식점", false, 0);
		insertBusinessType("CS100010", "커피-음료", "커피·음료", true, 1);
		insertBusinessType("CS100008", "분식전문점", "분식", true, 2);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].businessTypeCode").value("CS100010"))
				.andExpect(jsonPath("$.data.items[0].businessTypeName").value("커피·음료"))
				.andExpect(jsonPath("$.data.items[0].supported").value(true))
				.andExpect(jsonPath("$.data.items[1].businessTypeCode").value("CS100008"))
				.andExpect(jsonPath("$.data.items[1].businessTypeName").value("분식"))
				.andExpect(jsonPath("$.data.items[2].businessTypeCode").value("CS100005"))
				.andExpect(jsonPath("$.data.items[0].sourceBusinessTypeName").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].displayOrder").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].collectedAt").doesNotExist())
				.andExpect(jsonPath("$.meta.requestId").exists());
	}

	// 표시 순서가 같은 업종끼리는 코드순으로 나오는지 확인한다.
	@Test
	void sortsByCodeWhenDisplayOrderIsSame() throws Exception {
		insertBusinessType("CS100010", "커피-음료", "커피·음료", true, 1);
		insertBusinessType("CS100002", "중식음식점", "중식음식점", true, 1);
		insertBusinessType("CS100008", "분식전문점", "분식", true, 0);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("CS100008", "CS100002", "CS100010")));
	}

	// 실제 기준정보 스냅샷(업종 100개)을 적재하면 초기 지원 3업종만 표시 순서대로 나오는지 확인한다.
	@Test
	void returnsInitialThreeSupportedTypesFromSnapshot() throws Exception {
		importer.importSnapshot(loader.load());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_type", Integer.class)).isEqualTo(100);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("CS100010", "CS100008", "CS100005")))
				.andExpect(jsonPath("$.data.items[*].businessTypeName", contains("커피·음료", "분식", "제과점")));
	}

	// DB에서 지원 여부를 바꾸면 다음 요청 결과에 바로 반영되는지 확인한다.
	@Test
	void reflectsSupportChangeOnNextRequest() throws Exception {
		importer.importSnapshot(loader.load());
		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("CS100010", "CS100008", "CS100005")));

		jdbc.update("UPDATE business_type SET is_supported = FALSE WHERE business_type_code = 'CS100005'");
		jdbc.update("UPDATE business_type SET is_supported = TRUE, display_order = 4 WHERE business_type_code = 'CS100001'");

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("CS100010", "CS100008", "CS100001")));
	}

	// 쿼리 파라미터를 붙여도 미지원 업종이 노출되지 않는지 확인한다.
	@Test
	void ignoresQueryParametersAndNeverExposesUnsupportedTypes() throws Exception {
		insertBusinessType("CS100010", "커피-음료", "커피·음료", true, 1);
		insertBusinessType("CS100001", "한식음식점", "한식음식점", false, 0);

		mockMvc.perform(get(BUSINESS_TYPES_URL).param("supported", "false"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("CS100010")));
	}

	// 저장된 업종이 있어도 지원 업종이 하나도 없으면 200과 빈 목록인지 확인한다.
	@Test
	void returnsEmptyItemsWhenNoTypeIsSupported() throws Exception {
		insertBusinessType("CS100001", "한식음식점", "한식음식점", false, 0);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	// 업종이 하나도 적재되지 않았으면 오류가 아니라 200과 빈 목록인지 확인한다.
	@Test
	void returnsEmptyItemsWhenNoTypeExists() throws Exception {
		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	// OpenAPI 문서에 지원 업종 목록 API가 포함되는지 확인한다.
	@Test
	void openApiDocumentIncludesBusinessTypesApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(BUSINESS_TYPES_URL)));
	}

	// 테스트용 업종 한 행을 넣는다.
	private void insertBusinessType(String code, String sourceName, String displayName, boolean supported, int displayOrder) {
		jdbc.update("""
				INSERT INTO business_type (business_type_code, source_business_type_name, display_name, is_supported,
				    display_order, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'test-version', ?, ?, ?)
				""", code, sourceName, displayName, supported, displayOrder, COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

}
