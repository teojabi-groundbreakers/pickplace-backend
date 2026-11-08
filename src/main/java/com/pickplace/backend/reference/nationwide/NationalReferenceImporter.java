package com.pickplace.backend.reference.nationwide;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot.*;
import static com.pickplace.backend.reference.nationwide.NationalReferenceValidator.*;

@Service
public class NationalReferenceImporter {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final NationalReferenceValidator validator;
    private final TransactionTemplate transaction;

    public NationalReferenceImporter(JdbcTemplate jdbc, JsonMapper mapper, NationalReferenceValidator validator,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactions);
    }

    public ImportResult importSnapshot(NationalReferenceLoader.Loaded loaded) {
        var validation = validator.validate(loaded.snapshot());
        require(loaded.sha256() != null && loaded.sha256().matches("[0-9a-f]{64}"), "Invalid snapshot checksum");
        return transaction.execute(status -> importValidated(loaded, validation));
    }

    private ImportResult importValidated(NationalReferenceLoader.Loaded loaded, Validation validation) {
        var snapshot = loaded.snapshot();
        jdbc.update("""
                INSERT INTO region_release (release_version, parser_version, policy_version, started_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE release_id = release_id
                """, snapshot.version(), snapshot.parserVersion(), snapshot.policyVersion());
        var release = jdbc.queryForMap("SELECT * FROM region_release WHERE release_version = ? FOR UPDATE", snapshot.version());
        long releaseId = ((Number) release.get("release_id")).longValue();
        if (release.get("validation_report") != null) {
            var report = mapper.readTree(release.get("validation_report").toString());
            require(loaded.sha256().equals(report.path("snapshotSha256").asText()), "Existing version has different content");
            require(release.get("status").equals("READY") || release.get("status").equals("APPROVED"), "Existing release is not reusable");
            return new ImportResult(releaseId, classificationId(snapshot.classificationVersion()), false);
        }
        require(release.get("status").equals("LOADING"), "Existing release requires recovery review");
        insertArtifacts(releaseId, snapshot);
        insertNodes(releaseId, snapshot);
        insertNavigation(releaseId, snapshot);
        insertSources(snapshot);
        jdbc.batchUpdate("""
                INSERT INTO region_stat_mapping
                    (release_id, source_system, source_region_code, source_region_version, region_code,
                     review_status, reference_snapshot_on, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.mappings(), 300, (ps, row) -> {
            ps.setLong(1, releaseId); ps.setString(2, row.system()); ps.setString(3, row.sourceCode());
            ps.setString(4, row.sourceVersion()); ps.setString(5, row.targetCode()); ps.setString(6, row.reviewStatus());
            ps.setObject(7, row.referenceOn()); ps.setString(8, row.evidence());
        });
        long classificationId = insertIndustries(snapshot);
        String counts = mapper.writeValueAsString(validation.counts());
        String report = mapper.writeValueAsString(Map.of("snapshotSha256", loaded.sha256(),
                "approvable", validation.approvable(), "classificationId", classificationId,
                "supportedCodes", validation.supportedCodes(), "counts", validation.counts()));
        jdbc.update("""
                UPDATE region_release SET status = 'READY', finished_at = UTC_TIMESTAMP(6),
                    expected_counts = ?, actual_counts = ?, validation_report = ? WHERE release_id = ?
                """, counts, counts, report, releaseId);
        return new ImportResult(releaseId, classificationId, true);
    }

    private void insertArtifacts(long id, NationalReferenceSnapshot snapshot) {
        jdbc.batchUpdate("""
                INSERT INTO region_source_artifact
                    (release_id, artifact_role, notice_url, download_url, storage_key, sha256, byte_count,
                     collected_at, attachment_version, member_path, encoding, usage_review_status, usage_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.artifacts(), 100, (ps, row) -> {
            ps.setLong(1, id); ps.setString(2, row.role()); ps.setString(3, row.noticeUrl()); ps.setString(4, row.downloadUrl());
            ps.setString(5, row.storageKey()); ps.setString(6, row.sha256()); ps.setLong(7, row.bytes());
            ps.setObject(8, utc(row.collectedAt())); ps.setString(9, row.attachmentVersion()); ps.setString(10, row.memberPath());
            ps.setString(11, row.encoding()); ps.setString(12, row.usageStatus()); ps.setString(13, row.usageEvidence());
        });
    }

    private void insertNodes(long id, NationalReferenceSnapshot snapshot) {
        jdbc.batchUpdate("""
                INSERT INTO service_region (region_code, created_at) VALUES (?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE region_code = region_code
                """, snapshot.nodes(), 300, (ps, row) -> ps.setString(1, row.code()));
        jdbc.batchUpdate("""
                INSERT INTO official_region_node
                    (release_id, region_code, province_name, district_name, locality_name, raw_created_on,
                     raw_abolished_on, created_on, abolished_on, date_quality_status, date_issue_code,
                     official_status, artifact_role, source_row_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.nodes(), 300, (ps, row) -> {
            ps.setLong(1, id); ps.setString(2, row.code()); ps.setString(3, row.province()); ps.setString(4, row.district());
            ps.setString(5, row.locality()); ps.setString(6, row.rawCreatedOn()); ps.setString(7, row.rawAbolishedOn());
            ps.setObject(8, row.createdOn()); ps.setObject(9, row.abolishedOn()); ps.setString(10, row.dateStatus());
            ps.setString(11, row.dateIssue()); ps.setString(12, row.status()); ps.setString(13, row.artifactRole()); ps.setLong(14, row.rowNumber());
        });
    }

    private void insertNavigation(long id, NationalReferenceSnapshot snapshot) {
        jdbc.batchUpdate("""
                INSERT INTO region_navigation
                    (release_id, region_code, node_kind, hierarchy_parent_code, province_code, selection_parent_code,
                     display_name, display_name_derived, selection_role, selectable, auto_select_group, review_status, review_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.navigation(), 300, (ps, row) -> {
            ps.setLong(1, id); ps.setString(2, row.code()); ps.setString(3, row.kind()); ps.setString(4, row.parent());
            ps.setString(5, row.province()); ps.setString(6, row.selectionParent()); ps.setString(7, row.displayName());
            ps.setBoolean(8, row.derivedName()); ps.setString(9, row.role()); ps.setBoolean(10, row.selectable());
            ps.setBoolean(11, row.autoSelect()); ps.setString(12, row.reviewStatus()); ps.setString(13, row.evidence());
        });
    }

    private void insertSources(NationalReferenceSnapshot snapshot) {
        Map<String, Source> existing = new HashMap<>();
        jdbc.query("SELECT * FROM source_region_reference", rs -> {
            var row = new Source(rs.getString("source_system"), rs.getString("source_region_code"), rs.getString("source_region_version"),
                    rs.getString("source_region_name"), rs.getString("source_district_code"), rs.getString("source_district_name"),
                    rs.getDate("reference_snapshot_on").toLocalDate(), rs.getString("source_url"),
                    mapper.writeValueAsString(mapper.readTree(rs.getString("reference_evidence"))),
                    rs.getObject("collected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
            existing.put(sourceKey(row), row);
        });
        var fresh = snapshot.sources().stream().filter(row -> {
            var old = existing.get(sourceKey(row));
            var canonical = new Source(row.system(), row.code(), row.version(), row.name(), row.districtCode(), row.districtName(),
                    row.referenceOn(), row.url(), old == null ? row.evidence() : old.evidence(), row.collectedAt());
            require(old == null || old.equals(canonical) && mapper.readTree(old.evidence()).equals(mapper.readTree(row.evidence())),
                    "Source version content conflict: " + sourceKey(row));
            return old == null;
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO source_region_reference
                    (source_system, source_region_code, source_region_version, source_region_name, source_district_code,
                     source_district_name, reference_snapshot_on, source_url, reference_evidence, collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fresh, 300, (ps, row) -> {
            ps.setString(1, row.system()); ps.setString(2, row.code()); ps.setString(3, row.version()); ps.setString(4, row.name());
            ps.setString(5, row.districtCode()); ps.setString(6, row.districtName()); ps.setObject(7, row.referenceOn());
            ps.setString(8, row.url()); ps.setString(9, row.evidence()); ps.setObject(10, utc(row.collectedAt()));
        });
    }

    private long insertIndustries(NationalReferenceSnapshot snapshot) {
        String fingerprint = NationalReferenceLoader.sha256(mapper.writeValueAsBytes(snapshot.industries()));
        jdbc.update("""
                INSERT INTO national_industry_release
                    (version, source_url, source_sha256, reference_snapshot_on, collected_at)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE classification_id = classification_id
                """, snapshot.classificationVersion(), snapshot.classificationSourceUrl(), snapshot.classificationSourceSha256(),
                snapshot.referenceOn(), utc(snapshot.collectedAt()));
        var classification = jdbc.queryForMap("SELECT * FROM national_industry_release WHERE version = ? FOR UPDATE", snapshot.classificationVersion());
        long id = ((Number) classification.get("classification_id")).longValue();
        require(snapshot.classificationSourceSha256().equals(classification.get("source_sha256"))
                && snapshot.classificationSourceUrl().equals(classification.get("source_url"))
                && snapshot.referenceOn().equals(((java.sql.Date) classification.get("reference_snapshot_on")).toLocalDate()),
                "Classification source conflict");
        if (!classification.get("status").equals("LOADING")) {
            require(classification.get("status").equals("READY") || classification.get("status").equals("APPROVED"),
                    "Existing classification is not reusable");
            require(fingerprint.equals(mapper.readTree(classification.get("validation_report").toString()).path("fingerprint").asText()),
                    "Classification version content conflict");
            return id;
        }
        for (String level : new String[] {"LARGE", "MIDDLE", "SMALL"}) {
            var rows = snapshot.industries().stream().filter(row -> row.level().equals(level)).toList();
            jdbc.batchUpdate("""
                    INSERT INTO national_industry_code
                        (classification_id, industry_code, industry_name, industry_level, parent_code, parent_level,
                         display_name, display_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows, 300, (ps, row) -> {
                ps.setLong(1, id); ps.setString(2, row.code()); ps.setString(3, row.name()); ps.setString(4, row.level());
                ps.setString(5, row.parentCode()); ps.setString(6, level.equals("LARGE") ? null : level.equals("MIDDLE") ? "LARGE" : "MIDDLE");
                ps.setString(7, row.displayName()); ps.setInt(8, row.displayOrder());
            });
        }
        jdbc.update("UPDATE national_industry_release SET status = 'READY', validation_report = ? WHERE classification_id = ?",
                mapper.writeValueAsString(Map.of("industryCount", snapshot.industries().size(), "fingerprint", fingerprint)), id);
        return id;
    }

    private long classificationId(String version) {
        return jdbc.queryForObject("SELECT classification_id FROM national_industry_release WHERE version = ?", Long.class, version);
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ImportResult(long releaseId, long classificationId, boolean inserted) {}
}
