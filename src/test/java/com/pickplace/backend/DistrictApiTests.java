package com.pickplace.backend;

import java.time.LocalDateTime;
import java.util.List;

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
import com.pickplace.backend.reference.ReferenceSnapshot;
import com.pickplace.backend.reference.ReferenceSnapshotLoader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
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
class DistrictApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 15, 7, 8, 42);

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

	// 순서를 섞어 넣어도 코드순으로 나오고, 코드·이름 외의 관리용 컬럼은 응답에 없는지 확인한다.
	@Test
	void returnsDistrictsSortedByCodeWithOnlyCodeAndName() throws Exception {
		insertDistrict("11680", "강남구");
		insertDistrict("11110", "종로구");
		insertDistrict("11140", "중구");

		mockMvc.perform(get("/api/districts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].districtCode").value("11110"))
				.andExpect(jsonPath("$.data.items[0].districtName").value("종로구"))
				.andExpect(jsonPath("$.data.items[1].districtCode").value("11140"))
				.andExpect(jsonPath("$.data.items[2].districtCode").value("11680"))
				.andExpect(jsonPath("$.data.items[0].dataVersion").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].collectedAt").doesNotExist())
				.andExpect(jsonPath("$.meta.requestId").exists());
	}

	// 실제 기준정보 스냅샷을 적재하면 25개 자치구가 모두 코드순으로 나오는지 확인한다.
	@Test
	void returnsAllReviewedDistrictsFromSnapshot() throws Exception {
		ReferenceSnapshot snapshot = loader.load();
		importer.importSnapshot(snapshot);
		List<String> expectedCodes = snapshot.districts().stream().map(ReferenceSnapshot.District::code).sorted().toList();

		String body = mockMvc.perform(get("/api/districts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].districtCode").value("11110"))
				.andExpect(jsonPath("$.data.items[0].districtName").value("종로구"))
				.andReturn().getResponse().getContentAsString();

		JsonNode items = JSON.readTree(body).path("data").path("items");
		List<String> actualCodes = items.valueStream().map(item -> item.path("districtCode").asString()).toList();
		assertThat(actualCodes).hasSize(25).isEqualTo(expectedCodes);
	}

	// 자치구가 하나도 없으면 오류가 아니라 200과 빈 목록인지 확인한다.
	@Test
	void returnsEmptyItemsWhenNoDistrictExists() throws Exception {
		mockMvc.perform(get("/api/districts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	// OpenAPI 문서에 자치구 목록 API가 포함되는지 확인한다.
	@Test
	void openApiDocumentIncludesDistrictsApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/districts")));
	}

	// 테스트용 자치구 한 행을 넣는다.
	private void insertDistrict(String code, String name) {
		jdbc.update("""
				INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, 'test-version', ?, ?, ?)
				""", code, name, COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

}
