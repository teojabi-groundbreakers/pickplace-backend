package com.pickplace.backend.ingestion.statistics.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.reference.nationwide.NationalReferenceImporter;
import com.pickplace.backend.reference.nationwide.NationalReferenceLoader;
import com.pickplace.backend.reference.nationwide.NationalReferencePublisher;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"pickplace.test.scope=t14", "spring.datasource.hikari.data-source-properties.rewriteBatchedStatements=true"})
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class NationalStatisticsImportTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired NationalStatisticsImporter importer;
    @Autowired NationalSummaryPublisher publisher;
    @Autowired StatisticsBatchService batches;
    @Autowired NationalReferenceImporter referenceImporter;
    @Autowired NationalReferencePublisher referencePublisher;
    @Autowired NationalReferenceLoader referenceLoader;
    @MockitoSpyBean StatisticsFactWriter writer;
    @TempDir Path temp;
    int nextFixture;

    @BeforeEach @AfterEach void cleanup() {
        reset(writer);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
        for (String table : List.of("national_summary_publication_event", "national_summary_publication", "national_summary_release",
                "national_quality_issue", "store_industry_count", "store_region_coverage", "store_snapshot_entry", "resident_population_month",
                "national_source_artifact", "national_import_batch", "region_publication_event", "region_publication", "region_stat_mapping",
                "region_navigation", "official_region_node", "region_source_artifact", "region_release", "source_region_reference", "service_region")) {
            // ZIP 멤버가 같은 테이블의 ZIP 부모를 참조하므로 자식부터 지운다.
            if (table.equals("national_source_artifact")) jdbc.update("DELETE FROM national_source_artifact WHERE artifact_kind='ZIP_MEMBER'");
            jdbc.update("DELETE FROM " + table);
        }
        for (String level : List.of("SMALL", "MIDDLE", "LARGE")) jdbc.update("DELETE FROM national_industry_code WHERE industry_level=?", level);
        jdbc.update("DELETE FROM national_industry_release");
    }

    @Test void importsFactsArtifactsRealZeroAndReusesCompletedBatch() throws Exception {
        var f = fixture("202609", List.of(store("STORE1")));
        var pop = importer.importData(load(f, "POPULATION_MONTH")); var input = load(f, "STORE_SNAPSHOT");
        var shops = importer.importData(input);
        assertThat(value("SELECT total_population FROM resident_population_month WHERE batch_id=?", pop.batchId())).isEqualTo(100);
        assertThat(value("SELECT COUNT(*) FROM national_source_artifact WHERE batch_id=?", shops.batchId())).isEqualTo(2);
        assertThat(value("SELECT accepted_store_count FROM store_industry_count WHERE batch_id=? AND industry_code='I21001'", shops.batchId())).isZero();
        assertThat(value("SELECT COUNT(*) FROM store_region_coverage WHERE batch_id=?", shops.batchId())).isEqualTo(1);
        assertThat(importer.importData(input).status()).isEqualTo("REUSED");
        assertThat(value("SELECT COUNT(*) FROM national_import_batch", new Object[0])).isEqualTo(2);
        assertThat(publisher.pointers()).isEmpty();
    }

    @Test void keeps97AcceptedAndThreeCountAffectingRowsAsHeldAggregate() throws Exception {
        var rows = new ArrayList<List<String>>();
        for (int i = 0; i < 97; i++) rows.add(store("VALID" + i));
        for (int i = 0; i < 3; i++) { var c = store("CONFLICT"); c.set(31, "주소 " + i); rows.add(c); }
        var f = fixture("202609", rows); long id = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        var count = jdbc.queryForMap("SELECT * FROM store_industry_count WHERE batch_id=? AND industry_code='I21201'", id);
        assertThat(((Number) count.get("accepted_store_count")).longValue()).isEqualTo(97);
        assertThat(count.get("count_quality_status")).isEqualTo("HOLD");
        assertThat(((Number) count.get("count_affecting_issue_count")).longValue()).isEqualTo(3);
        assertThat(value("SELECT COUNT(*) FROM store_snapshot_entry WHERE batch_id=?", id)).isEqualTo(97);
        assertThat(value("SELECT COUNT(*) FROM national_quality_issue WHERE batch_id=? AND reason_code='DUPLICATE_ID_CONFLICT'", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count_quality_status FROM store_industry_count WHERE batch_id=? AND industry_code='I21001'", String.class, id)).isEqualTo("VALID");
    }

    @Test void neutralCoordinateWarningKeepsTheStoreCount() throws Exception {
        var c = store("STORE1"); c.set(37, "NaN");
        var f = fixture("202609", List.of(c)); long id = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        assertThat(value("SELECT accepted_store_count FROM store_industry_count WHERE batch_id=? AND industry_code='I21201'", id)).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM national_quality_issue WHERE batch_id=? AND affects_count=FALSE", id)).isEqualTo(1);
    }

    @Test void unknownIndustryHoldsAllIndustriesOnlyInThatRegion() throws Exception {
        var c = store("STORE1"); c.set(7, "UNKNOWN");
        var f = fixture("202609", List.of(c)); long id = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        assertThat(value("SELECT COUNT(*) FROM store_industry_count WHERE batch_id=? AND count_quality_status='HOLD'", id)).isEqualTo(2);
        assertThat(value("SELECT COUNT(*) FROM store_industry_count WHERE batch_id=? AND count_quality_status='VALID'", id)).isZero();
    }

    @Test void globalIssueBlocksSummaryWithoutChangingPreviouslyPublishedPointers() throws Exception {
        var first = bundle("202609"); publishInitial(first);
        var c = store("UNKNOWN"); c.set(15, "99999999");
        var f = fixture("202609", List.of(c)); long pop = importer.importData(load(f, "POPULATION_MONTH")).batchId();
        long shops = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        assertThatThrownBy(() -> publisher.prepare("blocked", pop, shops)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GLOBAL");
        assertThat(pointer("DEFAULT")).isEqualTo(first.summaryId());
    }

    @Test void identicalCopiesAreNotInsertedTwice() throws Exception {
        var f = fixture("202609", List.of(store("SAME"), store("SAME"))); long id = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        assertThat(value("SELECT duplicate_row_count FROM national_import_batch WHERE batch_id=?", id)).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM store_snapshot_entry WHERE batch_id=?", id)).isEqualTo(1);
    }

    @Test void failedChunksRemainHistoricalAndRetryCreatesANewAttempt() throws Exception {
        var rows = new ArrayList<List<String>>(); for (int i = 0; i < 550; i++) rows.add(store("STORE" + i));
        var f = fixture("202609", rows); var input = load(f, "STORE_SNAPSHOT"); var calls = new AtomicInteger();
        doAnswer(inv -> { if (calls.incrementAndGet() == 2) throw new IllegalStateException("injected failure"); return inv.callRealMethod(); })
                .when(writer).chunk(any(), any(), anyList(), any(), any());
        assertThatThrownBy(() -> importer.importData(input)).isInstanceOf(IllegalStateException.class);
        long failed = value("SELECT batch_id FROM national_import_batch WHERE status='FAILED'", new Object[0]);
        assertThat(value("SELECT COUNT(*) FROM store_snapshot_entry WHERE batch_id=?", failed)).isEqualTo(500);
        reset(writer);
        var success = importer.importData(input);
        assertThat(success.batchId()).isNotEqualTo(failed);
        assertThat(value("SELECT attempt_no FROM national_import_batch WHERE batch_id=?", success.batchId())).isEqualTo(2);
        assertThat(value("SELECT COUNT(*) FROM store_snapshot_entry WHERE batch_id=?", success.batchId())).isEqualTo(550);
    }

    @Test void activeBatchNeedsExplicitRecoveryAndCompletedBatchCannotBeRecovered() throws Exception {
        var f = fixture("202609", List.of(store("STORE1"))); var input = load(f, "STORE_SNAPSHOT");
        var running = batches.start(input);
        assertThatThrownBy(() -> importer.importData(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("실행 중");
        batches.recover(running.id(), "RUNNING", "tester", "worker stopped");
        var success = importer.importData(input);
        assertThatThrownBy(() -> batches.recover(success.batchId(), "RUNNING", "tester", "invalid recovery")).isInstanceOf(IllegalArgumentException.class);
        assertThat(importer.importData(input).status()).isEqualTo("REUSED");
    }

    @Test void changedInputIsRejectedBeforeCreatingBatch() throws Exception {
        var f = fixture("202609", List.of(store("STORE1"))); var input = load(f, "STORE_SNAPSHOT");
        Files.writeString(input.rows(), "changed");
        assertThatThrownBy(() -> importer.importData(input)).isInstanceOf(java.io.IOException.class);
        assertThat(value("SELECT COUNT(*) FROM national_import_batch", new Object[0])).isZero();
    }

    @Test void pastMonthCorrectionDoesNotTouchDefaultEvenWhenItIsTheSameMonth() throws Exception {
        var first = bundle("202609"); publishInitial(first);
        var correction = bundle("202609");
        publisher.publishMonth(correction.version(), new Expected(1, first.summaryId()), null, "tester", "correction", false);
        assertThat(pointer("MONTH:202609")).isEqualTo(correction.summaryId());
        assertThat(pointer("DEFAULT")).isEqualTo(first.summaryId());
        assertThat(revision("DEFAULT")).isEqualTo(1);
    }

    @Test void pastMonthCorrectionLeavesDifferentDefaultMonthUntouched() throws Exception {
        var old = bundle("202609"); publishInitial(old);
        var newer = bundle("202610"); publisher.publishMonth(newer.version(), new Expected(0, null), null, "tester", "new month", false);
        publisher.setDefault(newer.version(), new Expected(1, old.summaryId()), "tester", "select October");
        var correction = bundle("202609"); publisher.publishMonth(correction.version(), new Expected(1, old.summaryId()), null, "tester", "September fix", false);
        assertThat(pointer("DEFAULT")).isEqualTo(newer.summaryId());
        assertThat(revision("DEFAULT")).isEqualTo(2);
    }

    @Test void explicitSyncUpdatesBothPointersAndBothEventsAtomically() throws Exception {
        var first = bundle("202609"); publishInitial(first); var second = bundle("202609");
        publisher.publishMonth(second.version(), new Expected(1, first.summaryId()), new Expected(1, first.summaryId()), "tester", "sync correction", false);
        assertThat(pointer("DEFAULT")).isEqualTo(second.summaryId()); assertThat(pointer("MONTH:202609")).isEqualTo(second.summaryId());
        assertThat(value("SELECT COUNT(*) FROM national_summary_publication_event WHERE default_synchronized=TRUE", new Object[0])).isEqualTo(2);
    }

    @Test void staleDefaultExpectationRollsBackTheWholeCorrection() throws Exception {
        var first = bundle("202609"); publishInitial(first); var second = bundle("202609");
        assertThatThrownBy(() -> publisher.publishMonth(second.version(), new Expected(1, first.summaryId()), new Expected(0, null), "tester", "stale", false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pointer("MONTH:202609")).isEqualTo(first.summaryId()); assertThat(pointer("DEFAULT")).isEqualTo(first.summaryId());
        assertThat(value("SELECT COUNT(*) FROM national_summary_publication_event", new Object[0])).isEqualTo(2);
    }

    @Test void failureRecordingDefaultEventRollsBackEarlierMonthAndDefaultWrites() throws Exception {
        var first = bundle("202609"); publishInitial(first); var second = bundle("202609");
        // 다음 DEFAULT revision을 미리 차지해 두 번째 이력 INSERT를 실패시킨다. 포인터 자체는 바꾸지 않는다.
        jdbc.update("""
                INSERT INTO national_summary_publication_event (publication_key, from_summary_release_id, from_reference_month,
                    to_summary_release_id, to_reference_month, from_revision, to_revision, action, actor, reason, occurred_at)
                VALUES ('DEFAULT', ?, '202609', ?, '202609', 1, 2, 'SET_DEFAULT', 'test', 'fault injection', UTC_TIMESTAMP(6))
                """, first.summaryId(), second.summaryId());
        assertThatThrownBy(() -> publisher.publishMonth(second.version(), new Expected(1, first.summaryId()), new Expected(1, first.summaryId()), "tester", "sync", false))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(pointer("MONTH:202609")).isEqualTo(first.summaryId()); assertThat(pointer("DEFAULT")).isEqualTo(first.summaryId());
        assertThat(value("SELECT COUNT(*) FROM national_summary_publication_event WHERE publication_key='MONTH:202609'", new Object[0])).isEqualTo(1);
    }

    @Test void rollbackAppendsHistoryAndLeavesDefaultUnlessExplicitlySynchronized() throws Exception {
        var first = bundle("202609"); publishInitial(first); var second = bundle("202609");
        publisher.publishMonth(second.version(), new Expected(1, first.summaryId()), new Expected(1, first.summaryId()), "tester", "fix", false);
        publisher.publishMonth(first.version(), new Expected(2, second.summaryId()), null, "tester", "rollback", true);
        assertThat(pointer("MONTH:202609")).isEqualTo(first.summaryId()); assertThat(pointer("DEFAULT")).isEqualTo(second.summaryId());
        assertThat(revision("MONTH:202609")).isEqualTo(3);
    }

    @Test void cannotSelectUnpublishedSummaryAsDefaultOrChangeVersionContents() throws Exception {
        var first = bundle("202609");
        assertThatThrownBy(() -> publisher.setDefault(first.version(), new Expected(0, null), "tester", "too early")).isInstanceOf(IllegalArgumentException.class);
        var second = bundle("202609");
        assertThatThrownBy(() -> publisher.prepare(first.version(), second.populationId(), second.storeId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void firstPublicationRaceAllowsOnlyOneRevisionAndOneEvent() throws Exception {
        var first = bundle("202609"); var second = bundle("202609");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var futures = executor.invokeAll(List.of(publishRace(first, barrier), publishRace(second, barrier)));
            int successes = 0; for (var future : futures) if (future.get()) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(revision("MONTH:202609")).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM national_summary_publication_event", new Object[0])).isEqualTo(1);
    }

    @Test void concurrentImportsNeverCreateTwoActiveAttempts() throws Exception {
        var f = fixture("202609", List.of(store("STORE1"))); var input = load(f, "STORE_SNAPSHOT");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            Callable<Boolean> action = () -> { barrier.await(); try { importer.importData(input); return true; }
                catch (IllegalArgumentException active) { if (!active.getMessage().contains("실행 중")) throw active; return false; } };
            var results = executor.invokeAll(List.of(action, action));
            assertThat(results.get(0).get() || results.get(1).get()).isTrue();
        }
        assertThat(value("SELECT COUNT(*) FROM national_import_batch WHERE status='SUCCEEDED'", new Object[0])).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM national_import_batch", new Object[0])).isEqualTo(1);
    }

    @Test void selectingTheSamePointersDoesNotAppendEvents() throws Exception {
        var first = bundle("202609"); publishInitial(first);
        assertThat(publisher.publishMonth(first.version(), new Expected(1, first.summaryId()), null, "tester", "same", false)).isEqualTo(1);
        assertThat(publisher.setDefault(first.version(), new Expected(1, first.summaryId()), "tester", "same")).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM national_summary_publication_event", new Object[0])).isEqualTo(2);
    }

    private Callable<Boolean> publishRace(Bundle b, java.util.concurrent.CyclicBarrier barrier) {
        return () -> { barrier.await(); try { publisher.publishMonth(b.version(), new Expected(0, null), null, "tester", "race", false); return true; }
            catch (IllegalArgumentException expected) { return false; } };
    }
    private StatisticsTestFixture.Prepared fixture(String month, List<List<String>> rows) throws Exception {
        var f = StatisticsTestFixture.prepare(temp.resolve("fixture-" + nextFixture++), month, rows);
        referenceImporter.importSnapshot(referenceLoader.load(f.reference(), NationalRawValidator.sha256(f.reference())));
        referencePublisher.approve(f.snapshot().version(), "tester");
        return f;
    }
    private Bundle bundle(String month) throws Exception {
        var f = fixture(month, List.of(store("STORE1")));
        long pop = importer.importData(load(f, "POPULATION_MONTH")).batchId(), stores = importer.importData(load(f, "STORE_SNAPSHOT")).batchId();
        String version = "summary-" + nextFixture; long id = publisher.prepare(version, pop, stores); publisher.approve(version, "tester");
        return new Bundle(version, id, pop, stores);
    }
    private void publishInitial(Bundle b) {
        publisher.publishMonth(b.version(), new Expected(0, null), null, "tester", "initial", false);
        publisher.setDefault(b.version(), new Expected(0, null), "tester", "initial default");
    }
    private long pointer(String key) { return value("SELECT summary_release_id FROM national_summary_publication WHERE publication_key=?", key); }
    private long revision(String key) { return value("SELECT revision FROM national_summary_publication WHERE publication_key=?", key); }
    private long value(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private record Bundle(String version, long summaryId, long populationId, long storeId) {}
}
