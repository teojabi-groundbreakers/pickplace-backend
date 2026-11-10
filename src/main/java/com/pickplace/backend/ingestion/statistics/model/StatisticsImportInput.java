package com.pickplace.backend.ingestion.statistics.model;

import java.io.IOException;
import java.nio.file.Path;

import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.download.service.NationalRawValidator;
import com.pickplace.backend.ingestion.validation.service.NationalValidationService.Report;
import com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot;

/** 파일 검증을 통과한 적재 입력이다. DB 승인 릴리스 연결은 적재 서비스가 별도로 확인한다. */
public record StatisticsImportInput(Report report, NationalDownloadSource source, NationalReferenceSnapshot snapshot,
        Path rows, Path issues, Path raw, Path metadata, Path reference, Path reportFile, String reportSha256,
        java.util.Set<String> sourceCodes) {

    // 여러 청크를 적재하는 동안 파일이 변경되면 성공 처리하지 않는다. 실패 청크는 별도 배치로 남는다.
    public void verifyUnchanged() throws IOException {
        requireHash(rows, report.rowsSha256());
        requireHash(issues, report.issuesSha256());
        requireHash(raw, report.rawSha256());
        requireHash(metadata, report.receiptSha256());
        requireHash(reference, report.mappingSha256());
        requireHash(reportFile, reportSha256);
    }

    private static void requireHash(Path path, String expected) throws IOException {
        if (!NationalRawValidator.sha256(path).equals(expected)) throw new IOException("적재 입력 파일이 변경되었습니다: " + path.getFileName());
    }
}
