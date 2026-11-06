package com.pickplace.backend.ingestion.download.model;

import java.util.List;
import java.util.Map;

// 실행용 수집 설정 JSON을 담는다.
public record SourceCatalog(
		int schemaVersion,
		List<String> supportedQuarters,
		Endpoints endpoints,
		Map<String, String> requestDefaults,
		String industryCodesResource,
		int expectedIndustryCount,
		List<Dataset> datasets) {

	// 분기로 받을지, 업종별로 나눠 받을지 구분한다.
	public enum Strategy {
		SHEET_QUARTER_FILTER,
		SHEET_INDUSTRY_PARTITION
	}

	// 총건수 조회 주소와 다운로드 주소다.
	public record Endpoints(String previewAndCount, String sheetDownload) {}

	// 데이터 종류 하나의 수집 방식과 요청 조건이다.
	public record Dataset(
			String code,
			String sourceId,
			Strategy strategy,
			Map<String, String> requestTemplate) {}
}
