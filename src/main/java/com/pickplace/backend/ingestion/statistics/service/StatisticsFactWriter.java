package com.pickplace.backend.ingestion.statistics.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.ingestion.statistics.service.StatisticsBatchService.Batch;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import com.pickplace.backend.ingestion.validation.service.ValidationReference;
import tools.jackson.databind.json.JsonMapper;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 청크 하나의 사실 행과 이슈를 같은 트랜잭션에 저장한다. 중복·잘못된 키는 원문 위치로 격리한다. */
@Service
public class StatisticsFactWriter {
    private static final String POPULATION_SQL = """
            INSERT INTO resident_population_month (batch_id, reference_month, reference_on, region_code,
                source_region_code, source_region_version, mapping_release_id, total_population, male_population,
                female_population, age_sex_counts, row_status, reason_code, temporal_region_status, source_artifact_no, source_row_number)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String STORE_SQL = """
            INSERT INTO store_snapshot_entry (batch_id, source_period_key, source_region_code, source_region_version,
                legal_dong_code, mapping_release_id, region_code, classification_id, industry_small_code,
                source_store_id, raw_industry_large, raw_industry_middle, raw_industry_small, store_name, road_address,
                longitude, latitude, location_quality_status, row_status, reason_code, temporal_region_status,
                source_artifact_no, source_row_number)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String ISSUE_SQL = """
            INSERT INTO national_quality_issue (batch_id, source_artifact_no, source_row_number, source_region_code,
                source_store_id, field_name, reason_code, severity, affects_count, impact_scope, affected_region_code,
                classification_id, affected_industry_code, affected_industry_level, raw_value, evidence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final StatisticsBatchService batches;

    public StatisticsFactWriter(JdbcTemplate jdbc, JsonMapper mapper, StatisticsBatchService batches) {
        this.jdbc = jdbc; this.mapper = mapper; this.batches = batches;
    }

    public void artifacts(Batch batch, StatisticsImportInput input) {
        batches.inTransaction(() -> {
            batches.lockWritable(batch.id(), "RUNNING");
            var source = input.source(); var r = input.report();
            require(input.raw().toString().length() <= 500, "원본 보관 경로가 DB 저장 길이를 초과합니다.");
            jdbc.update("""
                    INSERT INTO national_source_artifact (batch_id, artifact_no, artifact_kind, source_url, storage_key,
                        sha256, byte_count, encoding, column_names, source_row_count, collected_at)
                    VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batch.id(), source.format(), source.downloadUrl(), input.raw().toString(), r.rawSha256(),
                    source.expectedBytes(), source.format().equals("CSV") ? source.encoding() : null,
                    source.format().equals("CSV") ? mapper.writeValueAsString(source.headers()) : null,
                    r.sourceRows(), LocalDateTime.ofInstant(r.collectedAt(), ZoneOffset.UTC));
            if (source.format().equals("ZIP")) for (var member : r.rawValidation().members()) {
                jdbc.update("""
                        INSERT INTO national_source_artifact (batch_id, artifact_no, artifact_kind, parent_artifact_no,
                            source_url, storage_key, member_path, sha256, byte_count, crc32, encoding, column_names, source_row_count, collected_at)
                        VALUES (?, ?, 'ZIP_MEMBER', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, batch.id(), r.artifactNumbers().get(member.name()), source.downloadUrl(), input.raw().toString(),
                        member.name(), member.sha256(), member.bytes(), member.crc32(), source.encoding(),
                        mapper.writeValueAsString(member.columns()), member.rows(), LocalDateTime.ofInstant(r.collectedAt(), ZoneOffset.UTC));
            }
            return null;
        });
    }

    void chunk(Batch batch, StatisticsImportInput input, List<ValidationRow> rows,
            ValidationReference reference, StatisticsImportLedger ledger) {
        var facts = new ArrayList<ValidationRow>();
        var issues = new ArrayList<IssueEntry>();
        boolean store = input.source().dataset().equals("STORE_SNAPSHOT");
        for (var row : rows) {
            validateRow(row, input, reference, store);
            ledger.add(row);
            if (row.disposition().equals("DUPLICATE")) continue;
            for (var issue : row.issues()) issues.add(new IssueEntry(row, issue));
            boolean conflict = row.issues().stream().anyMatch(i -> i.code().equals("DUPLICATE_ID_CONFLICT"));
            // PK 충돌을 무시하거나 마지막 행으로 덮어쓰지 않는다. 사실로 표현 불가능한 행은 이슈에만 남긴다.
            boolean knownSource = input.sourceCodes().contains(row.sourceRegionCode());
            if (!conflict && knownSource && (store ? row.sourceKey().matches("[A-Za-z0-9]{1,40}") && row.store() != null
                    : row.population() != null && row.regionCode() != null && row.sourceKey().equals(row.regionCode()))) facts.add(row);
        }
        batches.inTransaction(() -> {
            batches.lockWritable(batch.id(), "RUNNING");
            if (store) writeStores(batch, input, facts); else writePopulation(batch, input, facts);
            jdbc.batchUpdate(ISSUE_SQL, issues, 500, (ps, entry) -> {
                var row = entry.row(); var issue = entry.issue(); boolean industryScope = issue.impactScope().equals("REGION_INDUSTRY");
                Object[] values = {batch.id(), row.position().artifactNo(), row.position().rowNumber(), optionalKey(row.sourceRegionCode()),
                        store ? optionalKey(row.sourceKey()) : null, issue.field(), issue.code(), issue.severity(), issue.affectsCount(),
                        issue.impactScope(), issue.affectedRegion(), industryScope ? batch.classificationId() : null,
                        issue.affectedIndustry(), industryScope ? "SMALL" : null, mapper.writeValueAsString(issue.rawValue()),
                        mapper.writeValueAsString(Map.of("position", row.position(), "sourceKey", row.sourceKey(), "sourceRegion", row.sourceRegionCode()))};
                for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            });
            return null;
        });
        ledger.factRows += facts.size();
    }

    private void writePopulation(Batch b, StatisticsImportInput in, List<ValidationRow> rows) {
        jdbc.batchUpdate(POPULATION_SQL, rows, 500, (ps, row) -> {
            var p = row.population();
            Object[] values = {b.id(), in.report().periodKey(), LocalDate.parse(in.report().sourceReferenceOn()), row.regionCode(),
                    row.sourceRegionCode(), in.report().sourceRegionVersion(), b.mappingId(), p.total(), p.male(), p.female(),
                    p.rowStatus().equals("AVAILABLE") ? mapper.writeValueAsString(Map.of("male", p.maleAges(), "female", p.femaleAges())) : null,
                    p.rowStatus(), p.rowStatus().equals("AVAILABLE") ? null : firstReason(row), row.temporalStatus(),
                    row.position().artifactNo(), row.position().rowNumber()};
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
        });
    }

    private void writeStores(Batch b, StatisticsImportInput in, List<ValidationRow> rows) {
        jdbc.batchUpdate(STORE_SQL, rows, 500, (ps, row) -> {
            var s = row.store();
            Object[] values = {b.id(), in.report().periodKey(), row.sourceRegionCode(), in.report().sourceRegionVersion(),
                    s.legalDongCode(), b.mappingId(), row.regionCode(), b.classificationId(), row.industryCode(), row.sourceKey(),
                    s.largeCode(), s.middleCode(), s.rawSmallCode(), s.name(), s.address(), s.longitude(), s.latitude(), s.locationStatus(),
                    row.disposition(), row.disposition().equals("ACCEPTED") ? null : firstReason(row), row.temporalStatus(),
                    row.position().artifactNo(), row.position().rowNumber()};
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
        });
    }

    private void validateRow(ValidationRow row, StatisticsImportInput in, ValidationReference reference, boolean store) {
        require(row.position() != null && row.issues() != null && row.sourceRegionCode() != null && row.sourceKey() != null, "정제 행 필수 정보 누락");
        var member = in.report().rawValidation().members().stream()
                .filter(m -> Objects.equals(m.name(), row.position().member())).findFirst().orElseThrow(() -> new IllegalArgumentException("원본 멤버 없음"));
        String file = store ? row.position().member() : "raw.csv";
        require(Objects.equals(in.report().artifactNumbers().get(file), row.position().artifactNo())
                && row.position().rowNumber() > 0 && row.position().rowNumber() <= member.rows(), "원문 위치 오류");
        var resolved = reference.resolve(in.source().sourceSystem(), row.sourceRegionCode(), in.report().sourceRegionVersion(), LocalDate.parse(in.report().sourceReferenceOn()));
        require(Objects.equals(resolved.regionCode(), row.regionCode()) && resolved.temporalStatus().equals(row.temporalStatus()), "정제 행의 지역·시점이 기준정보와 다릅니다.");
        if (row.industryCode() != null) require(store && row.store() != null && row.industryCode().equals(row.store().rawSmallCode())
                && reference.validIndustry(row.store().largeCode(), row.store().middleCode(), row.store().rawSmallCode()), "정제 행의 업종 계층 오류");
        boolean countProblem = false;
        for (var issue : row.issues()) {
            require(issue.code() != null && !issue.code().isBlank() && issue.field() != null && !issue.field().isBlank(), "이슈 필수 값 누락");
            if (!issue.affectsCount()) {
                require("NONE".equals(issue.impactScope()) && issue.affectedRegion() == null && issue.affectedIndustry() == null, "경고 영향 범위 오류");
                continue;
            }
            countProblem = true;
            String scope = row.regionCode() == null ? "GLOBAL" : row.industryCode() == null ? "REGION" : "REGION_INDUSTRY";
            require(scope.equals(issue.impactScope()) && Objects.equals(row.regionCode(), issue.affectedRegion())
                    && Objects.equals(row.regionCode() == null ? null : row.industryCode(), issue.affectedIndustry()), "행과 집계 영향 범위 불일치");
        }
        require(!row.disposition().equals("ACCEPTED") || !countProblem && "VALID".equals(row.temporalStatus())
                && row.regionCode() != null && (store ? row.store() != null && row.industryCode() != null : row.population() != null), "채택 행의 필수 조건 위반");
        require(row.disposition().equals("ACCEPTED") || row.disposition().equals("DUPLICATE") || countProblem, "보류·거부 사유 누락");
    }

    private static String firstReason(ValidationRow row) {
        return row.issues().stream().filter(ValidationRow.Issue::affectsCount).map(ValidationRow.Issue::code).findFirst().orElseThrow();
    }
    private static String optionalKey(String value) { return value != null && value.codePointCount(0, value.length()) <= 100 ? value : null; }
    private record IssueEntry(ValidationRow row, ValidationRow.Issue issue) {}
}
