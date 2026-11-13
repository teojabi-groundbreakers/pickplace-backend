package com.pickplace.backend.ingestion.statistics.service;

import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.REGION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.Mappings;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.Metric;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.RegionMapping;
import com.pickplace.backend.ingestion.detail.service.DetailBatchService;
import com.pickplace.backend.ingestion.detail.service.DetailInputLoader;
import com.pickplace.backend.ingestion.detail.service.RegionalDetailImporter;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import com.pickplace.backend.trend.TrendQueryRepository;

import tools.jackson.databind.json.JsonMapper;

// 추이 API를 실제 적재 → 승인 → 분기별 게시 흐름으로 만든 작은 서울 상세 통계로 확인한다.
// StatisticsTestFixture(같은 패키지 전용)의 전국 기준정보를 써야 해서 이 패키지에 둔다.
// 일부 보류·정합성 오류 상태는 실제 원본으로 만들기 어려워 게시 뒤 해당 행만 SQL로 바꿔 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class TrendApiTests {

	private static final String TRENDS_URL = "/api/regions/{regionCode}/trends";
	private static final String SOURCE_REGION = "11110515";
	private static final String SOURCE_NAME = "청운효자동";
	private static final String WORKER_METRIC = "tot_wrc_popltn_co";
	private static final String STORE_METRIC = "stor_co";
	private static final String CAFE = "CS100010";
	private static final String SNACK = "CS100008";
	private static final String LISTED_METRIC = "listed_store_count";
	private static final String CAFE_SEMAS = "I21201";
	private static final String BAKERY_SEMAS = "I21001";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private NationalReferenceLoader referenceLoader;
	@Autowired
	private NationalReferenceImporter referenceImporter;
	@Autowired
	private NationalReferencePublisher regionPublisher;
	@Autowired
	private DetailInputLoader loader;
	@Autowired
	private RegionalDetailImporter importer;
	@Autowired
	private DetailBatchService batches;

	// 조회 도중 다른 게시가 일어나는 상황을 만들기 위해 실제 조회기를 감싼다. 평소 동작은 그대로다.
	@MockitoSpyBean
	private TrendQueryRepository repository;

	@TempDir
	private Path temp;
	// 상세 통계 대응표가 묶이는 기준정보 버전이다. 테스트 시작 때 처음 적재한 버전이다.
	private String referenceVersion;
	private int sequence;

	// 임시 DB의 상세 통계·기준정보 기록을 FK 순서대로 비운다. 다른 테스트가 남긴 전국 통계도 기준정보를 참조하므로 함께 지운다.
	@AfterEach
	void cleanup() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		reset(repository);
		for (String table : List.of("regional_statistic_publication_event", "regional_statistic_publication",
				"regional_statistic_issue", "regional_statistic_row", "regional_statistic_artifact", "regional_statistic_batch",
				"national_summary_publication_event", "national_summary_publication", "national_summary_release",
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

	// 테스트마다 DB를 비운 뒤 전국 기준정보를 적재·승인하고 지역 선택 기준으로 게시한다.
	// 두 @BeforeEach의 실행 순서는 보장되지 않으므로 비우기를 여기서 직접 먼저 부른다.
	@BeforeEach
	void prepareReference() throws Exception {
		cleanup();
		referenceVersion = importReference();
		regionPublisher.publish(referenceVersion, 0, "test", "selection", false);
	}

	// 요청 범위의 모든 분기가 연도를 넘어 시간순으로 나오고, 게시 분기는 값(실제 0 포함), 미게시 분기는 null과 사유인지 확인한다.
	@Test
	void returnsEveryQuarterInOrderWithValuesAndNotPublishedGaps() throws Exception {
		long batch = workerBatch(List.of("20244", "20251"), List.of(workerRow("20244", 12), workerRow("20251", 0)), "VERIFIED");
		publish(batch, "20244");
		publish(batch, "20251");

		trend("datasetCode=WORKER&fromQuarter=20243&toQuarter=20252")
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.meta.requestId").isNotEmpty())
				.andExpect(jsonPath("$.data.region.regionCode").value(REGION))
				.andExpect(jsonPath("$.data.region.regionName").value(SOURCE_NAME))
				.andExpect(jsonPath("$.data.dataset.sourceSystem").value("SEOUL_COMMERCIAL"))
				.andExpect(jsonPath("$.data.dataset.datasetCode").value("WORKER"))
				.andExpect(jsonPath("$.data.dataset.industryCode").value(nullValue()))
				.andExpect(jsonPath("$.data.dataset.periodType").value("QUARTER"))
				.andExpect(jsonPath("$.data.metrics", hasSize(1)))
				.andExpect(jsonPath("$.data.metrics[0].metricCode").value(WORKER_METRIC))
				.andExpect(jsonPath("$.data.metrics[0].unit").value("PERSON"))
				.andExpect(jsonPath("$.data.metrics[0].definitionStatus").value("VERIFIED"))
				.andExpect(jsonPath("$.data.periods", hasSize(4)))
				.andExpect(period(0, "20243", "UNAVAILABLE", "NOT_PUBLISHED"))
				.andExpect(jsonPath("$.data.periods[0].values").value(nullValue()))
				.andExpect(period(1, "20244", "AVAILABLE", null))
				.andExpect(value(1, WORKER_METRIC, 12, "AVAILABLE", null))
				.andExpect(period(2, "20251", "AVAILABLE", null))
				.andExpect(value(2, WORKER_METRIC, 0, "AVAILABLE", null))
				.andExpect(period(3, "20252", "UNAVAILABLE", "NOT_PUBLISHED"));
	}

	// 게시된 분기가 하나도 없으면 오류가 아니라 지표 정의 없이 모든 분기가 미게시로 나오는지 확인한다.
	@Test
	void datasetWithoutAnyPublicationReturnsAllQuartersAsNotPublished() throws Exception {
		trend("datasetCode=FACILITY&fromQuarter=20261&toQuarter=20262")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.metrics", hasSize(0)))
				.andExpect(period(0, "20261", "UNAVAILABLE", "NOT_PUBLISHED"))
				.andExpect(period(1, "20262", "UNAVAILABLE", "NOT_PUBLISHED"));
	}

	// 원천 정의가 확인되지 않은 지표는 숨기지 않고 값과 UNVERIFIED 상태·정의 상태를 함께 보여주는지 확인한다.
	@Test
	void sourceOnlyMetricIsShownWithUnverifiedStatus() throws Exception {
		long batch = workerBatch(List.of("20262"), List.of(workerRow("20262", 7)), "SOURCE_ONLY");
		publish(batch, "20262");

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(jsonPath("$.data.metrics[0].definitionStatus").value("SOURCE_ONLY"))
				.andExpect(period(0, "20262", "AVAILABLE", null))
				.andExpect(value(0, WORKER_METRIC, 7, "UNVERIFIED", "SOURCE_DEFINITION_UNREVIEWED"));
	}

	// 점포는 요청한 서울 업종 행만 읽고, 그 업종 행이 없는 분기는 0이 아니라 행 없음으로 나오는지 확인한다.
	@Test
	void storeReadsOnlyRequestedIndustry() throws Exception {
		long batch = storeBatch(List.of(storeRow("20262", CAFE, 5), storeRow("20262", SNACK, 9)));
		publish(batch, "20262");

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(jsonPath("$.data.dataset.industryCode").value(CAFE))
				.andExpect(jsonPath("$.data.metrics[0].unit").value("COUNT"))
				.andExpect(value(0, STORE_METRIC, 5, "AVAILABLE", null));
		trend("datasetCode=STORE&industryCode=CS100005&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "NO_DATA", "SOURCE_ROW_ABSENT"))
				.andExpect(jsonPath("$.data.periods[0].values").value(nullValue()));
	}

	// 한 지표만 비어 있으면 분기는 AVAILABLE이고 그 지표만 null·NO_DATA, 다른 지표 값은 유지되는지 확인한다.
	@Test
	void missingMetricInsideAvailableQuarterKeepsOtherMetric() throws Exception {
		var row = storeRow("20262", CAFE, 5);
		row.put("opbiz_stor_co", null);
		long batch = storeBatch(List.of(row));
		publish(batch, "20262");

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "AVAILABLE", null))
				.andExpect(value(0, STORE_METRIC, 5, "AVAILABLE", null))
				.andExpect(value(0, "opbiz_stor_co", null, "NO_DATA", "SOURCE_VALUE_MISSING"));
	}

	// 모든 값이 비어 보류된 행은 행 없음이 아니라 원천 결측(NO_DATA·SOURCE_VALUE_MISSING)으로 구분되는지 확인한다.
	@Test
	void rowWithOnlyMissingValuesIsSourceValueMissing() throws Exception {
		var empty = storeRow("20262", CAFE, null);
		empty.put("opbiz_stor_co", null);
		long batch = storeBatch(List.of(empty, storeRow("20262", SNACK, 9)));
		publish(batch, "20262");

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "NO_DATA", "SOURCE_VALUE_MISSING"));
	}

	// 경계 검토로 보류된 행은 행 없음이 아니라 UNAVAILABLE·BOUNDARY_REVIEW_PENDING으로 알리는지 확인한다.
	@Test
	void boundaryHeldRowIsUnavailableWithItsReason() throws Exception {
		long batch = storeBatch(List.of(storeRow("20262", CAFE, 5), storeRow("20262", SNACK, 9)));
		publish(batch, "20262");
		jdbc.update("""
				UPDATE regional_statistic_row SET row_status = 'HOLD', temporal_status = 'PENDING', reason_code = 'BOUNDARY_REVIEW_PENDING'
				WHERE batch_id = ? AND source_industry_code = ?
				""", batch, CAFE);

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "UNAVAILABLE", "BOUNDARY_REVIEW_PENDING"));
	}

	// 품질 오류로 빠진 행의 원천 사유(중복 충돌 등)는 공개 사유 QUALITY_CHECK_FAILED로 묶이는지 확인한다.
	@Test
	void invalidRowIsReportedAsQualityCheckFailed() throws Exception {
		long batch = storeBatch(List.of(storeRow("20262", CAFE, 5), storeRow("20262", SNACK, 9)));
		publish(batch, "20262");
		jdbc.update("UPDATE regional_statistic_row SET row_status = 'INVALID', reason_code = 'DUPLICATE_CONFLICT'"
				+ " WHERE batch_id = ? AND source_industry_code = ?", batch, CAFE);

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "UNAVAILABLE", "QUALITY_CHECK_FAILED"));
	}

	// 원천 대응표에 없는 지역(서울 밖 등)은 행 없음과 구분해 SOURCE_REGION_ABSENT로 알리는지 확인한다.
	@Test
	void regionOutsideSourceMappingIsSourceRegionAbsent() throws Exception {
		long batch = storeBatch(List.of(storeRow("20262", SNACK, 9)));
		publish(batch, "20262");
		jdbc.update("UPDATE regional_statistic_batch SET mapping_json = JSON_SET(mapping_json, '$.regions', JSON_ARRAY())"
				+ " WHERE batch_id = ?", batch);

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(period(0, "20262", "NO_DATA", "SOURCE_REGION_ABSENT"));
	}

	// 대응표를 만든 기준정보 릴리스에서 이 지역이 현행이 아니면 값이 있어도 BOUNDARY_MISMATCH로 보류하는지 확인한다.
	@Test
	void regionNotCurrentInMappingReleaseIsBoundaryMismatch() throws Exception {
		long batch = workerBatch(List.of("20262"), List.of(workerRow("20262", 12)), "VERIFIED");
		publish(batch, "20262");
		String next = importReference();
		regionPublisher.publish(next, 1, "test", "new selection", false);
		jdbc.update("""
				UPDATE official_region_node SET official_status = 'LEGACY', raw_abolished_on = '20260801', abolished_on = '2026-08-01'
				WHERE region_code = ? AND release_id = (SELECT release_id FROM region_release WHERE release_version = ?)
				""", REGION, referenceVersion);

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isOk())
				.andExpect(period(0, "20262", "UNAVAILABLE", "BOUNDARY_MISMATCH"));
	}

	// 선택 릴리스가 바뀌어도 대응 릴리스에서 현행이면 값을 그대로 보여주는지 확인한다.
	@Test
	void differentMappingReleaseStillShowsValueWhenRegionIsCurrent() throws Exception {
		long batch = workerBatch(List.of("20262"), List.of(workerRow("20262", 12)), "VERIFIED");
		publish(batch, "20262");
		regionPublisher.publish(importReference(), 1, "test", "new selection", false);

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(value(0, WORKER_METRIC, 12, "AVAILABLE", null));
	}

	// 읍·면·동이 아닌 그룹, 목록에 없는 코드는 통계 유무와 상관없이 404인지 확인한다.
	@Test
	void returns404ForNonLocalityOrUnknownRegion() throws Exception {
		for (String code : List.of("1111000000", "9999999999")) {
			mockMvc.perform(get(TRENDS_URL + "?datasetCode=WORKER&fromQuarter=20262&toQuarter=20262", code))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("REGION_NOT_FOUND"))
					.andExpect(jsonPath("$.error.details[0].field").value("regionCode"))
					.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"))
					.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		}
	}

	// 지역 선택 기준이 게시되지 않았으면 빈 추이가 아니라 503 REFERENCE_DATA_NOT_READY인지 확인한다.
	@Test
	void returns503WhenRegionSelectionIsNotPublished() throws Exception {
		jdbc.update("DELETE FROM region_publication_event");
		jdbc.update("DELETE FROM region_publication");

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("REFERENCE_DATA_NOT_READY"));
	}

	// 게시 포인터가 승인되지 않은 배치를 가리키면 다른 배치로 대신하지 않고 500인지 확인한다.
	@Test
	void returns500WhenPublishedBatchIsNotApproved() throws Exception {
		long batch = workerBatch(List.of("20262"), List.of(workerRow("20262", 12)), "VERIFIED");
		publish(batch, "20262");
		jdbc.update("UPDATE regional_statistic_batch SET status = 'READY', approved_at = NULL, approved_by = NULL WHERE batch_id = ?",
				batch);

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
	}

	// 점포 자료인데 게시 배치가 업종 없는 체계로 적재돼 있으면 업종 조건이 맞지 않으므로 500인지 확인한다.
	@Test
	void returns500WhenPublishedBatchHasWrongIndustryScheme() throws Exception {
		long batch = batch("STORE", "NONE", List.of("20262"), List.of(workerRow("20262", 3)),
				List.of(new Metric(WORKER_METRIC, "COUNT", "SOURCE_DEFINED", "VERIFIED")));
		publish(batch, "20262");

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isInternalServerError());
	}

	// 같은 지표의 단위가 분기마다 다르면 한 추이로 비교할 수 없으므로 500인지 확인한다.
	@Test
	void returns500WhenMetricDefinitionDiffersBetweenQuarters() throws Exception {
		publish(workerBatch(List.of("20261"), List.of(workerRow("20261", 1)), "VERIFIED"), "20261");
		long changed = batch("WORKER", "NONE", List.of("20262"), List.of(workerRow("20262", 2)),
				List.of(new Metric(WORKER_METRIC, "COUNT", "SOURCE_DEFINED", "VERIFIED")));
		publish(changed, "20262");

		trend("datasetCode=WORKER&fromQuarter=20261&toQuarter=20262")
				.andExpect(status().isInternalServerError());
	}

	// 조회 도중 같은 분기가 새 배치로 다시 게시돼도, 한 응답은 처음 읽은 시점의 포인터와 값만 쓰는지 확인한다.
	// 읽기 시점을 고정하지 않으면 포인터는 옛 배치, 값은 새 배치를 읽어 서로 맞지 않게 된다.
	@Test
	void republicationDuringRequestDoesNotMixBatches() throws Exception {
		long first = workerBatch(List.of("20262"), List.of(workerRow("20262", 1)), "VERIFIED");
		long second = workerBatch(List.of("20262"), List.of(workerRow("20262", 2)), "VERIFIED");
		publish(first, "20262");
		batches.approve(second, "test");
		try (var executor = Executors.newSingleThreadExecutor()) {
			doAnswer(invocation -> {
				executor.submit(() -> batches.publish(second, "20262", 1, first, "test", "republish")).get(10, TimeUnit.SECONDS);
				return invocation.callRealMethod();
			}).when(repository).findPublishedRows(any(), anyString(), anyString(), anyString(), anyString());

			trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
					.andExpect(status().isOk())
					.andExpect(value(0, WORKER_METRIC, 1, "AVAILABLE", null));
		}
		reset(repository);
		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(value(0, WORKER_METRIC, 2, "AVAILABLE", null));
	}

	// 전국 수록 업소는 소진공 업종 코드로 조회하고, 업종 표시명과 전 분기 대비 증감(절대·%)이 함께 나오는지 확인한다.
	@Test
	void listedStoreUsesSemasBusinessTypeAndShowsChanges() throws Exception {
		publish(nationalBatch("20262", Map.of(CAFE_SEMAS, 10, BAKERY_SEMAS, 4)), "20262");
		publish(nationalBatch("20263", Map.of(CAFE_SEMAS, 12, BAKERY_SEMAS, 4)), "20263");

		trend("datasetCode=LISTED_STORE&businessTypeCode=" + CAFE_SEMAS + "&fromQuarter=20262&toQuarter=20263")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.dataset.sourceSystem").value("SEMAS_STORE"))
				.andExpect(jsonPath("$.data.dataset.datasetCode").value("LISTED_STORE"))
				.andExpect(jsonPath("$.data.dataset.industryCode").value(nullValue()))
				.andExpect(jsonPath("$.data.dataset.businessTypeCode").value(CAFE_SEMAS))
				.andExpect(jsonPath("$.data.dataset.businessTypeName").value("카페"))
				.andExpect(jsonPath("$.data.metrics[0].metricCode").value(LISTED_METRIC))
				.andExpect(jsonPath("$.data.metrics[0].definitionStatus").value("VERIFIED"))
				.andExpect(value(0, LISTED_METRIC, 10, "AVAILABLE", null))
				// 20261은 게시되지 않아 비교 기준을 쓸 수 없다.
				.andExpect(change(0, LISTED_METRIC, "previousQuarter", null, null, "BASELINE_UNAVAILABLE"))
				.andExpect(value(1, LISTED_METRIC, 12, "AVAILABLE", null))
				.andExpect(change(1, LISTED_METRIC, "previousQuarter", 2, 20.00, "AVAILABLE"))
				.andExpect(change(1, LISTED_METRIC, "sameQuarterLastYear", null, null, "BASELINE_UNAVAILABLE"));
	}

	// 현재 승인 분류에 없는 소분류는 404, 등록됐지만 지원하지 않는 소분류는 422인지 확인한다(현황 API와 같은 기준).
	@Test
	void listedStoreRejectsUnknownOrUnsupportedBusinessType() throws Exception {
		trend("datasetCode=LISTED_STORE&businessTypeCode=Z99999&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("BUSINESS_TYPE_NOT_FOUND"))
				.andExpect(jsonPath("$.error.details[0].field").value("businessTypeCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_FOUND"));

		jdbc.update("UPDATE national_industry_code SET is_supported = FALSE, support_release_status = NULL WHERE industry_code = ?",
				BAKERY_SEMAS);
		trend("datasetCode=LISTED_STORE&businessTypeCode=" + BAKERY_SEMAS + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("BUSINESS_TYPE_NOT_SUPPORTED"))
				.andExpect(jsonPath("$.error.details[0].reason").value("NOT_SUPPORTED"));
	}

	// 기준값이 0이면 절대 증감만 주고 증감률은 무한대·100%로 만들지 않고 null(ZERO_BASELINE)인지 확인한다.
	@Test
	void zeroBaselineGivesAbsoluteChangeWithoutRate() throws Exception {
		publish(nationalBatch("20262", Map.of(CAFE_SEMAS, 0, BAKERY_SEMAS, 1)), "20262");
		publish(nationalBatch("20263", Map.of(CAFE_SEMAS, 5, BAKERY_SEMAS, 1)), "20263");

		trend("datasetCode=LISTED_STORE&businessTypeCode=" + CAFE_SEMAS + "&fromQuarter=20263&toQuarter=20263")
				.andExpect(change(0, LISTED_METRIC, "previousQuarter", 5, null, "ZERO_BASELINE"));
	}

	// 범위 첫 분기의 전년 동기는 응답 범위 밖(4분기 앞)이어도 비교 기준으로 읽고, 그 분기는 응답에 넣지 않는지 확인한다.
	@Test
	void sameQuarterLastYearReadsBaselineOutsideRequestedRange() throws Exception {
		long batch = workerBatch(List.of("20252", "20262"), List.of(workerRow("20252", 10), workerRow("20262", 15)), "VERIFIED");
		publish(batch, "20252");
		publish(batch, "20262");

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(jsonPath("$.data.periods", hasSize(1)))
				.andExpect(change(0, WORKER_METRIC, "sameQuarterLastYear", 5, 50.00, "AVAILABLE"))
				.andExpect(change(0, WORKER_METRIC, "previousQuarter", null, null, "BASELINE_UNAVAILABLE"));
	}

	// 직전 분기가 게시됐지만 이 지역·업종 행이 없으면 비교 기준 결측(BASELINE_MISSING)으로 미게시와 구분되는지 확인한다.
	@Test
	void absentBaselineRowIsBaselineMissing() throws Exception {
		long batch = storeBatch(List.of("20261", "20262"), List.of(storeRow("20261", SNACK, 3), storeRow("20262", CAFE, 5),
				storeRow("20262", SNACK, 4)));
		publish(batch, "20261");
		publish(batch, "20262");

		trend("datasetCode=STORE&industryCode=" + CAFE + "&fromQuarter=20262&toQuarter=20262")
				.andExpect(change(0, STORE_METRIC, "previousQuarter", null, null, "BASELINE_MISSING"));
	}

	// 정의가 확인되지 않은(UNVERIFIED) 지표는 값은 보여주되 증감은 계산하지 않는지 확인한다.
	@Test
	void unverifiedMetricHasNoComputedChange() throws Exception {
		long batch = workerBatch(List.of("20261", "20262"), List.of(workerRow("20261", 5), workerRow("20262", 7)), "SOURCE_ONLY");
		publish(batch, "20261");
		publish(batch, "20262");

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(value(0, WORKER_METRIC, 7, "UNVERIFIED", "SOURCE_DEFINITION_UNREVIEWED"))
				.andExpect(change(0, WORKER_METRIC, "previousQuarter", null, null, "CURRENT_UNAVAILABLE"));
	}

	// 비교 기준 분기의 지표 정의가 다르면(단위 변경) 응답 오류 없이 그 증감만 비교 불가(NOT_COMPARABLE)인지 확인한다.
	@Test
	void changedDefinitionInBaselineQuarterIsNotComparable() throws Exception {
		publish(batch("WORKER", "NONE", List.of("20261"), List.of(workerRow("20261", 5)),
				List.of(new Metric(WORKER_METRIC, "COUNT", "SOURCE_DEFINED", "VERIFIED"))), "20261");
		publish(workerBatch(List.of("20262"), List.of(workerRow("20262", 7)), "VERIFIED"), "20262");

		trend("datasetCode=WORKER&fromQuarter=20262&toQuarter=20262")
				.andExpect(status().isOk())
				.andExpect(change(0, WORKER_METRIC, "previousQuarter", null, null, "NOT_COMPARABLE"));
	}

	// 범위는 시작·종료 포함 최대 8분기까지 받는지 확인한다(20243~20262 = 8분기).
	@Test
	void acceptsExactlyEightQuarters() throws Exception {
		trend("datasetCode=WORKER&fromQuarter=20243&toQuarter=20262")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.periods", hasSize(8)))
				.andExpect(jsonPath("$.data.periods[7].quarter").value("20262"));
	}

	// 잘못된 요청은 DB를 읽기 전에 정해진 순서대로 하나의 오류만 알리는지 확인한다.
	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void rejectsInvalidRequests(String query, String code, String field, String reason) throws Exception {
		mockMvc.perform(get(TRENDS_URL + "?" + query, REGION))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.details", hasSize(1)))
				.andExpect(jsonPath("$.error.details[0].field").value(field))
				.andExpect(jsonPath("$.error.details[0].reason").value(reason))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
	}

	// 입력 오류 사례: 요청 쿼리, 오류 코드, 필드, 사유.
	static Stream<Arguments> invalidRequests() {
		String range = "&fromQuarter=20261&toQuarter=20262";
		return Stream.of(
				Arguments.of("datasetCode=WORKER" + range + "&month=202609", "INVALID_REQUEST", "query", "INVALID_VALUE"),
				Arguments.of("datasetCode=WORKER&datasetCode=WORKER" + range, "INVALID_REQUEST", "datasetCode", "INVALID_VALUE"),
				Arguments.of("fromQuarter=20261&toQuarter=20262", "INVALID_REQUEST", "datasetCode", "REQUIRED"),
				Arguments.of("datasetCode=" + range, "INVALID_REQUEST", "datasetCode", "REQUIRED"),
				Arguments.of("datasetCode=worker" + range, "INVALID_REQUEST", "datasetCode", "INVALID_VALUE"),
				Arguments.of("datasetCode=POPULATION" + range, "INVALID_REQUEST", "datasetCode", "INVALID_VALUE"),
				Arguments.of("datasetCode=STORE" + range, "INVALID_REQUEST", "industryCode", "REQUIRED"),
				Arguments.of("datasetCode=STORE&industryCode=I21201" + range, "INVALID_REQUEST", "industryCode", "INVALID_FORMAT"),
				Arguments.of("datasetCode=STORE&industryCode=cs100010" + range, "INVALID_REQUEST", "industryCode", "INVALID_FORMAT"),
				Arguments.of("datasetCode=WORKER&industryCode=CS100010" + range, "INVALID_REQUEST", "industryCode", "INVALID_VALUE"),
				Arguments.of("datasetCode=WORKER&toQuarter=20262", "INVALID_REQUEST", "fromQuarter", "REQUIRED"),
				Arguments.of("datasetCode=WORKER&fromQuarter=20261", "INVALID_REQUEST", "toQuarter", "REQUIRED"),
				Arguments.of("datasetCode=WORKER&fromQuarter=20265&toQuarter=20262", "INVALID_QUARTER", "fromQuarter", "INVALID_FORMAT"),
				Arguments.of("datasetCode=WORKER&fromQuarter=202602&toQuarter=20262", "INVALID_QUARTER", "fromQuarter", "INVALID_FORMAT"),
				Arguments.of("datasetCode=WORKER&fromQuarter=20261&toQuarter=2026Q2", "INVALID_QUARTER", "toQuarter", "INVALID_FORMAT"),
				Arguments.of("datasetCode=WORKER&fromQuarter=20262&toQuarter=20261", "INVALID_QUARTER", "toQuarter", "INVALID_VALUE"),
				Arguments.of("datasetCode=WORKER&fromQuarter=20242&toQuarter=20262", "INVALID_QUARTER", "toQuarter", "INVALID_VALUE"),
				Arguments.of("datasetCode=LISTED_STORE" + range, "INVALID_REQUEST", "businessTypeCode", "REQUIRED"),
				Arguments.of("datasetCode=LISTED_STORE&businessTypeCode=" + range, "INVALID_REQUEST", "businessTypeCode", "REQUIRED"),
				Arguments.of("datasetCode=LISTED_STORE&businessTypeCode=i21201" + range, "INVALID_REQUEST", "businessTypeCode", "INVALID_FORMAT"),
				Arguments.of("datasetCode=LISTED_STORE&businessTypeCode=CS100010" + range, "INVALID_REQUEST", "businessTypeCode", "INVALID_FORMAT"),
				Arguments.of("datasetCode=LISTED_STORE&businessTypeCode=I21201&industryCode=CS100010" + range, "INVALID_REQUEST",
						"industryCode", "INVALID_VALUE"),
				Arguments.of("datasetCode=STORE&industryCode=CS100010&businessTypeCode=I21201" + range, "INVALID_REQUEST",
						"businessTypeCode", "INVALID_VALUE"),
				Arguments.of("datasetCode=WORKER&businessTypeCode=I21201" + range, "INVALID_REQUEST", "businessTypeCode", "INVALID_VALUE"));
	}

	// 지역 코드 형식 오류는 다른 입력보다 먼저 INVALID_REGION_CODE로 알리는지 확인한다.
	@Test
	void rejectsInvalidRegionCodeFirst() throws Exception {
		mockMvc.perform(get(TRENDS_URL + "?datasetCode=worker", "11110515"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REGION_CODE"))
				.andExpect(jsonPath("$.error.details[0].field").value("regionCode"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"));
	}

	// GET 이외 요청은 405이고 Allow 헤더가 유지되는지 확인한다.
	@Test
	void rejectsOtherMethods() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(TRENDS_URL, REGION))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(HttpHeaders.ALLOW, "GET"));
	}

	// OpenAPI 문서에 추이 경로가 실리는지 확인한다.
	@Test
	void openApiDocumentsTrendPath() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/api/regions/{regionCode}/trends")));
	}

	// 기본 지역(청운효자동)으로 추이를 요청한다.
	private ResultActions trend(String query) throws Exception {
		return mockMvc.perform(get(TRENDS_URL + "?" + query, REGION));
	}

	// periods[index]의 분기·상태·사유를 확인한다.
	private static ResultMatcher period(int index, String quarter, String status, String reason) {
		String path = "$.data.periods[" + index + "]";
		return all(
				jsonPath(path + ".quarter").value(quarter),
				jsonPath(path + ".status").value(status),
				jsonPath(path + ".reasonCode").value(reason == null ? nullValue() : org.hamcrest.Matchers.is(reason)));
	}

	// periods[index]의 지표 값·상태·사유를 확인한다. value가 null이면 키는 있고 값만 null이어야 한다.
	private static ResultMatcher value(int index, String metric, Integer number, String status, String reason) {
		String path = "$.data.periods[" + index + "].values." + metric;
		return all(
				jsonPath(path + ".value").value(number == null ? nullValue() : org.hamcrest.Matchers.is(number)),
				jsonPath(path + ".status").value(status),
				jsonPath(path + ".reasonCode").value(reason == null ? nullValue() : org.hamcrest.Matchers.is(reason)));
	}

	// periods[index]의 지표 증감(absoluteChange·changeRatePercent·status)을 확인한다. null이면 키는 있고 값만 null이어야 한다.
	private static ResultMatcher change(int index, String metric, String kind, Integer absolute, Double rate, String status) {
		String path = "$.data.periods[" + index + "].values." + metric + ".changes." + kind;
		return all(
				jsonPath(path + ".absoluteChange").value(absolute == null ? nullValue() : org.hamcrest.Matchers.is(absolute)),
				jsonPath(path + ".changeRatePercent").value(rate == null ? nullValue() : org.hamcrest.Matchers.is(rate)),
				jsonPath(path + ".status").value(status));
	}

	// 여러 검사를 하나로 묶는다. 앞의 검사가 실패하면 거기서 멈춘다.
	private static ResultMatcher all(ResultMatcher... matchers) {
		return result -> {
			for (ResultMatcher matcher : matchers) {
				matcher.match(result);
			}
		};
	}

	// JSON 값이 null인지 확인하는 매처다.
	private static org.hamcrest.Matcher<Object> nullValue() {
		return org.hamcrest.Matchers.nullValue();
	}

	// 테스트 전용 전국 기준정보를 적재·승인하고 그 버전 이름을 돌려준다.
	private String importReference() throws Exception {
		var fixture = StatisticsTestFixture.prepare(temp.resolve("reference-" + sequence++), "202609",
				List.of(StatisticsTestFixture.store("S1")));
		var loaded = referenceLoader.load(fixture.reference(), NationalRawValidator.sha256(fixture.reference()));
		referenceImporter.importSnapshot(loaded);
		regionPublisher.approve(loaded.snapshot().version(), "test");
		return loaded.snapshot().version();
	}

	// 기준정보 릴리스에 기록된 스냅샷 해시를 읽는다.
	private String sha(String version) {
		String report = jdbc.queryForObject("SELECT validation_report FROM region_release WHERE release_version = ?", String.class,
				version);
		return mapper.readTree(report).path("snapshotSha256").asString();
	}

	// 직장인구(업종 없음) 배치를 적재한다.
	private long workerBatch(List<String> periods, List<Map<String, Object>> rows, String definition) throws Exception {
		return batch("WORKER", "NONE", periods, rows,
				List.of(new Metric(WORKER_METRIC, "PERSON", "SOURCE_DEFINED", definition)));
	}

	// 점포(서울 업종) 배치를 20262 분기로 적재한다.
	private long storeBatch(List<Map<String, Object>> rows) throws Exception {
		return storeBatch(List.of("20262"), rows);
	}

	// 점포(서울 업종) 배치를 지정한 분기들로 적재한다.
	private long storeBatch(List<String> periods, List<Map<String, Object>> rows) throws Exception {
		return batch("STORE", "SEOUL_CS100", periods, rows,
				List.of(new Metric(STORE_METRIC, "COUNT", "SOURCE_DEFINED", "VERIFIED"),
						new Metric("opbiz_stor_co", "COUNT", "SOURCE_DEFINED", "VERIFIED")));
	}

	// 배치를 승인하고 그 분기를 게시한다. 이미 게시된 분기면 현재 revision·배치를 넘겨 교체한다.
	private void publish(long batch, String quarter) {
		batches.approve(batch, "test");
		var current = jdbc.queryForList("SELECT batch_id, revision FROM regional_statistic_publication WHERE period_key = ?"
				+ " AND dataset_code = (SELECT dataset_code FROM regional_statistic_batch WHERE batch_id = ?)", quarter, batch);
		long revision = current.isEmpty() ? 0 : ((Number) current.getFirst().get("revision")).longValue();
		Long previous = current.isEmpty() ? null : ((Number) current.getFirst().get("batch_id")).longValue();
		batches.publish(batch, quarter, revision, previous, "test", "trend test");
	}

	// 직장인구 원천 행 하나를 만든다.
	private Map<String, Object> workerRow(String quarter, Object value) {
		var row = new LinkedHashMap<String, Object>();
		row.put("stdr_yyqu_cd", quarter);
		row.put("adstrd_cd", SOURCE_REGION);
		row.put("adstrd_cd_nm", SOURCE_NAME);
		row.put(WORKER_METRIC, value);
		return row;
	}

	// 점포 원천 행 하나를 만든다. 개업 점포 수는 1로 둔다.
	private Map<String, Object> storeRow(String quarter, String industry, Object stores) {
		var row = new LinkedHashMap<String, Object>();
		row.put("stdr_yyqu_cd", quarter);
		row.put("adstrd_cd", SOURCE_REGION);
		row.put("adstrd_cd_nm", SOURCE_NAME);
		row.put("svc_induty_cd", industry);
		row.put("svc_induty_cd_nm", "테스트업종");
		row.put(STORE_METRIC, stores);
		row.put("opbiz_stor_co", 1);
		return row;
	}

	// 전국 수록 업소 분기 집계 배치(소진공 업종별 업소 수, 한 분기)를 실제 전국 어댑터로 적재한다.
	// 이 어댑터는 보류 행을 허용하지 않으므로 대응이 검증된 지역과 지원 업종만 넣는다.
	private long nationalBatch(String quarter, Map<String, Integer> countsByBusinessType) throws Exception {
		Path dir = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
		Path raw = dir.resolve("raw.jsonl");
		Path maps = dir.resolve("mapping.json");
		StringBuilder lines = new StringBuilder();
		for (var entry : new java.util.TreeMap<>(countsByBusinessType).entrySet()) {
			var row = new LinkedHashMap<String, Object>();
			row.put("stdr_yyqu_cd", quarter);
			row.put("adstrd_cd", SOURCE_REGION);
			row.put("adstrd_cd_nm", SOURCE_NAME);
			row.put("svc_induty_cd", entry.getKey());
			row.put(LISTED_METRIC, entry.getValue());
			lines.append(mapper.writeValueAsString(row)).append('\n');
		}
		Files.writeString(raw, lines.toString());
		Files.write(maps, mapper.writeValueAsBytes(new Mappings(referenceVersion, sha(referenceVersion),
				List.of(new RegionMapping(SOURCE_REGION, SOURCE_NAME, REGION, "existing verified test mapping")))));
		var aliases = new LinkedHashMap<String, String>();
		for (String key : List.of("stdr_yyqu_cd", "adstrd_cd", "adstrd_cd_nm", "svc_induty_cd", LISTED_METRIC)) {
			aliases.put(key, key);
		}
		var manifest = new DetailImportManifest("regional-detail-manifest-v1", "SEMAS_STORE", "STORE_SNAPSHOT",
				"semas-store-quarter-v1", "QUARTER", List.of(quarter), "SEMAS_ADSTRD8", "SEMAS_SMALL", referenceVersion,
				sha(referenceVersion), "mapping.json", NationalRawValidator.sha256(maps), "https://example.test/semas",
				"2026-11-11T00:00:00Z", "raw.jsonl", NationalRawValidator.sha256(raw), "UTF-8", countsByBusinessType.size(), aliases,
				List.of(new Metric(LISTED_METRIC, "COUNT", "DISTINCT_STORE_ID", "VERIFIED")), Map.of("fixture", true));
		Path file = dir.resolve("manifest.json");
		Files.write(file, mapper.writeValueAsBytes(manifest));
		return importer.importData(loader.load(file)).batchId();
	}

	// 적재 명세·원본·대응표 파일을 만들어 실제 적재기로 배치를 만든다. 적재 결과 배치 번호를 돌려준다.
	private long batch(String dataset, String industryScheme, List<String> periods, List<Map<String, Object>> rows,
			List<Metric> metrics) throws Exception {
		Path dir = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
		Path raw = dir.resolve("raw.json");
		Path maps = dir.resolve("mapping.json");
		Files.write(raw, mapper.writeValueAsBytes(Map.of("DATA", rows)));
		Files.write(maps, mapper.writeValueAsBytes(new Mappings(referenceVersion, sha(referenceVersion),
				List.of(new RegionMapping(SOURCE_REGION, SOURCE_NAME, REGION, "existing verified test mapping")))));
		var aliases = new LinkedHashMap<String, String>();
		for (var row : rows) {
			row.keySet().forEach(key -> aliases.put(key, key));
		}
		var manifest = new DetailImportManifest("regional-detail-manifest-v1", "SEOUL_COMMERCIAL", dataset, "seoul-dong-v1",
				"QUARTER", periods, "SEOUL_ADSTRD8", industryScheme, referenceVersion, sha(referenceVersion), "mapping.json",
				NationalRawValidator.sha256(maps), "https://example.test/source", "2026-11-11T00:00:00Z", "raw.json",
				NationalRawValidator.sha256(raw), "UTF-8", rows.size(), aliases, metrics, Map.of("fixture", true));
		Path file = dir.resolve("manifest.json");
		Files.write(file, mapper.writeValueAsBytes(manifest));
		return importer.importData(loader.load(file)).batchId();
	}

}
