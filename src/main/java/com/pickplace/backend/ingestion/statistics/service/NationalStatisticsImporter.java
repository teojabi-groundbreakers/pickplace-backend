package com.pickplace.backend.ingestion.statistics.service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.pickplace.backend.ingestion.statistics.model.StatisticsImportInput;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import com.pickplace.backend.ingestion.validation.service.ValidationReference;
import tools.jackson.databind.json.JsonMapper;

/** T13 결과를 500행씩 적재한다. 실패한 배치의 이미 커밋된 청크는 이력으로 남기고 새 배치로 재시도한다. */
@Service
public class NationalStatisticsImporter {
    private static final Logger log = LoggerFactory.getLogger(NationalStatisticsImporter.class);
    private final JsonMapper mapper;
    private final StatisticsBatchService batches;
    private final StatisticsFactWriter writer;
    private final StatisticsAggregationService aggregation;

    public NationalStatisticsImporter(JsonMapper mapper, StatisticsBatchService batches,
            StatisticsFactWriter writer, StatisticsAggregationService aggregation) {
        this.mapper = mapper; this.batches = batches; this.writer = writer; this.aggregation = aggregation;
    }

    public ImportResult importData(StatisticsImportInput input) throws IOException {
        input.verifyUnchanged();
        var batch = batches.start(input);
        if (!batch.inserted()) return new ImportResult(batch.id(), "REUSED");
        try {
            writer.artifacts(batch, input);
            var reference = new ValidationReference(input.snapshot());
            var ledger = new StatisticsImportLedger();
            var chunk = new ArrayList<ValidationRow>(500);
            long processed = 0;
            try (var reader = Files.newBufferedReader(input.rows())) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("적재 작업이 중단되었습니다.");
                    if (line.isBlank() || line.length() > 1024 * 1024) throw new IOException("정제 JSONL 행 크기·형식 오류");
                    chunk.add(mapper.readValue(line, ValidationRow.class));
                    if (++processed > input.report().sourceRows()) throw new IOException("원천행 상한 초과");
                    if (chunk.size() == 500) {
                        writer.chunk(batch, input, chunk, reference, ledger); chunk.clear();
                        if (processed % 100_000 == 0) log.info("Statistics import batchId={} processed={}", batch.id(), processed);
                    }
                }
            }
            if (!chunk.isEmpty()) writer.chunk(batch, input, chunk, reference, ledger);
            ledger.verify(input.report());
            input.verifyUnchanged();
            batches.inTransaction(() -> {
                batches.lockWritable(batch.id(), "RUNNING");
                // 이후에는 사실 행을 더 쓰지 않는다. 부분 집계가 남아도 배치 성공 전까지 게시할 수 없다.
                batches.beginValidation(batch.id());
                return null;
            });
            aggregation.aggregate(batch, input, ledger, reference);
            aggregation.complete(batch, input, ledger);
            return new ImportResult(batch.id(), "SUCCEEDED");
        } catch (IOException | RuntimeException failure) {
            try { batches.failed(batch.id(), "IMPORT_FAILED"); }
            catch (RuntimeException markingFailure) { failure.addSuppressed(markingFailure); }
            throw failure;
        }
    }

    public record ImportResult(long batchId, String status) {}
}
