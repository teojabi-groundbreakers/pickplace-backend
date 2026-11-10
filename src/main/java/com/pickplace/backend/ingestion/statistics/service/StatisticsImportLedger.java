package com.pickplace.backend.ingestion.statistics.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import com.pickplace.backend.ingestion.validation.service.NationalValidationService.Report;
import static com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader.require;

/** 실제 읽은 행으로 회계를 다시 만든다. 보고서의 숫자만 복사해 성공 처리하지 않는다. */
final class StatisticsImportLedger {
    final Map<String, Long> dispositions = new TreeMap<>(Map.of("ACCEPTED", 0L, "HOLD", 0L, "REJECTED", 0L, "DUPLICATE", 0L));
    final Map<String, Long> issues = new TreeMap<>();
    final Map<String, Long> impacts = new TreeMap<>();
    final Map<String, Map<String, Long>> coverage = new TreeMap<>();
    final Map<String, Map<String, Long>> distribution = new HashMap<>();
    boolean globalHold;
    long factRows;
    long issueRows;

    void add(ValidationRow row) {
        require(row.sourceKey() != null && row.sourceRegionCode() != null && row.issues() != null
                && dispositions.containsKey(row.disposition()), "정제 행의 필수 값·판정 오류");
        dispositions.merge(row.disposition(), 1L, Long::sum);
        coverage.computeIfAbsent(row.sourceRegionCode(), ignored -> new TreeMap<>()).merge(row.disposition(), 1L, Long::sum);
        if (row.store() != null) distribution.computeIfAbsent(row.sourceRegionCode(), ignored -> new TreeMap<>())
                .merge(row.store().rawSmallCode() == null ? "UNKNOWN" : row.store().rawSmallCode(), 1L, Long::sum);
        if (row.disposition().equals("DUPLICATE")) return;
        var scopes = new HashSet<String>();
        for (var issue : row.issues()) {
            issues.merge(issue.code(), 1L, Long::sum); issueRows++;
            if (issue.affectsCount()) {
                globalHold |= "GLOBAL".equals(issue.impactScope());
                scopes.add(issue.impactScope() + ":" + issue.affectedRegion() + ":" + issue.affectedIndustry());
            }
        }
        scopes.forEach(scope -> impacts.merge(scope, 1L, Long::sum));
    }

    void verify(Report expected) {
        require(dispositions.equals(expected.dispositions()) && issues.equals(expected.issueCounts())
                && impacts.equals(expected.countImpacts()) && coverage.equals(expected.observedSourceRegions())
                && globalHold == expected.blocksNewPublication(), "실제 정제 행과 T13 보고서의 회계·영향 범위가 다릅니다.");
    }
}
