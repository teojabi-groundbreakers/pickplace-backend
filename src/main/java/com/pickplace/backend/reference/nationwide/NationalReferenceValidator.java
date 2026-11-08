package com.pickplace.backend.reference.nationwide;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import static com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot.*;

@Component
public class NationalReferenceValidator {

    public Validation validate(NationalReferenceSnapshot snapshot) {
        require(snapshot != null, "Missing snapshot");
        require(text(snapshot.version()) && snapshot.version().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"), "Invalid version");
        require(text(snapshot.parserVersion()) && text(snapshot.policyVersion()) && text(snapshot.classificationVersion()), "Missing versions");
        require(snapshot.collectedAt() != null && snapshot.referenceOn() != null, "Missing snapshot dates");
        require(text(snapshot.classificationSourceUrl()) && snapshot.classificationSourceSha256() != null
                && snapshot.classificationSourceSha256().matches("[0-9a-f]{64}"), "Missing classification source provenance");
        var artifacts = unique(snapshot.artifacts(), Artifact::role);
        require(artifacts.keySet().equals(Set.of("H_CURRENT_ZIP", "H_HISTORY_ZIP", "LAYOUT")), "Required official artifacts missing");
        for (var artifact : artifacts.values()) {
            require(artifact.sha256() != null && artifact.sha256().matches("[0-9a-f]{64}")
                    && artifact.bytes() > 0 && artifact.collectedAt() != null
                    && text(artifact.noticeUrl()) && text(artifact.downloadUrl()) && text(artifact.storageKey())
                    && text(artifact.attachmentVersion()) && text(artifact.encoding()), "Invalid artifact metadata");
            review(artifact.usageStatus(), artifact.usageEvidence());
        }
        var nodes = unique(snapshot.nodes(), Node::code);
        require(!nodes.isEmpty(), "Empty official nodes");
        var sourceRows = new HashSet<String>();
        for (var node : nodes.values()) {
            require(h10(node.code()) && text(node.province()) && text(node.rawCreatedOn())
                    && artifacts.containsKey(node.artifactRole()) && !node.artifactRole().equals("LAYOUT")
                    && node.rowNumber() > 0, "Invalid official node");
            require(sourceRows.add(node.artifactRole() + ":" + node.rowNumber()), "Duplicate official source row");
            require(Set.of("CURRENT", "LEGACY").contains(node.status())
                    && (node.status().equals("CURRENT") == (node.rawAbolishedOn() == null)), "Invalid official status");
            if ("VALID".equals(node.dateStatus())) {
                require(node.createdOn() != null && node.dateIssue() == null
                        && node.createdOn().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(node.rawCreatedOn()), "Invalid created date");
                require(node.status().equals("CURRENT") ? node.abolishedOn() == null
                        : node.abolishedOn() != null && !node.createdOn().isAfter(node.abolishedOn())
                        && node.abolishedOn().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(node.rawAbolishedOn()), "Invalid abolished date");
            } else {
                require("INVALID".equals(node.dateStatus()) && node.createdOn() == null
                        && node.abolishedOn() == null && text(node.dateIssue()), "Invalid date quarantine");
            }
        }
        var navigation = unique(snapshot.navigation(), Navigation::code);
        require(!navigation.isEmpty(), "Empty navigation");
        for (var row : navigation.values()) {
            var node = nodes.get(row.code());
            require(node != null && node.status().equals("CURRENT"), "Navigation requires current official node");
            require(nodes.containsKey(row.province()) && navigation.containsKey(row.province()), "Unknown province");
            require(text(row.displayName()) && Set.of("PROVINCE", "CITY_CONTAINER", "GROUP", "EUP", "MYEON", "DONG", "BRANCH", "UNKNOWN").contains(row.kind()), "Invalid node kind");
            review(row.reviewStatus(), row.evidence());
            require(Set.of("PROVINCE", "GROUP", "LOCALITY", "NONE").contains(row.role()), "Invalid selection role");
            require(!row.selectable() || row.reviewStatus().equals("VERIFIED") && !row.role().equals("NONE") && node.dateStatus().equals("VALID"), "Unreviewed selectable node");
            require(!row.autoSelect() || row.selectable() && row.role().equals("GROUP"), "Invalid auto selection");
            if (row.parent() != null) {
                require(navigation.containsKey(row.parent()) && !row.code().equals(row.parent())
                        && row.province().equals(navigation.get(row.parent()).province()), "Invalid hierarchy parent");
            }
            if (row.role().equals("PROVINCE")) {
                require(row.code().equals(row.province()) && row.parent() == null && row.selectionParent() == null, "Invalid province root");
            } else if (!row.role().equals("NONE")) {
                var parent = navigation.get(row.selectionParent());
                require(parent != null && row.province().equals(parent.province())
                        && parent.role().equals(row.role().equals("GROUP") ? "PROVINCE" : "GROUP"), "Invalid selection parent role");
                require(!row.selectable() || parent.selectable(), "Selectable child of hidden parent");
            }
            checkPath(row, navigation);
        }
        var sources = unique(snapshot.sources(), NationalReferenceValidator::sourceKey);
        require(!sources.isEmpty(), "Empty source regions");
        for (var row : sources.values()) {
            require(Set.of("MOIS_RESIDENT", "SEMAS_STORE", "SEOUL_COMMERCIAL").contains(row.system()), "Unknown source system");
            require(row.code() != null && row.code().matches(row.system().equals("MOIS_RESIDENT") ? "[0-9]{10}" : "[0-9]{8}"), "Invalid source code");
            require(text(row.version()) && text(row.name()) && row.referenceOn() != null && row.collectedAt() != null
                    && text(row.url()) && text(row.evidence()), "Missing source evidence");
            require(row.system().equals("MOIS_RESIDENT") ? row.districtCode() == null || h10(row.districtCode())
                    : row.districtCode() != null && row.districtCode().matches("[0-9]{5}"), "Invalid source parent");
        }
        var mappingKeys = new HashSet<String>();
        var verifiedSources = new HashSet<String>();
        for (var row : Objects.requireNonNull(snapshot.mappings(), "Missing mappings")) {
            String key = sourceKey(row.system(), row.sourceCode(), row.sourceVersion());
            var source = sources.get(key);
            var target = nodes.get(row.targetCode());
            require(source != null && target != null && Objects.equals(source.referenceOn(), row.referenceOn()), "Mapping source/target/version mismatch");
            require(mappingKeys.add(key + ":" + row.targetCode()), "Duplicate mapping");
            review(row.reviewStatus(), row.evidence());
            if (row.reviewStatus().equals("VERIFIED")) {
                require(verifiedSources.add(key) && target.dateStatus().equals("VALID"), "Ambiguous verified mapping or invalid target date");
                require(!row.system().equals("MOIS_RESIDENT") || row.sourceCode().equals(row.targetCode()), "Population identity mismatch");
            }
        }
        var industries = unique(snapshot.industries(), Industry::code);
        require(!industries.isEmpty(), "Empty classification");
        for (var row : industries.values()) {
            require(row.code() != null && row.code().matches("[A-Z0-9]{1,20}") && text(row.name()) && text(row.displayName())
                    && row.displayOrder() >= 0, "Invalid industry");
            require(Set.of("LARGE", "MIDDLE", "SMALL").contains(row.level()), "Invalid industry level");
            if (row.level().equals("LARGE")) {
                require(row.parentCode() == null, "Large industry has parent");
            } else {
                var parent = industries.get(row.parentCode());
                require(parent != null && parent.level().equals(row.level().equals("MIDDLE") ? "LARGE" : "MIDDLE"), "Invalid industry parent");
            }
            require(!row.supported() || row.level().equals("SMALL"), "Only small industries supported");
        }
        boolean approvable = artifacts.values().stream().allMatch(a -> a.usageStatus().equals("VERIFIED"))
                && navigation.values().stream().allMatch(n -> n.reviewStatus().equals("VERIFIED"))
                && navigation.values().stream().anyMatch(Navigation::selectable)
                && sources.keySet().equals(verifiedSources)
                && snapshot.mappings().stream().allMatch(m -> m.reviewStatus().equals("VERIFIED"));
        return new Validation(Map.of("nodes", nodes.size(), "navigation", navigation.size(), "sources", sources.size(),
                "mappings", snapshot.mappings().size(), "industries", industries.size()), approvable,
                snapshot.industries().stream().filter(Industry::supported).map(Industry::code).toList());
    }

    private void checkPath(Navigation row, Map<String, Navigation> rows) {
        var seen = new HashSet<String>();
        Navigation cursor = row;
        while (cursor != null) {
            require(seen.add(cursor.code()), "Hierarchy cycle");
            if (cursor.parent() == null) {
                require(cursor.code().equals(row.province()) && cursor.role().equals("PROVINCE"), "Hierarchy does not reach province");
                break;
            }
            cursor = rows.get(cursor.parent());
            require(cursor != null, "Missing hierarchy parent");
        }
    }

    static String sourceKey(Source row) { return sourceKey(row.system(), row.code(), row.version()); }
    static String sourceKey(String system, String code, String version) { return system + ":" + code + ":" + version; }
    private static boolean h10(String code) { return code != null && code.matches("[0-9]{10}"); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void review(String status, String evidence) {
        require(status != null && Set.of("PENDING", "VERIFIED", "BLOCKED").contains(status), "Invalid review status");
        require(!status.equals("VERIFIED") || text(evidence), "Verified review requires evidence");
    }
    private static <T> Map<String, T> unique(List<T> rows, Function<T, String> key) {
        require(rows != null, "Missing snapshot collection");
        Map<String, T> result = new HashMap<>();
        for (T row : rows) {
            require(row != null && key.apply(row) != null && result.putIfAbsent(key.apply(row), row) == null, "Duplicate/missing snapshot key");
        }
        return result;
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    public record Validation(Map<String, Integer> counts, boolean approvable, List<String> supportedCodes) {}
}
