package com.pickplace.backend.summary;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.pickplace.backend.businesstype.BusinessTypeQueryRepository;
import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ErrorDetail;
import com.pickplace.backend.summary.SummaryContextRepository.BatchSource;
import com.pickplace.backend.summary.SummaryReadContext.*;
import tools.jackson.databind.json.JsonMapper;

/** T17은 withContext 안에서 사실/집계를 읽는다. 포인터 고정 후 트랜잭션 밖에서 재조회하면 안 된다. */
@Service
public class SummaryContextService {
    private final BusinessTypeQueryRepository classifications;
    private final SummaryContextRepository repository;
    private final JsonMapper mapper;
    private final TransactionTemplate readSnapshot;

    public SummaryContextService(BusinessTypeQueryRepository classifications, SummaryContextRepository repository,
            JsonMapper mapper, PlatformTransactionManager manager) {
        this.classifications = classifications; this.repository = repository; this.mapper = mapper;
        readSnapshot = new TransactionTemplate(manager);
        readSnapshot.setReadOnly(true);
        readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // 외부 트랜잭션의 READ_COMMITTED 설정을 물려받지 않고 조회 전용 스냅샷을 만든다.
        readSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T withContext(String regionCode, String businessTypeCode, Function<SummaryReadContext, T> query) {
        validateCodes(regionCode, businessTypeCode); // 트랜잭션 시작/DB 연결보다 먼저 검사한다.
        Objects.requireNonNull(query, "조회 작업이 필요합니다.");
        try { return readSnapshot.execute(status -> query.apply(resolve(regionCode, businessTypeCode))); }
        catch (DataAccessException | CannotCreateTransactionException failure) {
            // 원래 DB 예외는 서버 로그에서만 추적하고 API의 상태와 데이터 결측을 섞지 않는다.
            var error = new ApiException(ErrorCode.DATA_SERVICE_UNAVAILABLE); error.initCause(failure); throw error;
        }
    }

    private SummaryReadContext resolve(String regionCode, String businessTypeCode) {
        var current = classifications.findPublishedClassification().orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_DATA_NOT_READY));
        invariant("APPROVED".equals(current.releaseStatus()) && current.classificationId() != null);
        long classificationId = current.classificationId();
        invariant(classifications.findClassificationStatus(classificationId).filter("APPROVED"::equals).isPresent());
        String name = repository.localityName(current.releaseId(), regionCode)
                .orElseThrow(() -> error(ErrorCode.REGION_NOT_FOUND, "regionCode", "NOT_FOUND"));
        var industry = repository.industry(classificationId, businessTypeCode)
                .orElseThrow(() -> error(ErrorCode.BUSINESS_TYPE_NOT_FOUND, "businessTypeCode", "NOT_FOUND"));
        if (!industry.supported()) throw error(ErrorCode.BUSINESS_TYPE_NOT_SUPPORTED, "businessTypeCode", "NOT_SUPPORTED");
        invariant("APPROVED".equals(industry.supportStatus()));
        var summary = repository.defaultSummary().orElseThrow(() -> new ApiException(ErrorCode.SUMMARY_DATA_NOT_READY));
        invariant("APPROVED".equals(summary.status()) && summary.month().equals(summary.summaryMonth())
                && summary.revision() > 0 && summary.publishedAt() != null && "APPROVED".equals(summary.mappingStatus())
                && "APPROVED".equals(summary.classificationStatus()));
        var linked = mapper.readTree(summary.mappingReport()).path("classificationId");
        invariant(linked.isIntegralNumber() && linked.canConvertToLong() && linked.longValue() > 0
                && linked.longValue() == summary.classificationId());
        Source population = source(summary.populationId(), "POPULATION_MONTH", summary.month(), summary.mappingId(), null);
        Source stores = source(summary.storeId(), "STORE_SNAPSHOT", summary.month(), summary.mappingId(), summary.classificationId());
        return new SummaryReadContext(new Selection(current.releaseId(), classificationId, regionCode, name, businessTypeCode, industry.name()),
                new PublishedSummary(summary.id(), summary.month(), summary.revision(), summary.publishedAt().toInstant(ZoneOffset.UTC),
                        summary.mappingId(), summary.classificationId()), population, stores);
    }

    private Source source(long id, String dataset, String month, long mapping, Long classification) {
        BatchSource b = repository.source(id).orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        boolean population = dataset.equals("POPULATION_MONTH");
        invariant(dataset.equals(b.dataset()) && "SUCCEEDED".equals(b.status()) && month.equals(b.month()) && mapping == b.mappingId()
                && Objects.equals(classification, b.classificationId()) && (population ? "MOIS_RESIDENT" : "SEMAS_STORE").equals(b.system())
                && (population ? "CSV" : "ZIP").equals(b.artifactKind()) && b.dataSha256().equals(b.artifactSha256())
                && b.collectedAt().equals(b.artifactCollectedAt()));
        return new Source(b.id(), dataset, b.sourceVersion(), b.referenceOn(), population ? null : b.label(), b.collectedAt().toInstant(ZoneOffset.UTC));
    }

    /** HTTP query의 중복·미정의 키 검사는 T18이 맡는다. 여기서도 코드를 정규화하지 않고 엄격히 검사한다. */
    public static void validateCodes(String regionCode, String businessTypeCode) {
        if (businessTypeCode == null || businessTypeCode.isEmpty()) throw error(ErrorCode.INVALID_REQUEST, "businessTypeCode", "REQUIRED");
        if (regionCode == null || !regionCode.matches("[0-9]{10}")) throw error(ErrorCode.INVALID_REGION_CODE, "regionCode", "INVALID_FORMAT");
        if (!businessTypeCode.matches("[A-Z][0-9]{5}")) throw error(ErrorCode.INVALID_REQUEST, "businessTypeCode", "INVALID_FORMAT");
    }
    private static void invariant(boolean valid) { if (!valid) throw new ApiException(ErrorCode.INTERNAL_ERROR); }
    private static ApiException error(ErrorCode code, String field, String reason) { return new ApiException(code, List.of(new ErrorDetail(field, reason))); }
}
