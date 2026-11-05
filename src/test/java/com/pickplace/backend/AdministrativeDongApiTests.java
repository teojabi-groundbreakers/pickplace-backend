package com.pickplace.backend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.pickplace.backend.region.AdministrativeDong;
import com.pickplace.backend.region.AdministrativeDongItem;
import com.pickplace.backend.region.AdministrativeDongRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
class AdministrativeDongApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 15, 7, 8, 42);
	private static final String DONGS_URL = "/api/districts/{districtCode}/administrative-dongs";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ReferenceDataImporter importer;

	@Autowired
	private ReferenceSnapshotLoader loader;

	@Autowired
	private AdministrativeDongRepository administrativeDongRepository;

	// 임시 DB의 기준정보를 비워 테스트마다 같은 조건에서 시작한다.
	@BeforeEach
	void clearReferenceTables() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		jdbc.update("DELETE FROM administrative_dong");
		jdbc.update("DELETE FROM business_type");
		jdbc.update("DELETE FROM district");
	}

	// 요청한 자치구의 동만 이름순으로 나오고, 다른 구의 동과 관리용 컬럼은 섞이지 않는지 확인한다.
	@Test
	void returnsOnlyDongsOfRequestedDistrictSortedByName() throws Exception {
		insertDistrict("11110", "종로구");
		insertDistrict("11140", "중구");
		insertDong("11110515", "청운효자동", "11110");
		insertDong("11110600", "가회동", "11110");
		insertDong("11140520", "소공동", "11140");

		mockMvc.perform(get(DONGS_URL, "11110"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].dongCode").value("11110600"))
				.andExpect(jsonPath("$.data.items[0].dongName").value("가회동"))
				.andExpect(jsonPath("$.data.items[0].districtCode").value("11110"))
				.andExpect(jsonPath("$.data.items[0].officialCodeStatus").value("CURRENT"))
				.andExpect(jsonPath("$.data.items[0].regionVersion").value("test-region"))
				.andExpect(jsonPath("$.data.items[1].dongName").value("청운효자동"))
				.andExpect(jsonPath("$.data.items[0].officialDongCode").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].collectedAt").doesNotExist())
				.andExpect(jsonPath("$.meta.requestId").exists());
	}

	// 실제 기준정보 스냅샷을 적재하면 종로구의 17개 동이 스냅샷과 같게 이름순으로 나오는지 확인한다.
	@Test
	void returnsReviewedDongsOfDistrictFromSnapshot() throws Exception {
		ReferenceSnapshot snapshot = loader.load();
		importer.importSnapshot(snapshot);
		List<String> expectedCodes = snapshot.dongs().stream()
				.filter(dong -> dong.districtCode().equals("11110"))
				.sorted(Comparator.comparing(ReferenceSnapshot.Dong::name).thenComparing(ReferenceSnapshot.Dong::code))
				.map(ReferenceSnapshot.Dong::code)
				.toList();

		String body = mockMvc.perform(get(DONGS_URL, "11110"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode items = JSON.readTree(body).path("data").path("items");
		List<String> actualCodes = items.valueStream().map(item -> item.path("dongCode").asString()).toList();
		assertThat(actualCodes).hasSize(17).isEqualTo(expectedCodes);
		assertThat(items.valueStream().map(item -> item.path("districtCode").asString())).containsOnly("11110");
	}

	// 공식 코드가 말소된 동(LEGACY)도 목록에 포함하고 상태로 구분하는지 확인한다.
	@Test
	void includesLegacyDongWithItsStatus() throws Exception {
		importer.importSnapshot(loader.load());

		mockMvc.perform(get(DONGS_URL, "11680"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.dongCode == '11680740')].dongName", contains("일원2동")))
				.andExpect(jsonPath("$.data.items[?(@.dongCode == '11680740')].officialCodeStatus", contains("LEGACY")));
	}

	// 동이 하나도 없는 정상 자치구는 404가 아니라 200과 빈 목록인지 확인한다.
	@Test
	void returnsEmptyItemsForDistrictWithoutDongs() throws Exception {
		insertDistrict("11110", "종로구");

		mockMvc.perform(get(DONGS_URL, "11110"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	// 형식은 맞지만 등록되지 않은 자치구는 404 REGION_NOT_FOUND인지 확인한다.
	@Test
	void returnsNotFoundForUnregisteredDistrict() throws Exception {
		insertDistrict("11110", "종로구");

		mockMvc.perform(get(DONGS_URL, "11999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REGION_NOT_FOUND"))
				.andExpect(jsonPath("$.error.details[0].field").value("districtCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"));
	}

	// 숫자 5자리가 아닌 자치구 코드는 400 INVALID_REGION_CODE인지 확인한다(4·6자리 경계값, 문자 포함).
	@ParameterizedTest
	@ValueSource(strings = {"1111", "111100", "abcde", "1111a"})
	void rejectsMalformedDistrictCode(String districtCode) throws Exception {
		mockMvc.perform(get(DONGS_URL, districtCode))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"))
				.andExpect(jsonPath("$.error.details[0].field").value("districtCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString(districtCode))));
	}

	// 자치구 코드 자리에 공백만 보내면(값 누락) 400 INVALID_REGION_CODE인지 확인한다.
	@Test
	void rejectsBlankDistrictCode() throws Exception {
		mockMvc.perform(get(DONGS_URL, " "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"));
	}

	// 자치구 코드 없이 빈 경로로 요청하면 없는 주소로 보아 공통 오류 형식의 404 NOT_FOUND인지 확인한다(서버 오류 아님).
	@Test
	void missingDistrictCodeIsNotFoundPath() throws Exception {
		mockMvc.perform(get("/api/districts//administrative-dongs"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.meta.requestId").exists());
	}

	// 동 목록을 응답으로 바꿀 때 자치구 행을 추가로 읽지 않는지(지연 로딩 유지) 확인한다.
	@Test
	void mappingDongsDoesNotLoadDistrictRow() {
		insertDistrict("11110", "종로구");
		insertDong("11110515", "청운효자동", "11110");

		List<AdministrativeDong> dongs = administrativeDongRepository.findAllByDistrictDistrictCodeOrderByDongNameAscDongCodeAsc("11110");
		AdministrativeDongItem item = AdministrativeDongItem.from(dongs.getFirst());

		assertThat(item.districtCode()).isEqualTo("11110");
		assertThat(Hibernate.isInitialized(dongs.getFirst().getDistrict())).isFalse();
	}

	// OpenAPI 문서에 행정동 목록 API가 포함되는지 확인한다.
	@Test
	void openApiDocumentIncludesDongsApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/districts/{districtCode}/administrative-dongs")));
	}

	// 테스트용 자치구 한 행을 넣는다.
	private void insertDistrict(String code, String name) {
		jdbc.update("""
				INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, 'test-version', ?, ?, ?)
				""", code, name, COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

	// 테스트용 행정동 한 행을 CURRENT 상태로 넣는다. CURRENT는 공식 코드·명칭·기준일이 필수다(V1 CHECK).
	private void insertDong(String code, String name, String districtCode) {
		jdbc.update("""
				INSERT INTO administrative_dong (dong_code, dong_name, district_code, region_version,
				    official_dong_code, official_dong_name, official_code_status, reference_snapshot_on,
				    collected_at, created_at, updated_at)
				VALUES (?, ?, ?, 'test-region', ?, ?, 'CURRENT', ?, ?, ?, ?)
				""", code, name, districtCode, code + "00", name, LocalDate.of(2026, 10, 12),
				COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

}
