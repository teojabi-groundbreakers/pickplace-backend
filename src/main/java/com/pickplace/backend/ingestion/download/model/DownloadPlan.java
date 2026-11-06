package com.pickplace.backend.ingestion.download.model;

import java.util.List;
import java.util.Map;

// 실행 전에 확인할 수집 계획을 담는다.
public record DownloadPlan(
		String status,
		String mode,
		String message,
		String dataset,
		String sourceId,
		String quarter,
		String outputDirectory,
		SourceCatalog.Strategy strategy,
		int partitionCount,
		String firstPartitionCode,
		String industryCodesResource,
		Request countRequest,
		Request downloadRequestTemplate,
		List<String> unresolvedVariables,
		int networkRequestsExecuted,
		int filesWritten) {

	// 요청 주소, 전송 방식, 파라미터를 묶는다.
	public record Request(String method, String url, String contentType, Map<String, String> parameters) {}
}
