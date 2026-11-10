package com.pickplace.backend.ingestion.statistics.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.ResultActions;

import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import com.pickplace.backend.summary.SummaryEvidenceReader;
import com.pickplace.backend.summary.SummaryReadContext;

import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.REGION;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.load;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.prepare;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 작은 실제 원본을 기준정보 → 정제 → 적재 → 승인·게시까지 거친 뒤 현황 API를 호출한다.
// 현황 조회는 자체 읽기 트랜잭션(REQUIRES_NEW)을 열어 커밋된 자료만 보므로, 테스트를 트랜잭션으로 감싸지 않고 앞뒤로 비운다.
// 일부 결측·보류 상태는 실제 원본으로 만들기 어려워 게시 뒤 해당 행만 SQL로 바꿔 근거 조회를 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class SummaryApiTests {

	private static final String CAFE = "I21201";
	private static final String BAKERY = "I21001";
	private static final String GROUP = "1111000000";
	private static final String ISO_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private NationalReferenceImporter references;
	@Autowired
	private NationalReferenceLoader loader;
	@Autowired
	private NationalReferencePublisher regionPublisher;
	@Autowired
	private NationalStatisticsImporter importer;
	@Autowired
	private NationalSummaryPublisher publisher;

	// 조회 도중 다른 게시가 일어나는 상황을 만들기 위해 실제 근거 조회기를 감싼다. 평소 동작은 그대로다.
	@MockitoSpyBean
	private SummaryEvidenceReader evidenceReader;

	@TempDir
	private Path temp;
	private int sequence;

	// 임시 DB의 기준정보·통계·게시 기록을 FK 순서대로 비운다.
	@BeforeEach
	@AfterEach
	void cleanup() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		reset(evidenceReader);
		for (String table : List.of("national_summary_publication_event", "national_summary_publication", "national_summary_release",
				"national_quality_issue", "store_industry_count", "store_region_coverage", "store_snapshot_entry",
				"resident_population_month", "national_source_artifact", "national_import_batch", "region_publication_event",
				"region_publication", "region_stat_mapping", "region_navigation", "official_region_node", "region_source_artifact",
				"region_release", "source_region_reference", "service_region")) {
			if (table.equals("national_source_artifact")) {
				jdbc.update("DELETE FROM national_source_artifact WHERE artifact_kind = 'ZIP_MEMBER'");
			}
			jdbc.update("DELETE FROM " + table);
		}
		for (String level : List.of("SMALL", "MIDDLE", "LARGE")) {
			jdbc.update("DELETE FROM national_industry_code WHERE industry_level = ?", level);
		}
		jdbc.update("DELETE FROM national_industry_release");
	}

	// 기본 묶음이 게시되면 두 지표의 값·단위·상태·출처와 기준월·게시 시각이 명세 형식대로 나오는지 확인한다.
	@Test
	void returnsBothMetricsWithSourcesForPublishedDefault() throws Exception {
		publishDefault(bundle("202609"));

		MvcResult result = summary(CAFE)
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.data", aMapWithSize(3)))
				.andExpect(jsonPath("$.data.region.regionCode").value(REGION))
				.andExpect(jsonPath("$.data.region.regionName").value("청운효자동"))
				.andExpect(jsonPath("$.data.businessType.businessTypeCode").value(CAFE))
				.andExpect(jsonPath("$.data.businessType.businessTypeName").value("카페"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.value").value(100))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.unit").value("PERSON"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation", hasKey("reasonCode")))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.reasonCode").value(nullValue()))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source.datasetCode").value("POPULATION_MONTH"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source.referenceOn").value("2026-09-30"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source", hasKey("snapshotLabel")))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source.snapshotLabel").value(nullValue()))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source.collectedAt").value("2026-11-08T12:25:07Z"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.value").value(1))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.unit").value("STORE"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.datasetCode").value("STORE_SNAPSHOT"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.referenceOn").value("2026-09-30"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.snapshotLabel").value(not(emptyString())))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.collectedAt").value("2026-11-08T12:25:07Z"))
				.andExpect(jsonPath("$.meta", aMapWithSize(3)))
				.andExpect(jsonPath("$.meta.referenceMonth").value("202609"))
				.andExpect(jsonPath("$.meta.publishedAt").value(matchesPattern(ISO_UTC)))
				.andReturn();
		// 응답 meta의 추적 ID는 응답 헤더 X-Request-Id와 같아야 한다.
		assertThat(result.getResponse().getContentAsString())
				.contains("\"requestId\":\"" + result.getResponse().getHeader("X-Request-Id") + "\"");
	}

	// 유효한 수록 범위에서 해당 업종만 없으면 null이 아니라 실제 0인지 확인한다.
	@Test
	void returnsObservedZeroForIndustryWithoutStores() throws Exception {
		publishDefault(bundle("202609"));

		summary(BAKERY)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.businessType.businessTypeName").value("빵/도넛"))
				.andExpect(metric("listedStoreCount", 0, "AVAILABLE", null));
	}

	// 업종 집계에 영향을 주는 행이 있으면 내부 채택 수가 있어도 업소는 null·COUNT_INCOMPLETE이고 인구는 그대로인지 확인한다.
	// 다른 업종은 영향이 없으므로 정상 0으로 남는다.
	@Test
	void keepsPopulationWhenStoreCountIsIncomplete() throws Exception {
		publishDefault(bundle("202609", List.of(store("S1"), store("BAD-ID"))));

		summary(CAFE)
				.andExpect(status().isOk())
				.andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "COUNT_INCOMPLETE"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.datasetCode").value("STORE_SNAPSHOT"))
				.andExpect(metric("registeredPopulation", 100, "AVAILABLE", null));
		summary(BAKERY).andExpect(metric("listedStoreCount", 0, "AVAILABLE", null));
	}

	// 업종을 확정하지 못한 행은 지역 전체에 영향을 주므로 그 지역의 모든 업종이 보류인지 확인한다.
	@Test
	void regionWideIssueHoldsEveryIndustry() throws Exception {
		List<String> unknownIndustry = new ArrayList<>(store("S2"));
		unknownIndustry.set(7, "I99999");
		publishDefault(bundle("202609", List.of(store("S1"), unknownIndustry)));

		summary(BAKERY).andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "COUNT_INCOMPLETE"));
		summary(CAFE).andExpect(metric("registeredPopulation", 100, "AVAILABLE", null));
	}

	// 집계 행이 없어도 지역 전체 영향 문제가 있으면 0이 아니라 보류인지 확인한다.
	@Test
	void regionIssueHoldsEvenWithoutCountRow() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("DELETE FROM store_industry_count WHERE batch_id = ? AND industry_code = ?", bundle.stores(), BAKERY);
		summary(BAKERY).andExpect(metric("listedStoreCount", 0, "AVAILABLE", null));

		insertIssue(bundle.stores(), "REGION");
		summary(BAKERY).andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "COUNT_INCOMPLETE"));
	}

	// 두 지표가 모두 결측이어도 기본 묶음이 있으면 503이 아니라 200이고 필드·출처를 유지하는지 확인한다.
	@Test
	void returns200WhenBothMetricsAreMissing() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("""
				UPDATE resident_population_month SET row_status = 'SUPPRESSED', reason_code = 'SOURCE_SUPPRESSED',
				    total_population = NULL, male_population = NULL, female_population = NULL, age_sex_counts = NULL
				WHERE batch_id = ?
				""", bundle.population());
		jdbc.update("DELETE FROM store_region_coverage WHERE batch_id = ?", bundle.stores());

		summary(CAFE)
				.andExpect(status().isOk())
				.andExpect(metric("registeredPopulation", null, "UNAVAILABLE", "SOURCE_SUPPRESSED"))
				.andExpect(metric("listedStoreCount", null, "NO_DATA", "SOURCE_REGION_ABSENT"))
				.andExpect(jsonPath("$.data.metrics.registeredPopulation.source.referenceOn").value("2026-09-30"))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.source.referenceOn").value("2026-09-30"))
				.andExpect(jsonPath("$.meta.referenceMonth").value("202609"));
	}

	// 원천 지역은 있는데 인구 행이 없으면 SOURCE_ROW_ABSENT인지 확인한다.
	@Test
	void missingPopulationRowIsSourceRowAbsent() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("DELETE FROM resident_population_month WHERE batch_id = ?", bundle.population());

		summary(CAFE).andExpect(metric("registeredPopulation", null, "NO_DATA", "SOURCE_ROW_ABSENT"));
	}

	// 수치는 정상이어도 인구 집계에 영향을 주는 품질 문제가 기록됐으면 공개하지 않는지 확인한다.
	@Test
	void populationWithCountAffectingIssueIsQualityFailure() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		insertIssue(bundle.population(), "REGION");

		summary(CAFE)
				.andExpect(metric("registeredPopulation", null, "UNAVAILABLE", "QUALITY_CHECK_FAILED"))
				.andExpect(metric("listedStoreCount", 1, "AVAILABLE", null));
	}

	// 인구 행의 시점 검토가 끝나지 않았으면 숫자가 있어도 공개하지 않고, 업소는 따로 판정하는지 확인한다.
	@Test
	void pendingPopulationBoundaryIsUnavailable() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("UPDATE resident_population_month SET temporal_region_status = 'PENDING' WHERE batch_id = ?", bundle.population());

		summary(CAFE)
				.andExpect(metric("registeredPopulation", null, "UNAVAILABLE", "BOUNDARY_REVIEW_PENDING"))
				.andExpect(metric("listedStoreCount", 1, "AVAILABLE", null));
	}

	// 업소 수록 범위의 시점 상태가 보류·불일치면 그 사유로 보류하고 인구는 그대로인지 확인한다.
	@ParameterizedTest
	@CsvSource({"PENDING, BOUNDARY_REVIEW_PENDING", "BOUNDARY_MISMATCH, BOUNDARY_MISMATCH"})
	void storeCoverageBoundaryIsUnavailable(String temporalStatus, String reason) throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("""
				UPDATE store_region_coverage SET temporal_region_status = ?, coverage_status = 'HOLD', reason_code = ?
				WHERE batch_id = ?
				""", temporalStatus, reason, bundle.stores());

		summary(CAFE)
				.andExpect(metric("listedStoreCount", null, "UNAVAILABLE", reason))
				.andExpect(metric("registeredPopulation", 100, "AVAILABLE", null));
	}

	// 업소 원천 지역의 H10 대응이 확정되지 않았으면 결측으로 숨기지 않고 MAPPING_UNRESOLVED인지 확인한다.
	@Test
	void unresolvedStoreMappingIsUnavailable() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("""
				UPDATE store_region_coverage SET region_code = NULL, temporal_region_status = 'PENDING', coverage_status = 'HOLD',
				    reason_code = 'MAPPING_UNRESOLVED'
				WHERE batch_id = ?
				""", bundle.stores());

		summary(CAFE).andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "MAPPING_UNRESOLVED"));
	}

	// 대응표 검토가 끝나지 않은 업소 원천이면 MAPPING_UNRESOLVED인지 확인한다.
	@Test
	void unverifiedStoreMappingIsUnavailable() throws Exception {
		publishDefault(bundle("202609"));
		jdbc.update("UPDATE region_stat_mapping SET review_status = 'PENDING' WHERE source_system = 'SEMAS_STORE'");

		summary(CAFE).andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "MAPPING_UNRESOLVED"));
	}

	// 현재 선택 분류와 현황 묶음 분류가 다르면 업소만 CLASSIFICATION_MISMATCH이고 정상 인구는 유지되는지 확인한다.
	@Test
	void differentClassificationHoldsOnlyStores() throws Exception {
		publishDefault(bundle("202609"));
		selectRegions(bundle("202610"), 1);

		summary(CAFE)
				.andExpect(status().isOk())
				.andExpect(metric("listedStoreCount", null, "UNAVAILABLE", "CLASSIFICATION_MISMATCH"))
				.andExpect(metric("registeredPopulation", 100, "AVAILABLE", null))
				.andExpect(jsonPath("$.meta.referenceMonth").value("202609"));
	}

	// 현황 묶음의 대응 릴리스에서는 현행이 아닌 지역이면 그 시점 인구를 이 지역 값으로 쓰지 않는지 확인한다.
	@Test
	void regionNotCurrentInMappingReleaseIsBoundaryMismatch() throws Exception {
		Bundle old = bundle("202609");
		publishDefault(old);
		selectRegions(bundle("202610"), 1);
		jdbc.update("""
				UPDATE official_region_node SET official_status = 'LEGACY', raw_abolished_on = '20260801', abolished_on = '2026-08-01'
				WHERE region_code = ? AND release_id = (SELECT release_id FROM region_release WHERE release_version = ?)
				""", REGION, old.regionVersion());

		summary(CAFE).andExpect(metric("registeredPopulation", null, "UNAVAILABLE", "BOUNDARY_MISMATCH"));
	}

	// 조회 도중 새 선택 기준·기본 묶음이 게시돼도 한 응답은 처음 고정한 묶음만 쓰는지 확인한다.
	@Test
	void concurrentPublicationDoesNotMixBundlesWithinRequest() throws Exception {
		Bundle old = bundle("202609");
		publishDefault(old);
		Bundle next = bundle("202610");
		publisher.publishMonth(next.version(), new Expected(0, null), null, "test", "next month", false);
		try (var executor = Executors.newSingleThreadExecutor()) {
			doAnswer(invocation -> {
				executor.submit(() -> {
					regionPublisher.publish(next.regionVersion(), 1, "test", "new selection", false);
					publisher.setDefault(next.version(), new Expected(1, old.id()), "test", "new default");
				}).get(10, TimeUnit.SECONDS);
				return invocation.callRealMethod();
			}).when(evidenceReader).population(any(SummaryReadContext.class));

			summary(CAFE)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.meta.referenceMonth").value("202609"))
					.andExpect(metric("listedStoreCount", 1, "AVAILABLE", null));
		}
		reset(evidenceReader);
		summary(CAFE).andExpect(jsonPath("$.meta.referenceMonth").value("202610"));
	}

	// 지역 기준정보 미게시와 기본 현황 묶음 미게시가 서로 다른 503 코드인지 확인한다.
	@Test
	void referenceAndSummaryNotReadyAreDifferent503() throws Exception {
		summary(CAFE)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("REFERENCE_DATA_NOT_READY"));

		selectRegions(bundle("202609"), 0);
		summary(CAFE)
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.error.code").value("SUMMARY_DATA_NOT_READY"))
				.andExpect(jsonPath("$.error.details").isEmpty())
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	// 현행 읍·면·동이 아닌 지역, 현재 분류에 없는 업종, 지원하지 않는 업종이 각각 다른 오류인지 확인한다.
	@Test
	void distinguishesNotFoundRegionAndBusinessTypeFromUnsupported() throws Exception {
		publishDefault(bundle("202609"));

		for (String regionCode : List.of(GROUP, "9999999999")) {
			mockMvc.perform(get("/api/regions/{regionCode}/summary", regionCode).param("businessTypeCode", CAFE))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("REGION_NOT_FOUND"))
					.andExpect(jsonPath("$.error.details[0].field").value("regionCode"))
					.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"));
		}
		summary("I99999")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("BUSINESS_TYPE_NOT_FOUND"))
				.andExpect(jsonPath("$.error.details[0].field").value("businessTypeCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"));

		jdbc.update("UPDATE national_industry_code SET is_supported = FALSE, support_release_status = NULL WHERE industry_code = ?", BAKERY);
		summary(BAKERY)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("BUSINESS_TYPE_NOT_SUPPORTED"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_SUPPORTED"));
	}

	// 입력 오류는 명세 순서(미정의·중복 → 업종 누락 → 지역 형식 → 업종 형식)대로 하나만 알리는지 확인한다.
	@ParameterizedTest
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"1111051500 | businessTypeCode=I21201&month=202609 | INVALID_REQUEST     | query            | INVALID_VALUE",
			"1111051500 | businessTypeCode=I21201&regionVersion=v1 | INVALID_REQUEST | query            | INVALID_VALUE",
			"1111051500 | businessTypeCode=I21201&businessTypeCode=I21201 | INVALID_REQUEST | businessTypeCode | INVALID_VALUE",
			"abc        | businessTypeCode=I21201&quarter=20263 | INVALID_REQUEST  | query            | INVALID_VALUE",
			"1111051500 | NULL                                  | INVALID_REQUEST     | businessTypeCode | REQUIRED",
			"1111051500 | businessTypeCode=                     | INVALID_REQUEST     | businessTypeCode | REQUIRED",
			"abc        | NULL                                  | INVALID_REQUEST     | businessTypeCode | REQUIRED",
			"abc        | businessTypeCode=CS100010             | INVALID_REGION_CODE | regionCode       | INVALID_FORMAT",
			"11110515   | businessTypeCode=I21201               | INVALID_REGION_CODE | regionCode       | INVALID_FORMAT",
			"1111051500 | businessTypeCode=CS100010             | INVALID_REQUEST     | businessTypeCode | INVALID_FORMAT",
			"1111051500 | businessTypeCode=%20I21201            | INVALID_REQUEST     | businessTypeCode | INVALID_FORMAT",
			"1111051500 | businessTypeCode=i21201               | INVALID_REQUEST     | businessTypeCode | INVALID_FORMAT"})
	void rejectsInvalidInputInSpecifiedOrder(String regionCode, String query, String code, String field, String reason)
			throws Exception {
		String url = "/api/regions/" + regionCode + "/summary" + (query == null ? "" : "?" + query);
		mockMvc.perform(get(java.net.URI.create(url)))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.details.length()").value(1))
				.andExpect(jsonPath("$.error.details[0].field").value(field))
				.andExpect(jsonPath("$.error.details[0].reason").value(reason))
				.andExpect(content().string(not(containsString("month"))))
				.andExpect(content().string(not(containsString("quarter"))));
	}

	// GET 전용 경로에 POST를 보내면 공통 처리대로 405와 Allow 헤더가 오는지 확인한다.
	@Test
	void returns405WithAllowHeader() throws Exception {
		mockMvc.perform(post("/api/regions/{regionCode}/summary", REGION).param("businessTypeCode", CAFE))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
	}

	// OpenAPI 문서에 현황 API 경로와 업종 파라미터가 포함되는지 확인한다.
	@Test
	void openApiDocumentIncludesSummaryApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/regions/{regionCode}/summary")))
				.andExpect(content().string(containsString("businessTypeCode")))
				.andExpect(content().string(containsString("SUMMARY_DATA_NOT_READY")));
	}

	// 현황 API를 호출한다.
	private ResultActions summary(String businessTypeCode) throws Exception {
		return mockMvc.perform(get("/api/regions/{regionCode}/summary", REGION).param("businessTypeCode", businessTypeCode));
	}

	// 한 지표의 값·상태·사유를 확인한다. value와 reasonCode는 null이어도 키가 있어야 한다.
	private static org.springframework.test.web.servlet.ResultMatcher metric(String name, Integer value, String status,
			String reason) {
		String path = "$.data.metrics." + name;
		return result -> {
			jsonPath(path, hasKey("value")).match(result);
			jsonPath(path, hasKey("reasonCode")).match(result);
			jsonPath(path + ".value").value(value == null ? nullValue() : org.hamcrest.Matchers.is(value)).match(result);
			jsonPath(path + ".status").value(status).match(result);
			jsonPath(path + ".reasonCode").value(reason == null ? nullValue() : org.hamcrest.Matchers.is(reason)).match(result);
		};
	}

	// 집계에 영향을 주는 품질 문제 한 건을 넣는다. 업종 단위 영향이 아니라 지역 전체 영향이다.
	private void insertIssue(long batchId, String scope) {
		jdbc.update("""
				INSERT INTO national_quality_issue (batch_id, field_name, reason_code, severity, affects_count, impact_scope,
				    affected_region_code, evidence)
				VALUES (?, 'test-field', 'TEST_COUNT_ISSUE', 'HOLD', TRUE, ?, ?, '{}')
				""", batchId, scope, REGION);
	}

	// 한 달 치 기준정보를 적재·승인하고 인구·업소 배치를 적재해 승인된 현황 묶음을 만든다(게시는 하지 않음).
	private Bundle bundle(String month) throws Exception {
		return bundle(month, List.of(store("S1")));
	}

	// 업소 원본 행을 지정해 현황 묶음을 만든다.
	private Bundle bundle(String month, List<List<String>> stores) throws Exception {
		var fixture = prepare(temp.resolve("fixture-" + sequence++), month, stores);
		references.importSnapshot(loader.load(fixture.reference(), NationalRawValidator.sha256(fixture.reference())));
		regionPublisher.approve(fixture.snapshot().version(), "test");
		long population = importer.importData(load(fixture, "POPULATION_MONTH")).batchId();
		long storeBatch = importer.importData(load(fixture, "STORE_SNAPSHOT")).batchId();
		String version = "summary-api-" + sequence;
		long id = publisher.prepare(version, population, storeBatch);
		publisher.approve(version, "test");
		return new Bundle(id, version, fixture.snapshot().version(), population, storeBatch);
	}

	// 지역 선택 기준(NATIONWIDE_SELECTION)을 이 묶음의 지역 릴리스로 게시한다.
	private void selectRegions(Bundle bundle, long expectedRevision) {
		regionPublisher.publish(bundle.regionVersion(), expectedRevision, "test", "selection", false);
	}

	// 처음 게시하는 경우로 지역 선택 기준·월별 포인터·DEFAULT 포인터를 차례로 게시한다.
	private void publishDefault(Bundle bundle) {
		selectRegions(bundle, 0);
		publisher.publishMonth(bundle.version(), new Expected(0, null), null, "test", "month", false);
		publisher.setDefault(bundle.version(), new Expected(0, null), "test", "default");
	}

	private record Bundle(long id, String version, String regionVersion, long population, long stores) {
	}

}
