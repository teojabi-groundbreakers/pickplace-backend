package com.pickplace.backend.ingestion.statistics.service;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 배치 성공과 게시 가능을 구분한다. 지역에 한정된 보류는 유지하되 GLOBAL 문제는 새 게시를 막는다. */
@Service
public class SummaryReadinessValidator {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public SummaryReadinessValidator(JdbcTemplate jdbc, JsonMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Bundle check(long populationId, long storeId) {
        var population = batch(populationId, "POPULATION_MONTH");
        var stores = batch(storeId, "STORE_SNAPSHOT");
        require(population.get("source_period_key").equals(stores.get("source_period_key"))
                && population.get("mapping_release_id").equals(stores.get("mapping_release_id")), "서로 다른 월·지역 릴리스의 배치를 묶을 수 없습니다.");
        long mapping = number(stores, "mapping_release_id"), classification = number(stores, "classification_id");
        var region = jdbc.queryForMap("SELECT status, validation_report FROM region_release WHERE release_id=?", mapping);
        var link = mapper.readTree(region.get("validation_report").toString()).path("classificationId");
        require("APPROVED".equals(region.get("status")) && link.isIntegralNumber() && link.canConvertToLong()
                && link.longValue() == classification, "현황 묶음의 승인 지역·분류 연결 오류");
        require("APPROVED".equals(jdbc.queryForObject("SELECT status FROM national_industry_release WHERE classification_id=?", String.class, classification)), "업종 분류가 승인 전입니다.");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM national_quality_issue WHERE batch_id IN (?,?) AND impact_scope='GLOBAL'", Long.class, populationId, storeId) == 0,
                "영향 지역을 확정하지 못한 GLOBAL 이슈가 있어 새 게시를 보류합니다.");
        long accepted = jdbc.queryForObject("SELECT COUNT(*) FROM store_snapshot_entry WHERE batch_id=? AND row_status='ACCEPTED'", Long.class, storeId);
        long summed = jdbc.queryForObject("SELECT COALESCE(SUM(accepted_store_count),0) FROM store_industry_count WHERE batch_id=?", Long.class, storeId);
        require(accepted == number(stores, "accepted_row_count") && accepted == summed, "업소 사실·집계 회계 불일치");
        require(jdbc.queryForObject("SELECT COALESCE(SUM(source_row_count),0) FROM store_region_coverage WHERE batch_id=?", Long.class, storeId)
                == number(stores, "source_row_count"), "원천 지역 범위 회계 불일치");
        // 업종 단위 영향이 있는 행은 반드시 해당 집계에 HOLD로 반영되어야 한다.
        require(jdbc.queryForObject("""
                SELECT COUNT(*) FROM national_quality_issue q LEFT JOIN store_industry_count c
                    ON c.batch_id=q.batch_id AND c.region_code=q.affected_region_code AND c.industry_code=q.affected_industry_code
                WHERE q.batch_id=? AND q.impact_scope='REGION_INDUSTRY'
                    AND (c.region_code IS NULL OR c.count_quality_status<>'HOLD' OR c.count_affecting_issue_count=0)
                """, Long.class, storeId) == 0, "보류 영향이 집계에 누락되었습니다.");
        require(jdbc.queryForObject("""
                SELECT COUNT(*) FROM national_quality_issue q JOIN store_industry_count c
                    ON c.batch_id=q.batch_id AND c.region_code=q.affected_region_code
                WHERE q.batch_id=? AND q.impact_scope='REGION' AND c.count_quality_status<>'HOLD'
                """, Long.class, storeId) == 0, "지역 전체의 보류 영향이 누락되었습니다.");
        return new Bundle(populationId, storeId, population.get("source_period_key").toString(), mapping, classification);
    }

    private Map<String, Object> batch(long id, String dataset) {
        var batch = jdbc.queryForMap("SELECT * FROM national_import_batch WHERE batch_id=?", id);
        require(dataset.equals(batch.get("dataset_code")) && "SUCCEEDED".equals(batch.get("status")), "해당 데이터셋의 성공 배치가 아닙니다.");
        var report = mapper.readTree(batch.get("validation_report").toString());
        require(report.path("importVerified").asBoolean(false)
                && StatisticsBatchService.WRITER_VERSION.equals(report.path("writerVersion").asText()), "검증된 T14 적재 기록이 없습니다.");
        String table = dataset.equals("POPULATION_MONTH") ? "resident_population_month" : "store_snapshot_entry";
        require(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE batch_id=?", Long.class, id) == report.path("factRows").asLong(-1), "사실 행 수가 적재 후 변경되었습니다.");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM national_quality_issue WHERE batch_id=?", Long.class, id) == report.path("issueRows").asLong(-1), "품질 기록이 적재 후 변경되었습니다.");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM national_source_artifact WHERE batch_id=?", Long.class, id) > 0, "원본 추적 기록 누락");
        return batch;
    }

    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    public record Bundle(long populationId, long storeId, String month, long mappingId, long classificationId) {}
}
