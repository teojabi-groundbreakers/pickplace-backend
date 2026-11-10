package com.pickplace.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot;
import com.pickplace.backend.reference.nationwide.NationalReferenceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class NationalReferenceImportTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private NationalReferenceImporter importer;
    @Autowired private NationalReferencePublisher publisher;
    @Autowired private NationalReferenceValidator validator;
    @Autowired private NationalReferenceLoader loader;
    @TempDir Path temporary;
    private ObjectNode fixture;

    @BeforeEach
    void prepare() throws Exception {
        cleanup();
        try (var stream = new ClassPathResource("reference-data/national-reference-fixture.json").getInputStream()) {
            fixture = (ObjectNode) mapper.readTree(stream);
        }
    }

    @AfterEach
    void cleanup() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        for (String table : List.of("region_publication_event", "region_publication", "region_stat_mapping", "region_navigation",
                "official_region_node", "region_source_artifact", "region_release", "source_region_reference", "service_region")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (String level : List.of("SMALL", "MIDDLE", "LARGE")) {
            jdbc.update("DELETE FROM national_industry_code WHERE industry_level = ?", level);
        }
        jdbc.update("DELETE FROM national_industry_release");
    }

    @Test
    void importsWithoutApprovalOrPublicationAndReimportIsIdempotent() {
        var first = importer.importSnapshot(loaded(fixture));
        var second = importer.importSnapshot(loaded(fixture));
        assertThat(first.inserted()).isTrue();
        assertThat(second.inserted()).isFalse();
        assertThat(second.releaseId()).isEqualTo(first.releaseId());
        assertThat(count("official_region_node")).isEqualTo(3);
        assertThat(count("source_region_reference")).isEqualTo(2);
        assertThat(count("region_stat_mapping")).isEqualTo(2);
        assertThat(count("national_industry_code")).isEqualTo(3);
        assertThat(count("region_publication")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM national_industry_code WHERE is_supported", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM region_release", String.class)).isEqualTo("READY");
    }

    @Test
    void rejectsChangedContentForExistingVersionIncludingAfterApproval() {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        item(fixture, "nodes", 2).put("locality", "changed");
        assertThatThrownBy(() -> importer.importSnapshot(loaded(fixture))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT locality_name FROM official_region_node WHERE locality_name IS NOT NULL", String.class))
                .isEqualTo("청운효자동");
        assertThat(jdbc.queryForObject("SELECT status FROM region_release", String.class)).isEqualTo("APPROVED");
    }

    @Test
    void sourceConflictRollsBackNewReleaseAndKeepsPublishedData() {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        publisher.publish("national-test-v1", 0, "reviewer", "first", false);
        fixture.put("version", "national-test-v2");
        item(fixture, "sources", 0).put("name", "conflicting source");
        assertThatThrownBy(() -> importer.importSnapshot(loaded(fixture))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("region_release")).isEqualTo(1);
        assertThat(count("official_region_node")).isEqualTo(3);
        assertThat(count("region_publication_event")).isEqualTo(1);
    }

    @Test
    void sharesExactSourceAndClassificationAcrossNewReleaseVersions() {
        var first = importer.importSnapshot(loaded(fixture));
        fixture.put("version", "national-test-v2");
        var second = importer.importSnapshot(loaded(fixture));
        assertThat(second.classificationId()).isEqualTo(first.classificationId());
        assertThat(count("source_region_reference")).isEqualTo(2);
        assertThat(count("official_region_node")).isEqualTo(6);
    }

    @Test
    void classificationConflictRollsBackNewNodesAndMappings() {
        importer.importSnapshot(loaded(fixture));
        fixture.put("version", "national-test-v2");
        item(fixture, "industries", 2).put("name", "conflict");
        assertThatThrownBy(() -> importer.importSnapshot(loaded(fixture))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("region_release")).isEqualTo(1);
        assertThat(count("region_stat_mapping")).isEqualTo(2);
    }

    @Test
    void pendingReviewCanBeStoredButCannotBeApprovedOrPublished() {
        for (var node : fixture.path("navigation")) {
            ((ObjectNode) node).put("selectable", false).put("reviewStatus", "PENDING").putNull("evidence");
        }
        importer.importSnapshot(loaded(fixture));
        assertThatThrownBy(() -> publisher.approve("national-test-v1", "reviewer")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish("national-test-v1", 0, "reviewer", "first", false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("region_publication")).isZero();
    }

    @Test
    void approvalEnablesOnlyReviewedSupportedIndustry() {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        publisher.approve("national-test-v1", "reviewer");
        assertThat(jdbc.queryForObject("SELECT status FROM national_industry_release", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("SELECT industry_code FROM national_industry_code WHERE is_supported", String.class))
                .containsExactly("I21201");
        assertThat(count("region_publication")).isZero();
    }

    @Test
    void confirmedExclusionsDoNotBlockApprovalAndNeverBecomeSelectable() throws Exception {
        addConfirmedExclusions(fixture);
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        publisher.publish("national-test-v1", 0, "reviewer", "confirmed exclusion", false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE review_status = 'BLOCKED'", Integer.class)).isEqualTo(76);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE review_status = 'BLOCKED' AND selectable", Integer.class)).isZero();
        assertThat(count("official_region_node")).isEqualTo(79);
    }

    @Test
    void exclusionCannotBeMadeSelectableOrReusedForAnotherOfficialSnapshot() throws Exception {
        addConfirmedExclusions(fixture);
        var changedSource = fixture.deepCopy();
        item(changedSource, "artifacts", 0).put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> validator.validate(mapper.treeToValue(changedSource, NationalReferenceSnapshot.class)))
                .isInstanceOf(IllegalArgumentException.class);
        item(fixture, "navigation", 3).put("reviewStatus", "VERIFIED").put("role", "LOCALITY")
                .put("selectable", true).put("selectionParent", "1111000000");
        assertThatThrownBy(() -> validator.validate(mapper.treeToValue(fixture, NationalReferenceSnapshot.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arbitraryBlockedNodeAndMissingPolicyEvidenceStillPreventApproval() throws Exception {
        addConfirmedExclusions(fixture);
        var noEvidence = fixture.deepCopy();
        item(noEvidence, "navigation", 3).put("evidence", "{}");
        assertThatThrownBy(() -> validator.validate(mapper.treeToValue(noEvidence, NationalReferenceSnapshot.class)))
                .isInstanceOf(IllegalArgumentException.class);
        var extraNode = item(fixture, "nodes", 3).deepCopy();
        extraNode.put("code", "9999900000").put("rowNumber", 100);
        ((ArrayNode) fixture.path("nodes")).add(extraNode);
        var extraNav = item(fixture, "navigation", 3).deepCopy();
        extraNav.put("code", "9999900000");
        ((ArrayNode) fixture.path("navigation")).add(extraNav);
        assertThat(validator.validate(mapper.treeToValue(fixture, NationalReferenceSnapshot.class)).approvable()).isFalse();
    }

    @Test
    void publishesWithRevisionChecksAndRollsBackToEarlierApprovedRelease() {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        assertThat(publisher.publish("national-test-v1", 0, "reviewer", "first", false)).isEqualTo(1);
        fixture.put("version", "national-test-v2");
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v2", "reviewer");
        assertThatThrownBy(() -> publisher.publish("national-test-v2", 0, "reviewer", "stale", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish("national-test-v2", 1, "reviewer", "never published", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher.publish("national-test-v2", 1, "reviewer", "next", false)).isEqualTo(2);
        assertThat(publisher.publish("national-test-v1", 2, "reviewer", "restore", true)).isEqualTo(3);
        assertThat(publisher.publish("national-test-v1", 3, "reviewer", "same", false)).isEqualTo(3);
        assertThat(count("region_publication_event")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT action FROM region_publication_event ORDER BY to_revision", String.class))
                .containsExactly("PUBLISH", "PUBLISH", "ROLLBACK");
    }

    @Test
    void eventFailureRollsBackPublicationPointer() {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        // 이벤트 revision 충돌을 주입해 포인터도 함께 롤백되는지 확인한다.
        jdbc.update("""
                INSERT INTO region_publication_event
                    (publication_key, to_release_id, from_revision, to_revision, action, actor, reason, occurred_at)
                SELECT 'NATIONWIDE_SELECTION', release_id, 0, 1, 'PUBLISH', 'fixture', 'collision', UTC_TIMESTAMP(6)
                FROM region_release WHERE release_version = 'national-test-v1'
                """);
        assertThatThrownBy(() -> publisher.publish("national-test-v1", 0, "reviewer", "first", false)).isInstanceOf(DataAccessException.class);
        assertThat(count("region_publication")).isZero();
        assertThat(count("region_publication_event")).isEqualTo(1);
    }

    @Test
    void concurrentPublicationAcceptsOnlyOneExpectedRevision() throws Exception {
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v1", "reviewer");
        fixture.put("version", "national-test-v2");
        importer.importSnapshot(loaded(fixture));
        publisher.approve("national-test-v2", "reviewer");
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String, Callable<Boolean>> publish = version -> () -> {
                try {
                    publisher.publish(version, 0, "reviewer", "concurrent", false);
                    return true;
                } catch (IllegalArgumentException | DataAccessException conflict) {
                    return false;
                }
            };
            var results = executor.invokeAll(List.of(publish.apply("national-test-v1"), publish.apply("national-test-v2")));
            assertThat(results.stream().filter(future -> {
                try { return future.get(); } catch (Exception e) { throw new IllegalStateException(e); }
            }).count()).isEqualTo(1);
        }
        assertThat(count("region_publication_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM region_publication", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameVersionImportDoesNotDuplicateReferenceRows() throws Exception {
        var input = loaded(fixture);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> importer.importSnapshot(input).inserted();
            var results = executor.invokeAll(List.of(task, task));
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
        }
        assertThat(count("region_release")).isEqualTo(1);
        assertThat(count("official_region_node")).isEqualTo(3);
        assertThat(count("national_industry_code")).isEqualTo(3);
    }

    @Test
    void rejectsChecksumMismatchBeforeImport() throws Exception {
        Path file = temporary.resolve("snapshot.json");
        Files.write(file, mapper.writeValueAsBytes(fixture));
        assertThatThrownBy(() -> loader.load(file, "0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("region_release")).isZero();
    }

    @TestFactory
    Stream<DynamicTest> invalidReferenceContracts() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(invalid("unknown source", tree -> item(tree, "sources", 0).put("system", "OTHER")));
        tests.add(invalid("wrong source length", tree -> item(tree, "sources", 0).put("code", "11110515")));
        tests.add(invalid("missing node", tree -> item(tree, "mappings", 0).put("targetCode", "9999999999")));
        tests.add(invalid("wrong source version", tree -> item(tree, "mappings", 0).put("sourceVersion", "absent")));
        tests.add(invalid("invalid date", tree -> item(tree, "nodes", 2).put("createdOn", "2027-01-01")));
        tests.add(invalid("unreviewed selection", tree -> item(tree, "navigation", 2).put("reviewStatus", "PENDING")));
        tests.add(invalid("parent cycle", tree -> item(tree, "navigation", 1).put("parent", "1111051500")));
        tests.add(invalid("wrong selection role", tree -> item(tree, "navigation", 2).put("selectionParent", "1100000000")));
        tests.add(invalid("wrong industry parent", tree -> item(tree, "industries", 2).put("parentCode", "I")));
        tests.add(invalid("supported large industry", tree -> item(tree, "industries", 0).put("supported", true)));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> actualSourceImport() {
        String path = System.getenv("PICKPLACE_REFERENCE_SNAPSHOT");
        if (path == null) return Stream.empty();
        return Stream.of(DynamicTest.dynamicTest("imports actual national reference snapshot into isolated MySQL", () -> {
            var loaded = loader.load(Path.of(path), System.getenv("PICKPLACE_REFERENCE_SHA256"));
            var result = importer.importSnapshot(loaded);
            assertThat(result.inserted()).isTrue();
            assertThat(count("official_region_node")).isEqualTo(9613);
            assertThat(count("region_navigation")).isEqualTo(3926);
            assertThat(count("source_region_reference")).isEqualTo(3619 + 3558);
            assertThat(count("region_stat_mapping")).isEqualTo(3619 + 3558);
            assertThat(count("national_industry_code")).isEqualTo(10 + 75 + 247);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM official_region_node WHERE date_quality_status = 'INVALID'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE selectable", Integer.class)).isZero();
            assertThat(importer.importSnapshot(loaded).inserted()).isFalse();
            assertThatThrownBy(() -> publisher.approve(loaded.snapshot().version(), "test")).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("region_publication")).isZero();
        }));
    }

    @TestFactory
    Stream<DynamicTest> reviewedSourceImport() {
        String path = System.getenv("PICKPLACE_REVIEWED_REFERENCE_SNAPSHOT");
        if (path == null) return Stream.empty();
        return Stream.of(DynamicTest.dynamicTest("imports reviewed identities and quarantines unresolved navigation and temporal edges", () -> {
            var loaded = loader.load(Path.of(path), System.getenv("PICKPLACE_REVIEWED_REFERENCE_SHA256"));
            var result = importer.importSnapshot(loaded);
            assertThat(result.inserted()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE review_status = 'VERIFIED'", Integer.class)).isEqualTo(3850);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE review_status = 'BLOCKED' AND selectable = FALSE", Integer.class)).isEqualTo(76);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE selectable", Integer.class)).isEqualTo(3837);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_stat_mapping WHERE review_status = 'VERIFIED'", Integer.class)).isEqualTo(7177);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM region_stat_mapping
                    WHERE JSON_UNQUOTE(JSON_EXTRACT(evidence, '$.temporalStatus')) = 'CHANGE_DATE_HOLD'
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT region_code FROM region_stat_mapping
                    WHERE source_system = 'SEMAS_STORE' AND source_region_code = '27710380'
                    """, String.class)).isEqualTo("2771038000");
            assertThat(importer.importSnapshot(loaded).inserted()).isFalse();
            assertThatThrownBy(() -> publisher.approve(loaded.snapshot().version(), "test")).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("region_publication")).isZero();
        }));
    }

    @TestFactory
    Stream<DynamicTest> releaseSourcePublication() {
        String path = System.getenv("PICKPLACE_RELEASE_REFERENCE_SNAPSHOT");
        if (path == null) return Stream.empty();
        return Stream.of(DynamicTest.dynamicTest("approves and publishes actual release with confirmed exclusions and supported industries", () -> {
            var input = loader.load(Path.of(path), System.getenv("PICKPLACE_RELEASE_REFERENCE_SHA256"));
            importer.importSnapshot(input);
            publisher.approve(input.snapshot().version(), "test");
            assertThat(publisher.publish(input.snapshot().version(), 0, "test", "initial release test", false)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM region_publication p JOIN region_navigation n ON n.release_id=p.release_id
                    WHERE n.selectable
                    """, Integer.class)).isEqualTo(3837);
            assertThat(jdbc.queryForList("SELECT industry_code FROM national_industry_code WHERE is_supported ORDER BY display_order", String.class))
                    .containsExactly("I21201", "I21007", "I21001");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE review_status='BLOCKED' AND selectable", Integer.class)).isZero();
            assertThat(count("region_publication_event")).isEqualTo(1);
            assertThat(importer.importSnapshot(input).inserted()).isFalse();
        }));
    }

    private void addConfirmedExclusions(ObjectNode tree) throws Exception {
        try (var stream = new ClassPathResource("reference-data/national-initial-selection-policy.json").getInputStream()) {
            var policy = mapper.readTree(stream);
            String version = policy.path("version").asText();
            tree.put("policyVersion", version);
            item(tree, "artifacts", 0).put("sha256", policy.path("currentArtifactSha256").asText());
            int rowNumber = 5;
            for (var code : policy.path("excludedCodes")) {
                var node = item(tree, "nodes", 1).deepCopy();
                node.put("code", code.asText()).put("rowNumber", rowNumber++).putNull("district");
                ((ArrayNode) tree.path("nodes")).add(node);
                var navigation = item(tree, "navigation", 1).deepCopy();
                navigation.put("code", code.asText()).put("kind", "BRANCH").put("role", "NONE")
                        .putNull("selectionParent").put("selectable", false).put("reviewStatus", "BLOCKED")
                        .put("evidence", "{\"policy\":\"" + version + "\",\"reason\":\"INITIAL_SELECTION_EXCLUDED\"}");
                ((ArrayNode) tree.path("navigation")).add(navigation);
            }
        }
    }

    private DynamicTest invalid(String name, java.util.function.Consumer<ObjectNode> change) {
        return DynamicTest.dynamicTest(name, () -> {
            ObjectNode changed = fixture.deepCopy();
            change.accept(changed);
            assertThatThrownBy(() -> validator.validate(mapper.treeToValue(changed, NationalReferenceSnapshot.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    private NationalReferenceLoader.Loaded loaded(ObjectNode tree) {
        byte[] bytes = mapper.writeValueAsBytes(tree);
        return new NationalReferenceLoader.Loaded(mapper.readValue(bytes, NationalReferenceSnapshot.class), NationalReferenceLoader.sha256(bytes));
    }
    private ObjectNode item(ObjectNode tree, String collection, int index) { return (ObjectNode) tree.path(collection).get(index); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
