package com.pickplace.backend;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class SejongAutoSelectReleaseTests {
    private static final String OLD = "national-202609-release-v1";
    private static final String NEW = "national-202609-sejong-hint-v1";
    private static final String SEJONG = "3611000000";

    @Autowired private NationalReferenceLoader loader;
    @Autowired private NationalReferenceImporter importer;
    @Autowired private NationalReferencePublisher publisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;

    @Test
    void publishesOnlySejongRecommendationAsNewReleaseWithoutChangingPreviousData() throws Exception {
        var oldInput = load(OLD);
        var newInput = load(NEW);
        var before = mapper.readTree(Files.readAllBytes(path(OLD).resolve("snapshot.json")));
        var after = (ObjectNode) mapper.readTree(Files.readAllBytes(path(NEW).resolve("snapshot.json")));
        var oldGroup = group(before);
        var newGroup = group(after);
        assertThat(newGroup.path("autoSelect").asBoolean()).isTrue();
        // 허용한 세 필드를 원래 값으로 되돌리면 원본 전체와 같아야 한다.
        after.put("version", OLD);
        newGroup.put("autoSelect", false);
        newGroup.put("evidence", oldGroup.path("evidence").asText());
        assertThat(after).isEqualTo(before);

        var original = importer.importSnapshot(oldInput);
        publisher.approve(OLD, "test");
        assertThat(publisher.publish(OLD, 0, "test", "base", false)).isEqualTo(1);
        long sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM source_region_reference", Long.class);
        var updated = importer.importSnapshot(newInput);
        assertThat(updated.classificationId()).isEqualTo(original.classificationId());
        publisher.approve(NEW, "test");
        assertThat(publisher.publish(NEW, 1, "test", "Sejong recommendation", false)).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT auto_select_group FROM region_navigation WHERE release_id=? AND region_code=?",
                Boolean.class, original.releaseId(), SEJONG)).isFalse();
        assertThat(jdbc.queryForObject("SELECT auto_select_group FROM region_navigation WHERE release_id=? AND region_code=?",
                Boolean.class, updated.releaseId(), SEJONG)).isTrue();
        assertThat(jdbc.queryForList("SELECT region_code FROM region_navigation WHERE release_id=? AND auto_select_group=TRUE",
                String.class, updated.releaseId())).containsExactly(SEJONG);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE release_id=? AND selectable=TRUE",
                Long.class, updated.releaseId())).isEqualTo(3837);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_navigation WHERE release_id=? AND review_status='BLOCKED' AND selectable=FALSE",
                Long.class, updated.releaseId())).isEqualTo(76);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_region_reference", Long.class)).isEqualTo(sourceCount);
        assertThat(jdbc.queryForList("SELECT industry_code FROM national_industry_code WHERE classification_id=? AND is_supported=TRUE ORDER BY display_order",
                String.class, updated.classificationId())).containsExactly("I21201", "I21007", "I21001");
        assertThat(jdbc.queryForObject("SELECT revision FROM region_publication WHERE publication_key='NATIONWIDE_SELECTION'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_publication_event", Long.class)).isEqualTo(2);
        assertThat(importer.importSnapshot(newInput).inserted()).isFalse();
    }

    private NationalReferenceLoader.Loaded load(String version) throws Exception {
        var manifest = mapper.readTree(Files.readAllBytes(path(version).resolve("manifest.json")));
        return loader.load(path(version).resolve("snapshot.json"), manifest.path("sha256").asText());
    }

    private Path path(String version) {
        return Path.of("data/reference", version);
    }

    private ObjectNode group(JsonNode snapshot) {
        for (var node : snapshot.path("navigation")) {
            if (node.path("code").asText().equals(SEJONG)) return (ObjectNode) node;
        }
        throw new AssertionError("Missing Sejong group");
    }
}
