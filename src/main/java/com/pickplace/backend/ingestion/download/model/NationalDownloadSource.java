package com.pickplace.backend.ingestion.download.model;

import java.time.LocalDate;
import java.util.List;

// 검증한 배포본 하나의 다운로드 조건이다. 새 기간은 원천 확인 후 별도 항목으로 추가한다.
public record NationalDownloadSource(String dataset, String sourceSystem, String sourceId, String periodType,
        String periodKey, String referenceOn, String sourceLabel, String sourcePage, String downloadUrl,
        String format, String encoding, long expectedBytes, String sha256, long expectedRows,
        List<String> headers, List<String> csvMembers) {

    public void validate() {
        boolean population = "POPULATION_MONTH".equals(dataset);
        if ((!population && !"STORE_SNAPSHOT".equals(dataset))
                || !(population ? "MOIS_RESIDENT" : "SEMAS_STORE").equals(sourceSystem)
                || !(population ? "MONTH" : "SNAPSHOT").equals(periodType)
                || !(population ? "CSV" : "ZIP").equals(format)
                || periodKey == null || !periodKey.matches("[1-9][0-9]{3}(0[1-9]|1[0-2])")
                || !LocalDate.parse(referenceOn).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")).equals(periodKey)
                || expectedBytes <= 0 || expectedBytes > 512L * 1024 * 1024 || expectedRows <= 0
                || sha256 == null || !sha256.matches("[0-9a-f]{64}") || sourceLabel == null || sourceLabel.isBlank()
                || headers == null || headers.isEmpty() || headers.stream().anyMatch(String::isBlank)
                || headers.stream().distinct().count() != headers.size() || csvMembers == null
                || (population ? !csvMembers.isEmpty() : csvMembers.isEmpty())
                || csvMembers.stream().distinct().count() != csvMembers.size()) {
            throw new IllegalArgumentException("전국 원천 카탈로그의 기간·형식·검증 조건이 올바르지 않습니다.");
        }
        java.nio.charset.Charset.forName(encoding);
    }
}
