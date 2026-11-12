package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import com.pickplace.backend.ingestion.detail.model.DetailImportManifest;
import com.pickplace.backend.ingestion.detail.model.DetailImportManifest.Mappings;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Component
public class DetailRowValidator {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public DetailRowValidator(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Reference reference(DetailInputLoader.Input input) {
        var m = input.manifest();
        var release =
                jdbc.queryForMap(
                        "SELECT release_id,status,validation_report FROM region_release WHERE"
                                + " release_version=?",
                        m.referenceVersion());
        require(
                "APPROVED".equals(release.get("status"))
                        && mapper.readTree(release.get("validation_report").toString())
                                .path("snapshotSha256")
                                .asText()
                                .equals(m.referenceSha256()),
                "승인 기준정보 또는 해시 불일치");
        long id = ((Number) release.get("release_id")).longValue();
        var nodes = new HashMap<String, Node>();
        jdbc.query(
                "SELECT"
                    + " region_code,province_name,locality_name,created_on,abolished_on,date_quality_status"
                    + " FROM official_region_node WHERE release_id=?",
                rs -> {
                    nodes.put(
                            rs.getString(1),
                            new Node(
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getObject(4, LocalDate.class),
                                    rs.getObject(5, LocalDate.class),
                                    rs.getString(6)));
                },
                id);
        var verified = new HashSet<String>();
        jdbc.query(
                "SELECT source_region_code,region_code FROM region_stat_mapping WHERE release_id=?"
                        + " AND source_system='SEMAS_STORE' AND review_status='VERIFIED'",
                rs -> {
                    verified.add(rs.getString(1) + ":" + rs.getString(2));
                },
                id);
        var mappings = new HashMap<String, DetailImportManifest.RegionMapping>();
        boolean national = NationalStoreQuarterAdapter.ID.equals(m.adapter());
        Set<String> industries = national ? approvedNationalIndustries(release) : Set.of();
        for (var map : mapper.readValue(input.mappingJson(), Mappings.class).regions()) {
            Node node = nodes.get(map.targetCode());
            require(
                    node != null
                            && (national || "서울특별시".equals(node.province()))
                            && node.name() != null
                            && sameLocalityName(node.name(), map.sourceName())
                            && verified.contains(map.sourceCode() + ":" + map.targetCode()),
                    "기존 검증 대응·공식 명칭과 다른 대응표: " + map.sourceCode());
            mappings.put(map.sourceCode(), map);
        }
        return new Reference(id, Map.copyOf(nodes), Map.copyOf(mappings), industries);
    }

    private Set<String> approvedNationalIndustries(Map<String, Object> release) {
        var id = mapper.readTree(release.get("validation_report").toString()).path("classificationId");
        require(id.isIntegralNumber() && id.canConvertToLong(), "승인 업종 분류 연결이 필요합니다.");
        var codes = jdbc.queryForList("""
                SELECT c.industry_code FROM national_industry_code c
                JOIN national_industry_release r ON r.classification_id=c.classification_id
                WHERE r.classification_id=? AND r.status='APPROVED' AND c.industry_level='SMALL'
                  AND c.is_supported=TRUE AND c.support_release_status='APPROVED'
                """, String.class, id.longValue());
        require(!codes.isEmpty(), "승인된 지원 업종이 없습니다.");
        return Set.copyOf(codes);
    }

    public Row validate(
            DetailInputLoader.Input input, Reference ref, long rowNumber, Map<String, Object> raw) {
        var m = input.manifest();
        String quarter = text(raw, "stdr_yyqu_cd"), source = text(raw, "adstrd_cd");
        require(m.periods().contains(quarter), "명세 밖 분기: " + quarter);
        String industry = "NONE".equals(m.industryScheme()) ? "" : text(raw, "svc_induty_cd");
        String payload = mapper.writeValueAsString(raw),
                hash =
                        NationalReferenceLoader.sha256(
                                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean validIndustry = switch (m.industryScheme()) {
            case "NONE" -> true;
            case "SEMAS_SMALL" -> ref.nationalIndustries().contains(industry);
            default -> industry.matches("CS[0-9]{6}");
        };
        if (!source.matches("[0-9]{8}") || !validIndustry)
            return new Row(
                    quarter,
                    source,
                    industry,
                    null,
                    "UNRESOLVED",
                    "PENDING",
                    "INVALID",
                    "INVALID_SOURCE_KEY",
                    "{}",
                    payload,
                    hash,
                    rowNumber,
                    false);
        var map = ref.mappings().get(source);
        String region = null,
                mapping = "UNRESOLVED",
                temporal = "PENDING",
                reason = "MAPPING_UNRESOLVED";
        if (map != null && sameLocalityName(map.sourceName(), text(raw, "adstrd_cd_nm"))) {
            region = map.targetCode();
            mapping = "VERIFIED";
            temporal = temporal(ref.nodes().get(region), quarter);
            reason =
                    temporal.equals("VALID")
                            ? null
                            : temporal.equals("PENDING")
                                    ? "BOUNDARY_REVIEW_PENDING"
                                    : "BOUNDARY_MISMATCH";
        }
        var metrics = new TreeMap<String, Object>();
        boolean invalid = false, anyValue = false;
        for (var metric : m.metrics()) {
            Object value = raw.get(metric.field());
            String status = "AVAILABLE", metricReason = null;
            BigDecimal number = null;
            if (value == null || Set.of("", "null", "N/A").contains(value.toString().trim())) {
                status = "NO_DATA";
                metricReason = "SOURCE_VALUE_MISSING";
            } else {
                try {
                    number = new BigDecimal(value.toString().trim());
                    if (number.signum() < 0
                            || number.compareTo(new BigDecimal("9007199254740991")) > 0)
                        throw new NumberFormatException();
                    anyValue = true;
                    if (!metric.definitionStatus().equals("VERIFIED")) {
                        status = "UNVERIFIED";
                        metricReason = "SOURCE_DEFINITION_UNREVIEWED";
                    }
                } catch (NumberFormatException ex) {
                    status = "INVALID";
                    metricReason = "QUALITY_CHECK_FAILED";
                    number = null;
                    invalid = true;
                }
            }
            // 단위·집계 정의는 불변 배치에 한 번만 저장하고, 행에는 값과 개별 상태만 둔다.
            var detail = new LinkedHashMap<String, Object>();
            detail.put("value", number);
            detail.put("status", status);
            detail.put("reasonCode", metricReason);
            metrics.put(metric.field(), detail);
        }
        String state = reason == null ? "VALID" : "HOLD";
        if (invalid) {
            state = "INVALID";
            reason = "QUALITY_CHECK_FAILED";
        } else if (!anyValue && reason == null) {
            state = "HOLD";
            reason = "SOURCE_VALUE_MISSING";
        }
        return new Row(
                quarter,
                source,
                industry,
                region,
                mapping,
                temporal,
                state,
                reason,
                mapper.writeValueAsString(metrics),
                payload,
                hash,
                rowNumber,
                true);
    }

    // 제1동/1동의 공식 표기 차이만 허용한다. 임의 이름 유사도나 코드 자릿수 변환은 하지 않는다.
    static boolean sameLocalityName(String first, String second) {
        if (first == null || second == null) return false;
        String a = first.replace("·", ".").replace(" ", ""),
                b = second.replace("·", ".").replace(" ", "");
        // 서울의 지명 '홍제'는 보존하고, 홍제제1동의 두 번째 제처럼 서수 표기만 제거한다.
        return a.replaceAll("(?<!홍)제(?=[0-9])", "").equals(b.replaceAll("(?<!홍)제(?=[0-9])", ""));
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private static String temporal(Node node, String quarter) {
        if (!"VALID".equals(node.dateStatus()) || node.created() == null) return "PENDING";
        int year = Integer.parseInt(quarter.substring(0, 4)), q = quarter.charAt(4) - '0';
        LocalDate start = LocalDate.of(year, (q - 1) * 3 + 1, 1),
                end = start.plusMonths(3).minusDays(1);
        if (node.created().isAfter(end)
                || node.abolished() != null && !node.abolished().isAfter(start))
            return "BOUNDARY_MISMATCH";
        if (node.created().isAfter(start)
                || node.abolished() != null && !node.abolished().isAfter(end)) return "PENDING";
        return "VALID";
    }

    public record Reference(
            long releaseId,
            Map<String, Node> nodes,
            Map<String, DetailImportManifest.RegionMapping> mappings,
            Set<String> nationalIndustries) {}

    public record Node(
            String province,
            String name,
            LocalDate created,
            LocalDate abolished,
            String dateStatus) {}

    public record Row(
            String period,
            String sourceRegion,
            String sourceIndustry,
            String region,
            String mappingStatus,
            String temporalStatus,
            String status,
            String reason,
            String metrics,
            String payload,
            String hash,
            long number,
            boolean usableKey) {
        public String key() {
            return period + ":" + sourceRegion + ":" + sourceIndustry;
        }
    }
}
