package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import com.pickplace.backend.ingestion.download.model.SourceCatalog;

// 실행 설정과 원천 업종 목록을 읽는다.
public class SourceCatalogLoader {

	private final JsonMapper mapper;

	public SourceCatalogLoader(JsonMapper mapper) {
		this.mapper = mapper;
	}

	// JSON 설정을 읽고 필수 항목을 확인한다.
	public SourceCatalog load() {
		try (var input = new ClassPathResource("data-download/source-catalog.json").getInputStream()) {
			SourceCatalog catalog = mapper.readValue(input, SourceCatalog.class);
			validate(catalog);
			return catalog;
		} catch (IOException exception) {
			throw new UncheckedIOException("수집 카탈로그를 읽을 수 없습니다.", exception);
		}
	}

	// 지원 여부를 거르지 않고 원천 업종 코드 전체를 읽는다.
	public List<String> loadIndustryCodes(SourceCatalog catalog) {
		try (var input = new ClassPathResource(catalog.industryCodesResource()).getInputStream()) {
			var snapshot = mapper.readTree(input);
			if (!snapshot.path("businessTypes").isArray()) {
				throw new IllegalStateException("업종 스냅샷에 businessTypes 배열이 없습니다.");
			}
			List<String> codes = snapshot.path("businessTypes").valueStream()
					.map(item -> item.path("code").asString())
					.toList();
			if (codes.size() != catalog.expectedIndustryCount()
					|| codes.stream().distinct().count() != codes.size()
					|| codes.stream().anyMatch(code -> code == null || !code.matches("CS[0-9]{6}"))) {
				throw new IllegalStateException("원천 업종 코드의 형식·중복·개수가 수집 설정과 다릅니다.");
			}
			return codes.stream().sorted().toList();
		} catch (IOException exception) {
			throw new UncheckedIOException("원천 업종 코드를 읽을 수 없습니다.", exception);
		}
	}

	// 수집 경로와 요청 조건이 설정에 맞는지 확인한다.
	private void validate(SourceCatalog catalog) {
		if (catalog == null || catalog.schemaVersion() != 1
				|| catalog.supportedQuarters() == null || catalog.supportedQuarters().isEmpty()
				|| catalog.supportedQuarters().stream().anyMatch(quarter ->
						quarter == null || !quarter.matches("[0-9]{4}[1-4]"))
				|| catalog.endpoints() == null || catalog.datasets() == null || catalog.datasets().isEmpty()
				|| catalog.industryCodesResource() == null || catalog.industryCodesResource().isBlank()
				|| catalog.expectedIndustryCount() < 1) {
			throw new IllegalStateException("수집 카탈로그의 버전 또는 필수 설정이 올바르지 않습니다.");
		}
		validateUrl(catalog.endpoints().previewAndCount());
		validateUrl(catalog.endpoints().sheetDownload());
		validateParameters(catalog.requestDefaults());
		var codes = new HashSet<String>();
		for (var dataset : catalog.datasets()) {
			if (dataset == null || dataset.code() == null || !dataset.code().matches("[A-Z][A-Z0-9_]*")
					|| !codes.add(dataset.code()) || dataset.sourceId() == null
					|| !dataset.sourceId().matches("OA-[0-9]+") || dataset.strategy() == null) {
				throw new IllegalStateException("수집 데이터셋의 코드·식별자·전략이 올바르지 않습니다.");
			}
			validateParameters(dataset.requestTemplate());
			for (String key : List.of("filterCol", "txtFilter", "strOrderby")) {
				if (!dataset.requestTemplate().containsKey(key) || dataset.requestTemplate().get(key).isBlank()) {
					throw new IllegalStateException("수집 요청 조건이 없습니다: " + key);
				}
			}
			boolean partitioned = dataset.strategy() == SourceCatalog.Strategy.SHEET_INDUSTRY_PARTITION;
			String expectedFilter = partitioned ? "SVC_INDUTY_CD" : "STDR_YYQU_CD";
			String expectedValue = partitioned ? "{businessTypeCode}" : "{quarter}";
			if (!expectedFilter.equals(dataset.requestTemplate().get("filterCol"))
					|| !expectedValue.equals(dataset.requestTemplate().get("txtFilter"))) {
				throw new IllegalStateException("수집 전략과 요청 필터가 일치하지 않습니다.");
			}
		}
	}

	// 요청 파라미터에 이름이나 값이 빠졌는지 확인한다.
	private void validateParameters(Map<String, String> parameters) {
		if (parameters == null || parameters.isEmpty()
				|| parameters.entrySet().stream().anyMatch(entry ->
						entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)) {
			throw new IllegalStateException("수집 요청 파라미터가 올바르지 않습니다.");
		}
	}

	// 수집 주소의 형식을 확인한다.
	private void validateUrl(String url) {
		if (url == null) {
			throw new IllegalStateException("수집 경로가 없습니다.");
		}
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("수집 경로의 URI 형식이 올바르지 않습니다.", exception);
		}
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
			throw new IllegalStateException("수집 경로에는 인증정보 없는 HTTPS 주소를 지정해야 합니다.");
		}
	}
}
