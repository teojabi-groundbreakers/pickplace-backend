package com.pickplace.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.RegionTestData.Region;
import com.pickplace.backend.region.RegionQueryRepository;
import com.pickplace.backend.region.SelectionLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class RegionApiTests {

	private static final String PROVINCES_URL = "/api/regions/provinces";
	private static final String GROUPS_URL = "/api/regions/groups";
	private static final String LOCALITIES_URL = "/api/regions/localities";

	private static final String SEOUL = "1100000000";
	private static final String SEJONG = "3600000000";
	private static final String GYEONGGI = "4100000000";
	private static final String JONGNO = "1111000000";
	private static final String JUNG = "1114000000";
	private static final String YONGSAN = "1117000000";
	private static final String SEONGDONG_LEGACY = "1120000000";
	private static final String SEJONG_GROUP = "3611000000";
	private static final String SUWON_CITY = "4111000000";
	private static final String SUWON_JANGAN = "4111100000";
	private static final String BUSAN_PENDING = "2600000000";
	private static final String GYEONGBUK_UNPUBLISHED = "4700000000";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	// 각 요청이 게시 정보를 몇 번 읽는지 세기 위해 실제 저장소를 감싼다. 동작은 그대로다.
	@MockitoSpyBean
	private RegionQueryRepository regionQueryRepository;

	private RegionTestData data;
	private long publishedRelease;
	private long approvedButUnpublishedRelease;

	// 게시본 하나와 게시되지 않은 버전 두 개를 준비한다. 화면에 보이면 안 되는 지역도 일부러 섞는다.
	@BeforeEach
	void prepareReleases() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		data = new RegionTestData(jdbc);
		data.clear();

		publishedRelease = data.release("test-v1", "APPROVED");
		data.insert(publishedRelease,
				Region.province(SEOUL, "서울특별시"),
				Region.province(SEJONG, "세종특별자치시"),
				Region.province(GYEONGGI, "경기도"),
				Region.province(BUSAN_PENDING, "부산광역시").pendingReview(),
				Region.group(JONGNO, "종로구", SEOUL),
				Region.group(JUNG, "중구", SEOUL),
				Region.group(YONGSAN, "용산구", SEOUL),
				Region.group(SEONGDONG_LEGACY, "성동구", SEOUL).legacy(),
				Region.group(SEJONG_GROUP, "세종특별자치시", SEJONG).autoSelected(),
				Region.group(SUWON_CITY, "수원시", GYEONGGI).hidden("CITY_CONTAINER"),
				Region.group(SUWON_JANGAN, "수원시 장안구", GYEONGGI).officialParent(SUWON_CITY),
				Region.locality("1111053000", "사직동", SEOUL, JONGNO),
				Region.locality("1111051500", "청운효자동", SEOUL, JONGNO),
				Region.locality("1111059000", "가회동", SEOUL, JONGNO).legacy(),
				Region.locality("1111060000", "가나동", SEOUL, JONGNO).invalidDate(),
				Region.locality("1111061000", "가다동", SEOUL, JONGNO).pendingReview(),
				Region.locality("1111062000", "가라출장소", SEOUL, JONGNO).hidden("BRANCH"),
				Region.locality("1114055000", "명동", SEOUL, JUNG),
				Region.locality("1114052000", "소공동", SEOUL, JUNG),
				Region.locality("1114051500", "소공동", SEOUL, JUNG),
				Region.locality("3611025000", "조치원읍", SEJONG, SEJONG_GROUP),
				Region.locality("4111151000", "파장동", GYEONGGI, SUWON_JANGAN));
		data.publish(publishedRelease);

		// 게시 전(READY) 버전과 승인됐지만 게시하지 않은 버전이다. 번호가 더 커도 화면에 섞이면 안 된다.
		long readyRelease = data.release("test-v2", "READY");
		data.insert(readyRelease, Region.province(BUSAN_PENDING, "부산광역시"));
		approvedButUnpublishedRelease = data.release("test-v3", "APPROVED");
		data.insert(approvedButUnpublishedRelease, Region.province(GYEONGBUK_UNPUBLISHED, "경상북도"));
	}

	// 시·도는 게시본의 화면 선택 대상만 코드순으로 나오고, 다른 버전·검토 전 시·도는 섞이지 않는지 확인한다.
	@Test
	void returnsPublishedProvincesInCodeOrder() throws Exception {
		mockMvc.perform(get(PROVINCES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].regionCode").value(SEOUL))
				.andExpect(jsonPath("$.data.items[1].regionCode").value(SEJONG))
				.andExpect(jsonPath("$.data.items[2].regionCode").value(GYEONGGI))
				.andExpect(jsonPath("$.data.items[0].regionName").value("서울특별시"))
				.andExpect(jsonPath("$.data.items[0].selectionLevel").value("PROVINCE"))
				.andExpect(jsonPath("$.data.items[0].autoSelect").value(false))
				// 시·도의 화면 부모는 없으므로 필드를 빼지 않고 null로 보낸다.
				.andExpect(jsonPath("$.data.items[0]", hasKey("parentCode")))
				.andExpect(jsonPath("$.data.items[0].parentCode").value(nullValue()));
	}

	// 응답 한 줄은 계약의 5개 필드만, meta는 요청 ID와 출처 3개만 담고 내부 버전·상태는 내보내지 않는지 확인한다.
	@Test
	void exposesOnlyContractFieldsAndSourceMeta() throws Exception {
		MvcResult result = mockMvc.perform(get(PROVINCES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(5)))
				.andExpect(jsonPath("$.meta", aMapWithSize(4)))
				.andExpect(jsonPath("$.meta.codeSystem").value("MOIS_H10"))
				.andExpect(jsonPath("$.meta.sourceReferenceOn").value(RegionTestData.SOURCE_REFERENCE_ON))
				.andExpect(jsonPath("$.meta.sourceFileVersion").value(RegionTestData.SOURCE_FILE_VERSION))
				.andExpect(jsonPath("$.meta.regionVersion").doesNotExist())
				.andExpect(content().string(not(containsString("test-v1"))))
				.andExpect(content().string(not(containsString("VERIFIED"))))
				.andReturn();

		// 응답 meta의 requestId와 응답 헤더 X-Request-Id가 같은 값이어야 로그를 추적할 수 있다.
		String headerRequestId = result.getResponse().getHeader("X-Request-Id");
		assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + headerRequestId + "\"");
	}

	// 그룹은 이름순(코드순 아님)이고 과거(말소) 그룹은 빠지며 화면 부모 코드가 시·도인지 확인한다.
	@Test
	void returnsGroupsInNameOrderWithoutLegacy() throws Exception {
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", SEOUL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].regionName").value("용산구"))
				.andExpect(jsonPath("$.data.items[1].regionName").value("종로구"))
				.andExpect(jsonPath("$.data.items[2].regionName").value("중구"))
				.andExpect(jsonPath("$.data.items[0].selectionLevel").value("GROUP"))
				.andExpect(jsonPath("$.data.items[0].parentCode").value(SEOUL));
	}

	// 수원시처럼 화면 대상이 아닌 도시 묶음은 빠지고, 수원시 장안구의 부모는 공식 부모(수원시)가 아닌 화면 부모(경기도)인지 확인한다.
	@Test
	void usesSelectionParentNotOfficialParent() throws Exception {
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", GYEONGGI))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].regionCode").value(SUWON_JANGAN))
				.andExpect(jsonPath("$.data.items[0].regionName").value("수원시 장안구"))
				.andExpect(jsonPath("$.data.items[0].parentCode").value(GYEONGGI));

		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", SUWON_JANGAN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].regionName").value("파장동"))
				.andExpect(jsonPath("$.data.items[0].parentCode").value(SUWON_JANGAN));
	}

	// 세종 그룹만 autoSelect가 true이고, 그 아래 읍면동은 false인지 확인한다.
	@Test
	void marksSejongGroupAsAutoSelect() throws Exception {
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", SEJONG))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].regionCode").value(SEJONG_GROUP))
				.andExpect(jsonPath("$.data.items[0].autoSelect").value(true));

		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", SEJONG_GROUP))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].regionName").value("조치원읍"))
				.andExpect(jsonPath("$.data.items[0].selectionLevel").value("LOCALITY"))
				.andExpect(jsonPath("$.data.items[0].autoSelect").value(false));
	}

	// 읍면동은 이름순이고 과거·날짜 오류·검토 전·출장소는 선택 가능 표시가 있어도 빠지는지 확인한다.
	@Test
	void returnsLocalitiesInNameOrderExcludingHiddenRegions() throws Exception {
		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", JONGNO))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].regionName").value("사직동"))
				.andExpect(jsonPath("$.data.items[1].regionName").value("청운효자동"))
				.andExpect(jsonPath("$.data.items[0].parentCode").value(JONGNO));
	}

	// 같은 이름의 지역은 코드순으로 정렬되는지 확인한다.
	@Test
	void sortsSameNameByCode() throws Exception {
		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", JUNG))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].regionName").value("명동"))
				.andExpect(jsonPath("$.data.items[1].regionCode").value("1114051500"))
				.andExpect(jsonPath("$.data.items[2].regionCode").value("1114052000"));
	}

	// 하위 지역이 없는 정상 그룹은 404가 아니라 200과 빈 배열인지 확인한다.
	@Test
	void returnsEmptyListForValidParentWithoutChildren() throws Exception {
		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", YONGSAN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.items", empty()));
	}

	// 없는 코드, 역할이 다른 코드(시·도에 그룹 코드 등), 화면 대상이 아닌 코드, 게시되지 않은 버전에만 있는 코드는 404인지 확인한다.
	@ParameterizedTest
	@CsvSource({
			"/api/regions/groups, provinceCode, 9999999999",
			"/api/regions/groups, provinceCode, 1111000000",
			"/api/regions/groups, provinceCode, 2600000000",
			"/api/regions/groups, provinceCode, 4700000000",
			"/api/regions/localities, groupCode, 1100000000",
			"/api/regions/localities, groupCode, 4111000000",
			"/api/regions/localities, groupCode, 1120000000",
			"/api/regions/localities, groupCode, 1111051500"
	})
	void returns404ForParentNotShownInRequestedLevel(String url, String field, String code) throws Exception {
		mockMvc.perform(get(url).param(field, code))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REGION_NOT_FOUND"))
				.andExpect(jsonPath("$.error.details[0].field").value(field))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"));
	}

	// 필수 코드가 없으면 400 INVALID_REQUEST(REQUIRED)인지 확인한다.
	@Test
	void returns400WhenParentCodeIsMissing() throws Exception {
		mockMvc.perform(get(GROUPS_URL))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("provinceCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
	}

	// 숫자 10자리가 아닌 코드(9·11자리, 문자, 공백, 빈 값, 전각 숫자)는 400 INVALID_REGION_CODE인지 확인한다.
	@ParameterizedTest
	@ValueSource(strings = {"110000000", "11000000000", "11000000ab", " 1100000000", "1100000000 ", "", "１１００００００００"})
	void returns400ForInvalidRegionCodeFormat(String code) throws Exception {
		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"))
				.andExpect(jsonPath("$.error.details[0].field").value("groupCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"));
	}

	// 같은 파라미터를 두 번 보내면 값이 같아도 400 INVALID_REQUEST(INVALID_VALUE)인지 확인한다.
	@Test
	void returns400ForDuplicatedParameter() throws Exception {
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", SEOUL, SEOUL))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("provinceCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_VALUE"));
	}

	// 정해지지 않은 파라미터(버전 파라미터 포함)는 400이고, 보낸 파라미터 이름을 응답에 되돌려주지 않는지 확인한다.
	@Test
	void returns400ForUndefinedParameterWithoutEchoingName() throws Exception {
		mockMvc.perform(get(PROVINCES_URL).param("unexpectedName", "1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("query"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_VALUE"))
				.andExpect(content().string(not(containsString("unexpectedName"))));

		mockMvc.perform(get(GROUPS_URL).param("provinceCode", SEOUL).param("regionVersion", "test-v1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.details[0].field").value("query"));
	}

	// 게시 포인터가 없으면 정상 입력은 503 REFERENCE_DATA_NOT_READY이고, 형식이 틀린 입력은 여전히 400인지 확인한다.
	@Test
	void returns503WhenNothingIsPublished() throws Exception {
		data.unpublish();

		for (RequestBuilder request : new RequestBuilder[] {
				get(PROVINCES_URL), get(GROUPS_URL).param("provinceCode", SEOUL), get(LOCALITIES_URL).param("groupCode", JONGNO)}) {
			mockMvc.perform(request)
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.error.code").value("REFERENCE_DATA_NOT_READY"))
					.andExpect(jsonPath("$.error.message").value("지역 기준정보가 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."))
					.andExpect(jsonPath("$.error.details", empty()));
		}

		mockMvc.perform(get(GROUPS_URL).param("provinceCode", "11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"));
	}

	// 게시본을 다른 승인 버전으로 바꾸면 다음 요청부터 새 버전만 보이고 이전 버전 지역은 섞이지 않는지 확인한다.
	@Test
	void reflectsPublicationSwitchOnNextRequest() throws Exception {
		data.publish(approvedButUnpublishedRelease);

		mockMvc.perform(get(PROVINCES_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(1))
				.andExpect(jsonPath("$.data.items[0].regionCode").value(GYEONGBUK_UNPUBLISHED));

		mockMvc.perform(get(GROUPS_URL).param("provinceCode", SEOUL))
				.andExpect(status().isNotFound());
	}

	// 게시본에 출처 파일 정보가 없는 비정상 상태는 500이고, 내부 사유를 응답에 노출하지 않는지 확인한다.
	@Test
	void returns500WithoutDetailsWhenPublishedReleaseLacksSource() throws Exception {
		data.deleteHistoryArtifact(publishedRelease);

		mockMvc.perform(get(PROVINCES_URL))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("releaseId"))));
	}

	// 성공·오류 응답 모두 브라우저 저장을 막는 Cache-Control: no-store가 붙는지 확인한다.
	@Test
	void addsNoStoreHeaderToSuccessAndErrorResponses() throws Exception {
		mockMvc.perform(get(PROVINCES_URL))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		mockMvc.perform(get(GROUPS_URL).param("provinceCode", "9999999999"))
				.andExpect(status().isNotFound())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
	}

	// 기존 서울 API에는 no-store를 붙이지 않아 기존 동작이 바뀌지 않는지 확인한다.
	@Test
	void doesNotChangeExistingDistrictApiHeaders() throws Exception {
		mockMvc.perform(get("/api/districts"))
				.andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
	}

	// GET 전용 경로에 POST를 보내면 공통 처리대로 405와 Allow 헤더가 오는지 확인한다.
	@Test
	void returns405WithAllowHeader() throws Exception {
		mockMvc.perform(post(PROVINCES_URL))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
	}

	// 시·도 조회는 게시 정보 1번 + 목록 1번, 하위 조회는 게시 정보 1번 + 부모 확인 1번 + 목록 1번만 읽는지 확인한다.
	// 저장소 메서드 하나가 SQL 한 문장이므로 호출 횟수가 곧 SQL 횟수다. 게시 정보를 한 번만 읽어야 버전이 섞이지 않는다.
	@Test
	void readsPublicationOncePerRequest() throws Exception {
		mockMvc.perform(get(PROVINCES_URL)).andExpect(status().isOk());
		verify(regionQueryRepository, times(1)).findPublishedRelease();
		verify(regionQueryRepository, times(1)).findProvinces(anyLong());
		verify(regionQueryRepository, never()).existsSelectableRegion(anyLong(), anyString(), any(SelectionLevel.class));

		clearInvocations(regionQueryRepository);
		mockMvc.perform(get(LOCALITIES_URL).param("groupCode", JONGNO)).andExpect(status().isOk());
		verify(regionQueryRepository, times(1)).findPublishedRelease();
		verify(regionQueryRepository, times(1)).existsSelectableRegion(publishedRelease, JONGNO, SelectionLevel.GROUP);
		verify(regionQueryRepository, times(1)).findChildren(publishedRelease, SelectionLevel.LOCALITY, JONGNO);
	}

	// Swagger 문서(OpenAPI)에 새 경로 3개가 나오는지 확인한다.
	@Test
	void includesRegionApisInOpenApiDocument() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(PROVINCES_URL)))
				.andExpect(content().string(containsString(GROUPS_URL)))
				.andExpect(content().string(containsString(LOCALITIES_URL)));
	}

}
