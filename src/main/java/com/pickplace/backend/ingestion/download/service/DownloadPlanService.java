package com.pickplace.backend.ingestion.download.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.DownloadRequest;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;

// 선택한 데이터에 맞는 수집 계획을 만든다.
public class DownloadPlanService {

	private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
	private static final List<String> ALLOWED_VARIABLES = List.of("businessTypeCode", "officialQuarterCount");

	private final SourceCatalog catalog;
	private final SourceCatalogLoader loader;

	public DownloadPlanService(SourceCatalog catalog, SourceCatalogLoader loader) {
		this.catalog = catalog;
		this.loader = loader;
	}

	// 대상 분기와 업종 범위를 확인하고 요청 조건을 조합한다.
	public DownloadPlan plan(DownloadRequest request) {
		if (!catalog.supportedQuarters().contains(request.quarter())) {
			throw new IllegalArgumentException("현재 수집 경로를 확인한 지원 분기: " + catalog.supportedQuarters()
					+ ". 다른 분기는 수집 경로 검증 후 확장합니다.");
		}
		var dataset = catalog.datasets().stream()
				.filter(item -> item.code().equals(request.dataset()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("지원하지 않는 데이터 종류입니다. 지원 목록: "
						+ catalog.datasets().stream().map(SourceCatalog.Dataset::code).toList()));

		boolean partitioned = dataset.strategy() == SourceCatalog.Strategy.SHEET_INDUSTRY_PARTITION;
		List<String> industryCodes = partitioned ? loader.loadIndustryCodes(catalog) : List.of();

		// 먼저 확인할 공식 총건수 조회 조건이다.
		Map<String, String> countParameters = defaults(dataset.sourceId());
		countParameters.put("strOrderby", dataset.requestTemplate().get("strOrderby"));
		countParameters.put("filterCol", "STDR_YYQU_CD");
		countParameters.put("txtFilter", request.quarter());
		countParameters.put("onepagerow", "1");

		// 분기는 채우고 실제 조회가 필요한 값은 템플릿에 남긴다.
		Map<String, String> downloadParameters = defaults(dataset.sourceId());
		dataset.requestTemplate().forEach((key, value) ->
				downloadParameters.put(key, value.replace("{quarter}", request.quarter())));
		List<String> unresolved = downloadParameters.values().stream()
				.flatMap(value -> VARIABLE.matcher(value).results().map(match -> match.group(1)))
				.distinct().sorted().toList();
		if (!ALLOWED_VARIABLES.containsAll(unresolved)) {
			throw new IllegalStateException("수집 설정에 알 수 없는 변수가 있습니다.");
		}

		return new DownloadPlan("PLANNED", "PLAN_ONLY",
				"수집 계획만 생성했습니다. 네트워크 요청·원본 저장은 아직 수행하지 않습니다.",
				dataset.code(), dataset.sourceId(), request.quarter(), request.output().toString(),
				dataset.strategy(), partitioned ? industryCodes.size() : 1,
				partitioned ? industryCodes.getFirst() : null,
				partitioned ? catalog.industryCodesResource() : null,
				new DownloadPlan.Request("POST", catalog.endpoints().previewAndCount(),
						"application/x-www-form-urlencoded", immutable(countParameters)),
				new DownloadPlan.Request("POST", catalog.endpoints().sheetDownload(),
						"application/x-www-form-urlencoded", immutable(downloadParameters)),
				unresolved, 0, 0);
	}

	// 공통 요청 값에 데이터 식별자를 넣는다.
	private Map<String, String> defaults(String sourceId) {
		Map<String, String> parameters = new LinkedHashMap<>(catalog.requestDefaults());
		parameters.put("infId", sourceId);
		return parameters;
	}

	// 계획에 담은 요청 조건이 나중에 바뀌지 않도록 복사한다.
	private Map<String, String> immutable(Map<String, String> parameters) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
	}
}
