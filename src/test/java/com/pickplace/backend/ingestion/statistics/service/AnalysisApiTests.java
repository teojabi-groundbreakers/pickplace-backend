package com.pickplace.backend.ingestion.statistics.service;

import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.REGION;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.load;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.prepare;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.ai.AiAnalysisV2Controller;
import com.pickplace.backend.ai.AiPredictClient;
import com.pickplace.backend.ai.AiPredictionRequest;
import com.pickplace.backend.analysis.PerIndicatorAnalysisService;
import com.pickplace.backend.analysis.SeoulAiInputPreparationService;
import com.pickplace.backend.summary.SummaryService;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;

import tools.jackson.databind.json.JsonMapper;

// 분석 API가 현황 API와 같은 통계를 같은 기준으로 돌려주는지, 작은 실제 원본을 적재·게시한 뒤 확인한다.
// 아직 승인된 매출·코드 대응이 없어 AI는 호출되지 않고(SKIPPED) 통계만 채워져야 한다.
// StatisticsTestFixture(같은 패키지 전용)를 써야 해서 이 패키지에 둔다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class AnalysisApiTests {

	private static final String ANALYSES_URL = "/api/ai/analyses";
	private static final String CAFE = "I21201";
	private static final String BAKERY = "I21001";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
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
	@Autowired
	private SummaryService summaries;
	@Autowired
	private SeoulAiInputPreparationService aiInputs;

	@TempDir
	private Path temp;
	private int sequence;

	// 임시 DB의 기준정보·통계·게시 기록을 FK 순서대로 비운다. 현황 API 테스트와 같은 범위다.
	@BeforeEach
	@AfterEach
	void cleanup() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		for (String table : List.of("ai_input_area_review", "ai_input_metric_review", "ai_input_scope_review",
				"regional_statistic_publication_event", "regional_statistic_publication",
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

	// 같은 지역·업종에서 분석 응답의 통계가 현황 API의 지표·기준월·게시 시각과 같고, AI는 호출되지 않는지 확인한다.
	@Test
	void statisticsMatchSummaryApiWhileAiIsSkipped() throws Exception {
		publishDefault(bundle("202609"));

		String summary = mockMvc.perform(get("/api/regions/{regionCode}/summary", REGION).param("businessTypeCode", CAFE))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String analysis = analyze(CAFE)
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.data.request.regionCode").value(REGION))
				.andExpect(jsonPath("$.data.request.industryCode").value(CAFE))
				.andExpect(jsonPath("$.data.statistics.version").value("national-summary-202609-r1"))
				.andExpect(metric("registeredPopulation", 100, "AVAILABLE", null))
				.andExpect(jsonPath("$.data.statistics.metrics.registeredPopulation.unit").value("PERSON"))
				.andExpect(jsonPath("$.data.statistics.metrics.listedStoreCount.unit").value("STORE"))
				.andExpect(jsonPath("$.data.ai.state").value("SKIPPED"))
				.andExpect(jsonPath("$.data.ai.reason").value("mapping_unverified"))
				.andExpect(jsonPath("$.data.ai.transportStatus").value(nullValue()))
				.andExpect(jsonPath("$.data.ai.result").value(nullValue()))
				.andReturn().getResponse().getContentAsString();

		var summaryJson = mapper.readTree(summary);
		var analysisJson = mapper.readTree(analysis);
		assertThat(analysisJson.at("/data/statistics/metrics")).isEqualTo(summaryJson.at("/data/metrics"));
		assertThat(analysisJson.at("/data/statistics/referenceMonth")).isEqualTo(summaryJson.at("/meta/referenceMonth"));
		assertThat(analysisJson.at("/data/statistics/publishedAt")).isEqualTo(summaryJson.at("/meta/publishedAt"));
	}

	// 해당 업종 업소가 실제로 0개면 null이 아니라 0으로 그대로 나오는지 확인한다.
	@Test
	void realZeroStoreCountIsKept() throws Exception {
		publishDefault(bundle("202609"));

		analyze(BAKERY)
				.andExpect(status().isOk())
				.andExpect(metric("listedStoreCount", 0, "AVAILABLE", null));
	}

	// 두 지표가 결측·보류여도 AI 상태와 관계없이 200이고, 지표별 사유와 출처를 그대로 보여주는지 확인한다.
	@Test
	void missingAndHeldMetricsKeepReasonsAndSources() throws Exception {
		Bundle bundle = bundle("202609");
		publishDefault(bundle);
		jdbc.update("""
				UPDATE resident_population_month SET row_status = 'SUPPRESSED', reason_code = 'SOURCE_SUPPRESSED',
				    total_population = NULL, male_population = NULL, female_population = NULL, age_sex_counts = NULL
				WHERE batch_id = ?
				""", bundle.population());
		jdbc.update("DELETE FROM store_region_coverage WHERE batch_id = ?", bundle.stores());

		analyze(CAFE)
				.andExpect(status().isOk())
				.andExpect(metric("registeredPopulation", null, "UNAVAILABLE", "SOURCE_SUPPRESSED"))
				.andExpect(metric("listedStoreCount", null, "NO_DATA", "SOURCE_REGION_ABSENT"))
				.andExpect(jsonPath("$.data.statistics.metrics.listedStoreCount.source.referenceOn").value("2026-09-30"))
				.andExpect(jsonPath("$.data.ai.state").value("SKIPPED"));
	}

	// 기본 현황 묶음이 게시되지 않았으면 빈 통계로 대신하지 않고 503 SUMMARY_DATA_NOT_READY인지 확인한다.
	@Test
	void unpublishedDefaultIsServiceUnavailable() throws Exception {
		selectRegions(bundle("202609"));

		analyze(CAFE)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SUMMARY_DATA_NOT_READY"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
	}

	// 등록됐지만 지원하지 않는 업종은 현황 API와 같은 422로 거절되는지 확인한다.
	@Test
	void unsupportedBusinessTypeIsRejectedLikeSummaryApi() throws Exception {
		publishDefault(bundle("202609"));
		jdbc.update("UPDATE national_industry_code SET is_supported = FALSE, support_release_status = NULL WHERE industry_code = ?",
				BAKERY);

		analyze(BAKERY)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("BUSINESS_TYPE_NOT_SUPPORTED"));
	}

	@Test
	void expandsPublishedSmallIndustriesAcrossSelectionSummaryAndAnalysis() throws Exception {
		Bundle bundle = bundle("202609");
		assertThatThrownBy(() -> regionPublisher.expandPublishedIndustries(bundle.regionVersion(), 2))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not currently published");
		publishDefault(bundle);
		jdbc.update("UPDATE national_industry_code SET is_supported = FALSE, support_release_status = NULL WHERE industry_code = ?",
				BAKERY);

		assertThatThrownBy(() -> regionPublisher.expandPublishedIndustries(bundle.regionVersion(), 1))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
		analyze(BAKERY).andExpect(status().isUnprocessableContent());

		assertThat(regionPublisher.expandPublishedIndustries(bundle.regionVersion(), 2)).isEqualTo(1);
		assertThat(regionPublisher.expandPublishedIndustries(bundle.regionVersion(), 2)).isZero();
		mockMvc.perform(get("/api/business-types"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[1].businessTypeCode").value(BAKERY));
		mockMvc.perform(get("/api/regions/{regionCode}/summary", REGION).param("businessTypeCode", BAKERY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.value").value(0))
				.andExpect(jsonPath("$.data.metrics.listedStoreCount.status").value("AVAILABLE"));
		analyze(BAKERY).andExpect(status().isOk())
				.andExpect(metric("registeredPopulation", 100, "AVAILABLE", null))
				.andExpect(metric("listedStoreCount", 0, "AVAILABLE", null))
				.andExpect(jsonPath("$.data.ai.state").value("SKIPPED"))
				.andExpect(jsonPath("$.data.ai.reason").value("mapping_unverified"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_industry_code WHERE industry_level <> 'SMALL' AND is_supported",
				Integer.class)).isZero();
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "PICKPLACE_TEST_FASTAPI_URL", matches = ".+")
	void approvedPublishedRiskSampleReachesFastApiAndSurvivesAnOutage() throws Exception {
		publishDefault(bundle("202609"));
		long releaseId = jdbc.queryForObject("SELECT release_id FROM region_publication WHERE publication_key='NATIONWIDE_SELECTION'", Long.class);
		long classificationId = jdbc.queryForObject("SELECT classification_id FROM national_summary_release", Long.class);
		jdbc.update("""
				INSERT INTO ai_input_scope_review (
				    review_version,region_release_id,region_code,classification_id,business_type_code,source_region_code,
				    source_industry_code,source_industry_name,industry_match,valid_from,valid_through,status,evidence,approved_at,approved_by)
				VALUES ('t32-risk-sample',?,'1111051500',?,'I21201','11110515','CS100010','커피 전문점','PARTIAL',
				        '2025-04-01','2026-06-30','APPROVED','test-only scope','2026-11-19 00:00:00','test')
				""", releaseId, classificationId);
		long scopeId = jdbc.queryForObject("SELECT review_id FROM ai_input_scope_review WHERE review_version='t32-risk-sample'", Long.class);

		String key = System.getenv("PICKPLACE_TEST_FASTAPI_KEY");
		var realClient = spy(new AiPredictClient(mapper, true, System.getenv("PICKPLACE_TEST_FASTAPI_URL"), key, 1000, 5000));
		var realHttp = v2With(realClient);

		requestV2(realHttp, CAFE)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.statistics.metrics.registeredPopulation.value").value(100))
				.andExpect(jsonPath("$.data.ai.state").value("SKIPPED"))
				.andExpect(jsonPath("$.data.ai.reason").value("insufficient_verified_data"));
		org.mockito.Mockito.verifyNoInteractions(realClient);

		// 원본 대조값만 이 격리 DB에 복제한다. 승인 상태는 테스트 전용이다.
		publishSeoulSample(releaseId, scopeId, "SALES", "20252", """
				{"thsmon_selng_amt":{"status":"UNVERIFIED","reasonCode":"SOURCE_DEFINITION_UNREVIEWED","value":4603714789}}
				""", "SALES");
		publishSeoulSample(releaseId, scopeId, "SALES", "20262", """
				{"thsmon_selng_amt":{"status":"UNVERIFIED","reasonCode":"SOURCE_DEFINITION_UNREVIEWED","value":4403365282}}
				""", "SALES");
		publishSeoulSample(releaseId, scopeId, "STORE", "20262", """
				{"similr_induty_stor_co":{"status":"AVAILABLE","value":124},"clsbiz_stor_co":{"status":"AVAILABLE","value":7}}
				""", "STORE_COUNT", "CLOSING_COUNT");

		String available = requestV2(realHttp, CAFE)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ai.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.ai.metrics.risk.basePeriod").value("2026Q2"))
				.andExpect(jsonPath("$.data.ai.metrics.risk.value").value(27.52))
				.andExpect(jsonPath("$.data.ai.metrics.growth.reason").value("insufficient_verified_data"))
				.andExpect(jsonPath("$.data.ai.metrics.fit.reason").value("insufficient_verified_data"))
				.andExpect(jsonPath("$.data.ai.analysisScope.sourceIndustryName").value("커피 전문점"))
				.andExpect(jsonPath("$.data.ai.analysisScope.referenceOnly").value(true))
				.andReturn().getResponse().getContentAsString();
		var captured = ArgumentCaptor.forClass(AiPredictionRequest.class);
		verify(realClient).predict(captured.capture());
		var request = captured.getValue();
		assertThat(request.regionCode()).isEqualTo("11110515");
		assertThat(request.regionCodeSystem()).isEqualTo("seoul-adstrd");
		assertThat(request.industryCode()).isEqualTo("CS100010");
		assertThat(request.basePeriod()).isEqualTo("2026Q2");
		assertThat(request.history()).extracting(AiPredictionRequest.Observation::period).containsExactly("2025Q2", "2026Q2");
		assertThat(request.history().getFirst().salesAmount()).isEqualByComparingTo(BigDecimal.valueOf(4_603_714_789L));
		assertThat(request.history().getLast().salesAmount()).isEqualByComparingTo(BigDecimal.valueOf(4_403_365_282L));
		assertThat(request.history().getLast().storeCount()).isEqualTo(124L);
		assertThat(request.history().getLast().closingCount()).isEqualTo(7L);

		try (var closedEndpoint = new Socket()) {
			closedEndpoint.bind(new InetSocketAddress("127.0.0.1", 0));
			var disconnected = new AiPredictClient(mapper, true,
					"http://127.0.0.1:" + closedEndpoint.getLocalPort(), key, 500, 1500);
			String failed = requestV2(v2With(disconnected), CAFE)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.ai.state").value("FAILED"))
					.andExpect(jsonPath("$.data.ai.metrics.risk.reason").value("ai_transport_failure"))
					.andExpect(jsonPath("$.data.ai.metrics.risk.value").value(nullValue()))
					.andReturn().getResponse().getContentAsString();
			assertThat(mapper.readTree(failed).at("/data/statistics"))
					.isEqualTo(mapper.readTree(available).at("/data/statistics"));
		}
	}

	private MockMvc v2With(AiPredictClient client) {
		var clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);
		var service = new PerIndicatorAnalysisService(aiInputs, client);
		return MockMvcBuilders.standaloneSetup(new AiAnalysisV2Controller(summaries, service, clock)).build();
	}

	private ResultActions requestV2(MockMvc http, String industry) throws Exception {
		return http.perform(post(ANALYSES_URL + "/v2").contentType(MediaType.APPLICATION_JSON)
				.content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"" + industry + "\"}"));
	}

	private void publishSeoulSample(long releaseId, long scopeId, String dataset, String period, String metrics, String... kinds) {
		String fingerprint = String.format(java.util.Locale.ROOT, "%064d", ++sequence);
		jdbc.update("""
				INSERT INTO regional_statistic_batch (
				    source_system,dataset_code,period_type,period_keys,region_scheme,industry_scheme,mapping_release_id,
				    fingerprint,attempt_no,status,definition_json,mapping_json,collected_at,started_at,finished_at,
				    source_row_count,accepted_row_count,held_row_count,invalid_row_count,duplicate_row_count,
				    validation_report,approved_at,approved_by)
				VALUES ('SEOUL_COMMERCIAL',?,'QUARTER',JSON_ARRAY(?),'SEOUL_ADMIN8','SEOUL_CS100',?,
				        ?,1,'APPROVED',JSON_OBJECT(),JSON_OBJECT(),'2026-11-12','2026-11-12','2026-11-12',
				        1,1,0,0,0,JSON_OBJECT(),'2026-11-12','test')
				""", dataset, period, releaseId, fingerprint);
		long batch = jdbc.queryForObject("SELECT batch_id FROM regional_statistic_batch WHERE fingerprint=?", Long.class, fingerprint);
		jdbc.update("""
				INSERT INTO regional_statistic_row (
				    batch_id,period_key,source_region_code,source_industry_code,mapping_release_id,region_code,
				    mapping_status,temporal_status,row_status,metrics,raw_payload,payload_sha256,source_row_number)
				VALUES (?,?,'11110515','CS100010',?,'1111051500','VERIFIED','VALID','VALID',?,JSON_OBJECT(),?,1)
				""", batch, period, releaseId, metrics, fingerprint);
		jdbc.update("""
				INSERT INTO regional_statistic_publication
				    (source_system,dataset_code,period_type,period_key,batch_id,revision,published_at)
				VALUES ('SEOUL_COMMERCIAL',?,'QUARTER',?,?,1,'2026-11-12')
				""", dataset, period, batch);
		for (String kind : kinds) {
			jdbc.update("""
					INSERT INTO ai_input_metric_review (
					    scope_review_id,batch_id,metric_kind,valid_from,valid_through,unit,boundary_verified,status,evidence,approved_at,approved_by)
					VALUES (?,?,?,'2025-04-01','2026-06-30',?,TRUE,'APPROVED','test-only metric','2026-11-19','test')
					""", scopeId, batch, kind, "SALES".equals(kind) ? "KRW" : "COUNT");
		}
	}

	// 분석을 요청한다.
	private ResultActions analyze(String businessTypeCode) throws Exception {
		return mockMvc.perform(post(ANALYSES_URL).contentType(MediaType.APPLICATION_JSON)
				.content("{\"regionCode\":\"" + REGION + "\",\"industryCode\":\"" + businessTypeCode + "\"}"));
	}

	// 통계 지표 하나의 값·상태·사유를 확인한다. value와 reasonCode는 null이어도 키가 있어야 한다.
	private static ResultMatcher metric(String name, Integer value, String status, String reason) {
		String path = "$.data.statistics.metrics." + name;
		return result -> {
			jsonPath(path + ".value").value(value == null ? nullValue() : org.hamcrest.Matchers.is(value)).match(result);
			jsonPath(path + ".status").value(status).match(result);
			jsonPath(path + ".reasonCode").value(reason == null ? nullValue() : org.hamcrest.Matchers.is(reason)).match(result);
		};
	}

	// 한 달 치 기준정보를 적재·승인하고 인구·업소 배치를 적재해 승인된 현황 묶음을 만든다(게시는 하지 않음).
	private Bundle bundle(String month) throws Exception {
		var fixture = prepare(temp.resolve("fixture-" + sequence++), month, List.of(store("S1")));
		references.importSnapshot(loader.load(fixture.reference(), NationalRawValidator.sha256(fixture.reference())));
		regionPublisher.approve(fixture.snapshot().version(), "test");
		long population = importer.importData(load(fixture, "POPULATION_MONTH")).batchId();
		long stores = importer.importData(load(fixture, "STORE_SNAPSHOT")).batchId();
		String version = "analysis-api-" + sequence;
		publisher.prepare(version, population, stores);
		publisher.approve(version, "test");
		return new Bundle(version, fixture.snapshot().version(), population, stores);
	}

	// 지역 선택 기준(NATIONWIDE_SELECTION)만 게시한다.
	private void selectRegions(Bundle bundle) {
		regionPublisher.publish(bundle.regionVersion(), 0, "test", "selection", false);
	}

	// 지역 선택 기준·월별 포인터·DEFAULT 포인터를 차례로 게시한다.
	private void publishDefault(Bundle bundle) {
		selectRegions(bundle);
		publisher.publishMonth(bundle.version(), new Expected(0, null), null, "test", "month", false);
		publisher.setDefault(bundle.version(), new Expected(0, null), "test", "default");
	}

	private record Bundle(String version, String regionVersion, long population, long stores) {
	}

}
