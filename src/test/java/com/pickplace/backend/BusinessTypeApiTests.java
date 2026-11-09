package com.pickplace.backend;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.businesstype.BusinessTypeQueryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트마다 넣은 행을 롤백해 다른 테스트와 서로 영향을 주지 않게 한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class BusinessTypeApiTests {

	private static final String BUSINESS_TYPES_URL = "/api/business-types";
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 15, 7, 12, 34);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	// 한 요청이 게시 정보를 몇 번 읽는지 세기 위해 실제 저장소를 감싼다. 동작은 그대로다.
	@MockitoSpyBean
	private BusinessTypeQueryRepository businessTypeQueryRepository;

	private RegionTestData regions;
	private IndustryTestData industries;

	// 임시 DB의 지역·업종 기준정보를 비워 테스트마다 같은 조건에서 시작한다.
	@BeforeEach
	void clearReferenceTables() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		regions = new RegionTestData(jdbc);
		industries = new IndustryTestData(jdbc);
		regions.clear();
		industries.clear();
		jdbc.update("DELETE FROM administrative_dong");
		jdbc.update("DELETE FROM business_type");
		jdbc.update("DELETE FROM district");
		clearInvocations(businessTypeQueryRepository);
	}

	// 게시본에 연결된 분류의 지원 소분류만 표시 순서대로 나오고, 이름은 원천명이 아니라 표시명인지 확인한다.
	@Test
	void returnsSupportedSmallTypesOfPublishedClassificationInDisplayOrder() throws Exception {
		long classificationId = publishedClassification("release-a", "class-a");
		industries.small(classificationId, "I21001", "빵/도넛(원천)", "빵/도넛", true, 3);
		industries.small(classificationId, "I21201", "카페(원천)", "카페", true, 1);
		industries.small(classificationId, "I21002", "미지원 업종", "미지원 업종", false, 0);
		industries.small(classificationId, "I21007", "김밥/만두/분식(원천)", "김밥/만두/분식", true, 2);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21201", "I21007", "I21001")))
				.andExpect(jsonPath("$.data.items[*].businessTypeName", contains("카페", "김밥/만두/분식", "빵/도넛")))
				.andExpect(jsonPath("$.data.items[*].supported", contains(true, true, true)))
				// 항목에는 코드·이름·지원 여부 세 칸만 있고, 원천명·표시 순서 같은 관리용 컬럼은 없다.
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(3)))
				// 업종 목록 meta는 requestId만 있다. 지역 출처 정보를 복사해 넣지 않는다.
				.andExpect(jsonPath("$.meta", aMapWithSize(1)))
				.andExpect(jsonPath("$.meta.requestId").exists());
	}

	// 표시 순서가 같은 업종끼리는 코드순으로 나오는지 확인한다.
	@Test
	void sortsByCodeWhenDisplayOrderIsSame() throws Exception {
		long classificationId = publishedClassification("release-a", "class-a");
		industries.small(classificationId, "I21201", "카페", "카페", true, 1);
		industries.small(classificationId, "I21001", "빵/도넛", "빵/도넛", true, 1);
		industries.small(classificationId, "I21007", "김밥/만두/분식", "김밥/만두/분식", true, 0);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21007", "I21001", "I21201")));
	}

	// 번호가 더 큰(더 최근) 승인 분류본이 있어도 게시본에 연결된 분류본의 업종만 나오는지 확인한다.
	@Test
	void ignoresOtherApprovedClassifications() throws Exception {
		long linked = publishedClassification("release-a", "class-a");
		industries.small(linked, "I21201", "카페", "카페", true, 1);
		long newer = industries.classification("class-b", "APPROVED");
		industries.small(newer, "I21007", "김밥/만두/분식", "김밥/만두/분식", true, 1);
		assertThat(newer).isGreaterThan(linked);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21201")));
	}

	// 다른 분류본에 연결된 릴리스를 새로 게시하면 다음 요청부터 그 분류본의 업종이 나오는지 확인한다.
	@Test
	void followsClassificationOfNewlyPublishedRelease() throws Exception {
		long first = publishedClassification("release-a", "class-a");
		industries.small(first, "I21201", "카페", "카페", true, 1);
		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21201")));

		long secondRelease = regions.release("release-b", "APPROVED");
		long second = industries.classification("class-b", "APPROVED");
		industries.small(second, "I21007", "김밥/만두/분식", "김밥/만두/분식", true, 1);
		industries.link(secondRelease, second);
		regions.publish(secondRelease);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21007")));
	}

	// 서울 업종 테이블(CS 코드)의 지원 업종은 섞이지 않는지 확인한다.
	@Test
	void doesNotUseSeoulBusinessTypeTable() throws Exception {
		insertSeoulBusinessType("CS100010", "커피·음료");
		long classificationId = publishedClassification("release-a", "class-a");
		industries.small(classificationId, "I21201", "카페", "카페", true, 1);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[*].businessTypeCode", contains("I21201")));
	}

	// 연결된 분류본에 지원 업종이 하나도 없으면 오류가 아니라 200과 빈 목록인지 확인한다.
	@Test
	void returnsEmptyItemsWhenNoTypeIsSupported() throws Exception {
		long classificationId = publishedClassification("release-a", "class-a");
		industries.small(classificationId, "I21201", "카페", "카페", false, 1);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	// 지역 기준정보가 아직 게시 전이면 빈 목록이 아니라 503 REFERENCE_DATA_NOT_READY인지 확인한다.
	// 서울 업종이나 승인 분류본이 DB에 있어도 그것으로 대신하지 않는다.
	@Test
	void returns503WhenNothingIsPublished() throws Exception {
		insertSeoulBusinessType("CS100010", "커피·음료");
		long release = regions.release("release-a", "APPROVED");
		long classificationId = industries.classification("class-a", "APPROVED");
		industries.small(classificationId, "I21201", "카페", "카페", true, 1);
		industries.link(release, classificationId);

		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.error.code").value("REFERENCE_DATA_NOT_READY"))
				.andExpect(jsonPath("$.error.details").isEmpty())
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	// 게시본의 검증 보고서에 분류 번호가 없거나 정수가 아니면 다른 분류본으로 대신하지 않고 500인지 확인한다.
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"classificationId\": null}", "{\"classificationId\": \"%d\"}",
			"{\"classificationId\": 1.5}", "{\"classificationId\": -1}"})
	void returns500WhenClassificationLinkIsMissingOrMalformed(String reportTemplate) throws Exception {
		long release = regions.release("release-a", "APPROVED");
		long classificationId = industries.classification("class-a", "APPROVED");
		industries.small(classificationId, "I21201", "카페", "카페", true, 1);
		industries.report(release, reportTemplate.formatted(classificationId));
		regions.publish(release);

		expectInternalError();
	}

	// 게시본이 존재하지 않는 분류본을 가리키면 500인지 확인한다.
	@Test
	void returns500WhenLinkedClassificationDoesNotExist() throws Exception {
		long release = regions.release("release-a", "APPROVED");
		long other = industries.classification("class-a", "APPROVED");
		industries.small(other, "I21201", "카페", "카페", true, 1);
		industries.link(release, other + 1000);
		regions.publish(release);

		expectInternalError();
	}

	// 게시본이 승인 전(READY) 분류본을 가리키면 500인지 확인한다.
	@Test
	void returns500WhenLinkedClassificationIsNotApproved() throws Exception {
		long release = regions.release("release-a", "APPROVED");
		long classificationId = industries.classification("class-a", "READY");
		industries.link(release, classificationId);
		regions.publish(release);

		expectInternalError();
	}

	// 이 경로는 파라미터가 없으므로 무엇을 붙여도 400이고, 붙인 이름을 응답에 되돌려주지 않는지 확인한다.
	@ParameterizedTest
	@ValueSource(strings = {"supported", "regionVersion", "classificationId"})
	void rejectsAnyQueryParameter(String name) throws Exception {
		mockMvc.perform(get(BUSINESS_TYPES_URL).param(name, "x"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("query"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_VALUE"))
				.andExpect(content().string(not(containsString(name))));

		verify(businessTypeQueryRepository, never()).findPublishedClassification();
	}

	// 한 요청은 게시 정보를 한 번만 읽고, 그때 정한 분류 번호로 목록을 조회하는지 확인한다.
	@Test
	void readsPublicationOncePerRequest() throws Exception {
		long classificationId = publishedClassification("release-a", "class-a");
		industries.small(classificationId, "I21201", "카페", "카페", true, 1);

		mockMvc.perform(get(BUSINESS_TYPES_URL)).andExpect(status().isOk());

		verify(businessTypeQueryRepository, times(1)).findPublishedClassification();
		verify(businessTypeQueryRepository, times(1)).findSupportedBusinessTypes(classificationId);
		verify(businessTypeQueryRepository, times(1)).findSupportedBusinessTypes(anyLong());
	}

	// OpenAPI 문서에 지원 업종 목록 API가 포함되는지 확인한다.
	@Test
	void openApiDocumentIncludesBusinessTypesApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(BUSINESS_TYPES_URL)));
	}

	// 승인 지역 릴리스와 승인 분류본을 만들고 연결한 뒤 게시한다. 분류본 번호를 돌려준다.
	private long publishedClassification(String releaseVersion, String classificationVersion) {
		long release = regions.release(releaseVersion, "APPROVED");
		long classificationId = industries.classification(classificationVersion, "APPROVED");
		industries.link(release, classificationId);
		regions.publish(release);
		return classificationId;
	}

	// 500 INTERNAL_ERROR이고 내부 번호·SQL이 응답에 드러나지 않는지 확인한다.
	private void expectInternalError() throws Exception {
		mockMvc.perform(get(BUSINESS_TYPES_URL))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(content().string(not(containsString("classificationId"))))
				.andExpect(content().string(not(containsString("releaseId"))));
	}

	// 서울 업종 테이블에 지원 업종 한 행을 넣는다.
	private void insertSeoulBusinessType(String code, String displayName) {
		jdbc.update("""
				INSERT INTO business_type (business_type_code, source_business_type_name, display_name, is_supported,
				    display_order, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, ?, TRUE, 0, 'test-version', ?, ?, ?)
				""", code, displayName, displayName, COLLECTED_AT, COLLECTED_AT, COLLECTED_AT);
	}

}
