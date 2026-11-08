package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 공식 건수와 원본을 대조한 뒤 한 묶음으로 보관한다.
public class QuarterDownloadService {

	private static final Map<String, String> PRIMARY_FIELDS = Map.of(
			"RESIDENT", "tot_repop_co",
			"WORKER", "tot_wrc_popltn_co",
			"FACILITY", "viatr_fclty_co");
	private static final String METADATA_FILE = "metadata.json";

	private final JsonMapper mapper;

	public QuarterDownloadService(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public static boolean supports(String dataset) {
		return PRIMARY_FIELDS.containsKey(dataset);
	}

	public Result download(DownloadPlan plan) throws IOException, InterruptedException {
		if (!supports(plan.dataset()) || plan.strategy() != SourceCatalog.Strategy.SHEET_QUARTER_FILTER) {
			throw new IllegalArgumentException("분기별 다운로드는 RESIDENT·WORKER·FACILITY를 지원합니다.");
		}
		try (var client = new PublicDataHttpClient()) {
			byte[] countResponse = client.post("공식 총건수 조회", plan.countRequest());
			int officialCount = client.officialCount(countResponse, plan.quarter());

			var template = plan.downloadRequestTemplate();
			var parameters = new LinkedHashMap<>(template.parameters());
			parameters.put("gridTotalCnt", Integer.toString(officialCount));
			var downloadRequest = new DownloadPlan.Request(
					template.method(), template.url(), template.contentType(), parameters);
			byte[] raw = client.post(plan.dataset() + " 원본 다운로드", downloadRequest);
			Instant collectedAt = Instant.now();
			int rows = validateRows(raw, plan.quarter(), officialCount, PRIMARY_FIELDS.get(plan.dataset()));
			return save(plan, downloadRequest, collectedAt, raw, officialCount, rows);
		}
	}

	private int validateRows(byte[] raw, String quarter, int officialCount, String primaryField) throws IOException {
		JsonNode document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
		JsonNode rows = document.path("DATA");
		if (!document.path("DESCRIPTION").isObject() || !rows.isArray()) {
			throw new IOException("원본에 DESCRIPTION 객체 또는 DATA 배열이 없습니다.");
		}
		if (rows.size() != officialCount) {
			throw new IOException("공식 총건수와 원본 행 수가 다릅니다. 공식 "
					+ officialCount + "행, 원본 " + rows.size() + "행");
		}
		var dongs = new HashSet<String>();
		for (JsonNode row : rows) {
			// 데이터 종류를 식별하는 필드를 확인하고 숫자 정제는 후속 단계에서 처리한다.
			if (!row.has(primaryField)) {
				throw new IOException("원본에 해당 데이터의 필수 필드가 없습니다: " + primaryField);
			}
			String dong = row.path("adstrd_cd").asString();
			if (!quarter.equals(row.path("stdr_yyqu_cd").asString())
					|| dong == null || !dong.matches("[0-9]{8}") || !dongs.add(dong)) {
				throw new IOException("원본의 기준 분기 또는 행정동 코드가 올바르지 않거나 중복됩니다.");
			}
		}
		return rows.size();
	}

	private Result save(DownloadPlan plan, DownloadPlan.Request downloadRequest, Instant collectedAt,
			byte[] raw, int officialCount, int rows) throws IOException {
		String checksum = sha256(raw);
		String directory = plan.dataset().toLowerCase(Locale.ROOT);
		String rawFile = directory + ".json";
		var metadata = new Metadata(plan.dataset(), plan.sourceId(), plan.quarter(), collectedAt.toString(),
				rawFile, officialCount, rows, raw.length, checksum, plan.countRequest(), downloadRequest);
		Path parent = Files.createDirectories(Path.of(plan.outputDirectory()).resolve(directory).resolve(plan.quarter()));
		Path destination = parent.resolve(UUID.randomUUID().toString());
		Path staging = Files.createTempDirectory(parent, ".pending-");
		try {
			Files.write(staging.resolve(rawFile), raw);
			Files.write(staging.resolve(METADATA_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
			// 두 파일을 모두 저장한 뒤 수집 폴더를 한 번에 확정한다.
			Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException exception) {
			try {
				Files.deleteIfExists(staging.resolve(METADATA_FILE));
				Files.deleteIfExists(staging.resolve(rawFile));
				Files.deleteIfExists(staging);
			} catch (IOException cleanupError) {
				exception.addSuppressed(cleanupError);
			}
			throw exception;
		}
		return new Result("DOWNLOADED", destination.resolve(rawFile).toString(),
				destination.resolve(METADATA_FILE).toString(), officialCount, rows, checksum);
	}

	private String sha256(byte[] raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private record Metadata(String dataset, String sourceId, String quarter, String collectedAt, String rawFile,
			int officialCount, int rows, int bytes, String sha256,
			DownloadPlan.Request countRequest, DownloadPlan.Request downloadRequest) {}

	public record Result(String status, String rawFile, String metadataFile, int officialCount, int rows, String sha256) {}
}
