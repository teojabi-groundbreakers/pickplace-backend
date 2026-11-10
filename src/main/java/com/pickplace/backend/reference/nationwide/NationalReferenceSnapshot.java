package com.pickplace.backend.reference.nationwide;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record NationalReferenceSnapshot(
        String version, String parserVersion, String policyVersion, Instant collectedAt,
        String classificationVersion, String classificationSourceUrl, String classificationSourceSha256,
        LocalDate referenceOn, List<Artifact> artifacts,
        List<Node> nodes, List<Navigation> navigation, List<Source> sources,
        List<Mapping> mappings, List<Industry> industries) {

    public record Artifact(String role, String noticeUrl, String downloadUrl, String storageKey,
            String sha256, long bytes, Instant collectedAt, String attachmentVersion, String memberPath,
            String encoding, String usageStatus, String usageEvidence) {}

    public record Node(String code, String province, String district, String locality,
            String rawCreatedOn, String rawAbolishedOn, LocalDate createdOn, LocalDate abolishedOn,
            String dateStatus, String dateIssue, String status, String artifactRole, long rowNumber) {}

    public record Navigation(String code, String kind, String parent, String province,
            String selectionParent, String displayName, boolean derivedName, String role,
            boolean selectable, boolean autoSelect, String reviewStatus, String evidence) {}

    public record Source(String system, String code, String version, String name, String districtCode,
            String districtName, LocalDate referenceOn, String url, String evidence, Instant collectedAt) {}

    public record Mapping(String system, String sourceCode, String sourceVersion, String targetCode,
            LocalDate referenceOn, String reviewStatus, String evidence) {}

    public record Industry(String code, String name, String level, String parentCode,
            String displayName, boolean supported, int displayOrder) {}
}
