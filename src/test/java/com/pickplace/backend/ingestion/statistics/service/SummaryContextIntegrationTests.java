package com.pickplace.backend.ingestion.statistics.service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.pickplace.backend.MySqlTestConfiguration;
import com.pickplace.backend.common.error.*;
import com.pickplace.backend.businesstype.BusinessTypeQueryRepository;
import com.pickplace.backend.reference.nationwide.*;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.summary.*;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsTestFixture.*;
import static org.assertj.core.api.Assertions.*;

/** T14의 실제 작은 원본을 적재해 T15가 게시본·출처를 올바르게 따라가는지 검증한다. */
@SpringBootTest(properties="pickplace.test.scope=t15")
@ActiveProfiles("test") @Import(MySqlTestConfiguration.class)
class SummaryContextIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired SummaryContextService service;
    @Autowired SummaryContextRepository repository;
    @Autowired BusinessTypeQueryRepository classifications;
    @Autowired NationalReferenceImporter references;
    @Autowired NationalReferenceLoader loader;
    @Autowired NationalReferencePublisher regionPublisher;
    @Autowired NationalStatisticsImporter importer;
    @Autowired NationalSummaryPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temp;
    int sequence;

    @BeforeEach @AfterEach void cleanup() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pickplace_test");
        for (String table:List.of("national_summary_publication_event","national_summary_publication","national_summary_release",
                "national_quality_issue","store_industry_count","store_region_coverage","store_snapshot_entry","resident_population_month",
                "national_source_artifact","national_import_batch","region_publication_event","region_publication","region_stat_mapping",
                "region_navigation","official_region_node","region_source_artifact","region_release","source_region_reference","service_region")) {
            if(table.equals("national_source_artifact")) jdbc.update("DELETE FROM national_source_artifact WHERE artifact_kind='ZIP_MEMBER'");
            jdbc.update("DELETE FROM "+table);
        }
        for(String level:List.of("SMALL","MIDDLE","LARGE")) jdbc.update("DELETE FROM national_industry_code WHERE industry_level=?",level);
        jdbc.update("DELETE FROM national_industry_release");
    }
    @Test void resolvesPublishedDefaultAndPreservesOriginalCollectionTimes() throws Exception {
        var b=bundle("202609"); select(b,0); publish(b,0,null);
        var c=context();
        assertThat(c.summary().id()).isEqualTo(b.id()); assertThat(c.summary().referenceMonth()).isEqualTo("202609");
        assertThat(c.population().collectedAt().toString()).isEqualTo("2026-11-08T12:25:07Z");
        assertThat(c.population().snapshotLabel()).isNull(); assertThat(c.stores().snapshotLabel()).isNotBlank();
        assertThat(c.selection().businessTypeName()).isEqualTo("카페"); assertThat(c.classificationMatches()).isTrue();
        assertThat(c.summary().publishedAt()).isAfter(c.population().collectedAt());
    }
    @Test void referenceUnpublishedAndSummaryUnpublishedHaveDifferentErrors() throws Exception {
        expect(ErrorCode.REFERENCE_DATA_NOT_READY);
        var b=bundle("202609"); select(b,0); expect(ErrorCode.SUMMARY_DATA_NOT_READY);
    }
    @Test void unregisteredUnsupportedAndWrongRegionRemainDistinct() throws Exception {
        var b=bundle("202609");select(b,0);
        assertCode(() -> service.withContext(REGION,"I99999", c -> c),ErrorCode.BUSINESS_TYPE_NOT_FOUND);
        assertCode(() -> service.withContext("1100000000","I21201", c -> c),ErrorCode.REGION_NOT_FOUND);
        jdbc.update("UPDATE national_industry_code SET is_supported=FALSE,support_release_status=NULL WHERE industry_code='I21201'");
        expect(ErrorCode.BUSINESS_TYPE_NOT_SUPPORTED);
    }
    @Test void brokenClassificationIsInternalErrorNotPreparation() throws Exception {
        var b=bundle("202609");select(b,0);
        jdbc.update("UPDATE region_release SET validation_report=JSON_SET(validation_report,'$.classificationId','1') WHERE release_version=?",b.regionVersion());
        expect(ErrorCode.INTERNAL_ERROR);
    }
    @Test void brokenPublishedSourceCannotBecomeEmptyData() throws Exception {
        var b=bundle("202609");select(b,0);publish(b,0,null);
        jdbc.update("UPDATE national_source_artifact SET sha256=REPEAT('a',64) WHERE batch_id=?",b.population());
        expect(ErrorCode.INTERNAL_ERROR);
    }
    @Test void laterMonthlyPublicationDoesNotReplaceDefault() throws Exception {
        var old=bundle("202609");select(old,0);publish(old,0,null);
        var next=bundle("202610");
        publisher.publishMonth(next.version(),new Expected(0,null),null,"test","month only",false);
        assertThat(context().summary().id()).isEqualTo(old.id());
    }
    @Test void monthlyPublicationAloneDoesNotCreateAnImplicitDefault() throws Exception {
        var b=bundle("202609");select(b,0);
        publisher.publishMonth(b.version(),new Expected(0,null),null,"test","month only",false);
        expect(ErrorCode.SUMMARY_DATA_NOT_READY);
    }
    @Test void doesNotInheritWeakerIsolationFromCaller() throws Exception {
        var b=bundle("202609");select(b,0);publish(b,0,null);
        var outer=new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(ignored -> {
            service.withContext(REGION,"I21201", c -> {
                assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                return null;
            });
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
        });
    }
    @Test void newerSelectionDoesNotSilentlyJoinDifferentClassification() throws Exception {
        var old=bundle("202609");select(old,0);publish(old,0,null);
        var next=bundle("202610");select(next,1);
        var c=context();assertThat(c.classificationMatches()).isFalse();assertThat(c.summary().id()).isEqualTo(old.id());
    }
    @Test void concurrentPublicationCannotMixSnapshotsWithinCallback() throws Exception {
        var old=bundle("202609");select(old,0);publish(old,0,null);
        var next=bundle("202610");publisher.publishMonth(next.version(),new Expected(0,null),null,"test","next month",false);
        try(var executor=Executors.newSingleThreadExecutor()) {
            service.withContext(REGION,"I21201", c -> {
                try { executor.submit(() -> {
                    regionPublisher.publish(next.regionVersion(),1,"test","new selection",false);
                    publisher.setDefault(next.version(),new Expected(1,old.id()),"test","new default");
                }).get(10,TimeUnit.SECONDS); } catch(Exception failure) {throw new AssertionError(failure);}
                // Callback 안의 후속 SQL도 최초 선택·DEFAULT와 같은 스냅샷을 읽어야 한다.
                assertThat(classifications.findPublishedClassification().orElseThrow().releaseId()).isEqualTo(c.selection().releaseId());
                assertThat(repository.defaultSummary().orElseThrow().id()).isEqualTo(old.id());
                assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
                return null;
            });
        }
        assertThat(context().summary().id()).isEqualTo(next.id());assertThat(context().classificationMatches()).isTrue();
    }
    private Bundle bundle(String month)throws Exception {
        var f=prepare(temp.resolve("fixture-"+sequence++),month,List.of(store("S1")));
        references.importSnapshot(loader.load(f.reference(),NationalRawValidator.sha256(f.reference())));
        regionPublisher.approve(f.snapshot().version(),"test");
        long pop=importer.importData(load(f,"POPULATION_MONTH")).batchId();
        long shops=importer.importData(load(f,"STORE_SNAPSHOT")).batchId();
        String version="test-summary-"+sequence;long id=publisher.prepare(version,pop,shops);publisher.approve(version,"test");
        return new Bundle(id,version,f.snapshot().version(),pop);
    }
    private void select(Bundle b,long revision){regionPublisher.publish(b.regionVersion(),revision,"test","selection",false);}
    private void publish(Bundle b,long revision,Long previous){
        publisher.publishMonth(b.version(),new Expected(0,null),null,"test","month",false);
        publisher.setDefault(b.version(),new Expected(revision,previous),"test","default");
    }
    private SummaryReadContext context(){return service.withContext(REGION,"I21201",c -> c);}
    private void expect(ErrorCode code){assertCode(this::context,code);}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,ErrorCode code){
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.errorCode()).isEqualTo(code));
    }
    private record Bundle(long id,String version,String regionVersion,long population){}
}
