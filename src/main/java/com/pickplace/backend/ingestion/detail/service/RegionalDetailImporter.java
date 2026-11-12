package com.pickplace.backend.ingestion.detail.service;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import com.pickplace.backend.ingestion.detail.service.DetailRowValidator.Row;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.*;

@Service
public class RegionalDetailImporter {
    private static final Logger log = LoggerFactory.getLogger(RegionalDetailImporter.class);
    private final List<RegionalSourceAdapter> adapters;
    private final DetailRowValidator validator;
    private final DetailBatchService batches;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public RegionalDetailImporter(
            List<RegionalSourceAdapter> adapters,
            DetailRowValidator validator,
            DetailBatchService batches,
            JdbcTemplate jdbc,
            JsonMapper mapper) {
        this.adapters = adapters;
        this.validator = validator;
        this.batches = batches;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Result importData(DetailInputLoader.Input input) throws IOException {
        input.verify();
        var adapter =
                adapters.stream()
                        .filter(a -> a.id().equals(input.manifest().adapter()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 원천 어댑터"));
        var reference = validator.reference(input);
        var batch = batches.start(input, reference.releaseId());
        if (!batch.inserted()) return new Result(batch.id(), "REUSED");
        var ledger = new Ledger();
        var chunk = new ArrayList<Row>(500);
        try {
            adapter.read(
                    input,
                    raw -> {
                        if (Thread.currentThread().isInterrupted())
                            throw new IllegalStateException("적재가 중단됐습니다.");
                        require(++ledger.source <= input.manifest().expectedRows(), "원천 행 수 상한 초과");
                        Row row = validator.validate(input, reference, ledger.source, raw);
                        ledger.periods.add(row.period());
                        chunk.add(row);
                        if (chunk.size() == 500) {
                            write(batch, chunk, ledger);
                            chunk.clear();
                        }
                        if (ledger.source % 100_000 == 0)
                            log.info("Detail batch={} processed={}", batch.id(), ledger.source);
                    });
            if (!chunk.isEmpty()) write(batch, chunk, ledger);
            require(
                    ledger.source == input.manifest().expectedRows()
                            && ledger.periods.equals(new HashSet<>(input.manifest().periods())),
                    "원천 건수·분기 목록 불일치");
            input.verify();
            batches.transaction(
                    () -> {
                        batches.writable(batch.id());
                        long valid = count(batch.id(), "VALID"),
                                hold = count(batch.id(), "HOLD"),
                                invalid = count(batch.id(), "INVALID") + ledger.badKeys;
                        require(
                                ledger.source == valid + hold + invalid + ledger.duplicates,
                                "원천 행 회계 불일치");
                        var report = new LinkedHashMap<String, Object>();
                        report.put("sourceRows", ledger.source);
                        report.put("accepted", valid);
                        report.put("held", hold);
                        report.put("invalid", invalid);
                        report.put("duplicates", ledger.duplicates);
                        report.put("periods", new TreeSet<>(ledger.periods));
                        report.put("writer", DetailInputLoader.WRITER_VERSION);
                        report.put(
                                "commonIndustryMapping",
                                "NOT_APPLIED_SOURCE_CLASSIFICATION_PRESERVED");
                        jdbc.update(
                                """
                                UPDATE regional_statistic_batch SET status='READY',finished_at=UTC_TIMESTAMP(6),source_row_count=?,
                                    accepted_row_count=?,held_row_count=?,invalid_row_count=?,duplicate_row_count=?,validation_report=?
                                WHERE batch_id=?
                                """,
                                ledger.source,
                                valid,
                                hold,
                                invalid,
                                ledger.duplicates,
                                mapper.writeValueAsString(report),
                                batch.id());
                        return null;
                    });
            return new Result(batch.id(), "READY");
        } catch (IOException | RuntimeException failure) {
            try {
                batches.failed(batch.id(), "IMPORT_FAILED");
            } catch (RuntimeException marking) {
                failure.addSuppressed(marking);
            }
            throw failure;
        }
    }

    // 500행 단위로 기존 키를 한 번에 읽는다. 동일 키 충돌을 마지막 값 덮어쓰기로 숨기지 않는다.
    private void write(DetailBatchService.Batch batch, List<Row> input, Ledger ledger) {
        batches.transaction(
                () -> {
                    batches.writable(batch.id());
                    var usable = input.stream().filter(Row::usableKey).toList();
                    var existing = new HashMap<String, Previous>();
                    if (!usable.isEmpty()) {
                        String tuples =
                                String.join(",", Collections.nCopies(usable.size(), "(?,?,?)"));
                        var args = new ArrayList<Object>();
                        args.add(batch.id());
                        usable.forEach(
                                r -> {
                                    args.add(r.period());
                                    args.add(r.sourceRegion());
                                    args.add(r.sourceIndustry());
                                });
                        jdbc.query(
                                "SELECT"
                                    + " period_key,source_region_code,source_industry_code,payload_sha256"
                                    + " FROM regional_statistic_row WHERE batch_id=? AND"
                                    + " (period_key,source_region_code,source_industry_code) IN ("
                                        + tuples
                                        + ")",
                                rs -> {
                                    existing.put(
                                            rs.getString(1)
                                                    + ":"
                                                    + rs.getString(2)
                                                    + ":"
                                                    + rs.getString(3),
                                            new Previous(rs.getString(4)));
                                },
                                args.toArray());
                    }
                    var fresh = new LinkedHashMap<String, Row>();
                    var issues = new ArrayList<Object[]>();
                    var conflicts = new ArrayList<Object[]>();
                    for (var row : input) {
                        if (!row.usableKey()) {
                            ledger.badKeys++;
                            issues.add(issue(batch.id(), row, "INVALID_SOURCE_KEY"));
                            continue;
                        }
                        String old =
                                fresh.containsKey(row.key())
                                        ? fresh.get(row.key()).hash()
                                        : existing.containsKey(row.key())
                                                ? existing.get(row.key()).hash()
                                                : null;
                        if (old != null) {
                            ledger.duplicates++;
                            boolean conflict = !old.equals(row.hash());
                            issues.add(
                                    issue(
                                            batch.id(),
                                            row,
                                            conflict
                                                    ? "DUPLICATE_CONFLICT"
                                                    : "DUPLICATE_IDENTICAL"));
                            if (conflict) {
                                if (fresh.containsKey(row.key())) {
                                    var first = fresh.get(row.key());
                                    fresh.put(row.key(), invalidated(first));
                                } else
                                    conflicts.add(
                                            new Object[] {
                                                batch.id(),
                                                row.period(),
                                                row.sourceRegion(),
                                                row.sourceIndustry()
                                            });
                            }
                        } else {
                            fresh.put(row.key(), row);
                            if (!row.status().equals("VALID"))
                                issues.add(issue(batch.id(), row, row.reason()));
                        }
                    }
                    if (!fresh.isEmpty())
                        jdbc.batchUpdate(
                                """
                                INSERT INTO regional_statistic_row(batch_id,period_key,source_region_code,source_industry_code,mapping_release_id,
                                    region_code,mapping_status,temporal_status,row_status,reason_code,metrics,raw_payload,payload_sha256,source_row_number)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                                """,
                                fresh.values().stream()
                                        .map(
                                                r ->
                                                        new Object[] {
                                                            batch.id(),
                                                            r.period(),
                                                            r.sourceRegion(),
                                                            r.sourceIndustry(),
                                                            batch.mappingId(),
                                                            r.region(),
                                                            r.mappingStatus(),
                                                            r.temporalStatus(),
                                                            r.status(),
                                                            r.reason(),
                                                            r.metrics(),
                                                            r.payload(),
                                                            r.hash(),
                                                            r.number()
                                                        })
                                        .toList());
                    if (!conflicts.isEmpty())
                        jdbc.batchUpdate(
                                "UPDATE regional_statistic_row SET"
                                    + " row_status='INVALID',reason_code='DUPLICATE_CONFLICT' WHERE"
                                    + " batch_id=? AND period_key=? AND source_region_code=? AND"
                                    + " source_industry_code=?",
                                conflicts);
                    if (!issues.isEmpty())
                        jdbc.batchUpdate(
                                "INSERT INTO"
                                    + " regional_statistic_issue(batch_id,source_row_number,issue_code,evidence_json)"
                                    + " VALUES(?,?,?,?)",
                                issues);
                    return null;
                });
    }

    private static Row invalidated(Row r) {
        return new Row(
                r.period(),
                r.sourceRegion(),
                r.sourceIndustry(),
                r.region(),
                r.mappingStatus(),
                r.temporalStatus(),
                "INVALID",
                "DUPLICATE_CONFLICT",
                r.metrics(),
                r.payload(),
                r.hash(),
                r.number(),
                true);
    }

    private Object[] issue(long batch, Row row, String reason) {
        return new Object[] {
            batch,
            row.number(),
            reason,
            mapper.writeValueAsString(
                    Map.of("key", row.key(), "raw", mapper.readTree(row.payload())))
        };
    }

    private long count(long batch, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM regional_statistic_row WHERE batch_id=? AND row_status=?",
                Long.class,
                batch,
                status);
    }

    private record Previous(String hash) {}

    private static class Ledger {
        long source, badKeys, duplicates;
        Set<String> periods = new HashSet<>();
    }

    public record Result(long batchId, String status) {}
}
