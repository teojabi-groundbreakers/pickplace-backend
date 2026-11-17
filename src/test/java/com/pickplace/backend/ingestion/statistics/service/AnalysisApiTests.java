package com.pickplace.backend.ingestion.statistics.service;

import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.REGION;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.load;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.prepare;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import com.pickplace.backend.MySqlTestConfiguration;
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

	@TempDir
	private Path temp;
	private int sequence;

	// 임시 DB의 기준정보·통계·게시 기록을 FK 순서대로 비운다. 현황 API 테스트와 같은 범위다.
	@BeforeEach
	@AfterEach
	void cleanup() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
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
