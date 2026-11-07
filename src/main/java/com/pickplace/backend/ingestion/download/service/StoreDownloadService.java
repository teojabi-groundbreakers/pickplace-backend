package com.pickplace.backend.ingestion.download.service;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pickplace.backend.ingestion.download.model.DownloadPlan;
import com.pickplace.backend.ingestion.download.model.SourceCatalog;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 업종별 원본을 모으고 대상 분기의 완전성을 확인한다.
public class StoreDownloadService {

	private static final String METADATA_FILE = "metadata.json";
	private static final String TARGET_FILE = "store-target.json";
	private final JsonMapper mapper;

	public StoreDownloadService(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public Result download(DownloadPlan plan, List<String> industryCodes, PrintStream progress)
			throws IOException, InterruptedException {
		validatePartitions(plan, industryCodes);
		Path parent = Files.createDirectories(Path.of(plan.outputDirectory()).resolve("store").resolve(plan.quarter()));
		String runId = UUID.randomUUID().toString();
		Path staging = Files.createDirectory(parent.resolve(".pending-" + runId));
		Path destination = parent.resolve(runId);
		var partitions = new ArrayList<Partition>();
		var metadata = new LinkedHashMap<String, Object>();
		metadata.put("status", "RUNNING");
		metadata.put("dataset", plan.dataset());
		metadata.put("sourceId", plan.sourceId());
		metadata.put("quarter", plan.quarter());
		metadata.put("startedAt", Instant.now().toString());
		metadata.put("expectedPartitions", industryCodes.size());
		metadata.put("completedPartitions", 0);
		metadata.put("countRequest", plan.countRequest());
		metadata.put("partitions", partitions);
		var targetRows = new ArrayList<JsonNode>();
		var targetKeys = new HashSet<String>();
		String activeCode = null;
		int officialCount;
		String targetChecksum;

		try {
			writeMetadata(staging, metadata);
			try (var client = new PublicDataHttpClient()) {
				byte[] countResponse = client.post("점포 공식 총건수 조회", plan.countRequest());
				Files.write(staging.resolve("official-count.txt"), countResponse);
				officialCount = client.officialCount(countResponse, plan.quarter());
				metadata.put("officialCount", officialCount);
				JsonNode description = null;

				for (String code : industryCodes) {
					activeCode = code;
					DownloadPlan.Request request = partitionRequest(plan, code);
					byte[] raw = client.post("점포 " + code + " 다운로드", request);
					String collectedAt = Instant.now().toString();
					String fileName = code + ".json";
					Files.write(staging.resolve(fileName), raw);
					var partition = new Partition(code, fileName, collectedAt, raw.length,
							sha256(raw), request, null, null);
					partitions.add(partition);
					JsonNode document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
					int countBefore = targetRows.size();
					validateRows(document, code, plan.quarter(), targetRows, targetKeys);
					if (description == null) {
						description = document.path("DESCRIPTION");
					}
					partitions.set(partitions.size() - 1, new Partition(code, fileName, collectedAt, raw.length,
							partition.sha256(), request, document.path("DATA").size(), targetRows.size() - countBefore));
					metadata.put("completedPartitions", partitions.size());
					writeMetadata(staging, metadata);
					if (partitions.size() % 10 == 0 || partitions.size() == industryCodes.size()) {
						progress.println("점포 수집 " + partitions.size() + "/" + industryCodes.size()
								+ "업종 · 대상 분기 " + targetRows.size() + "행");
					}
				}
				activeCode = null;
				if (targetRows.size() != officialCount || targetKeys.size() != officialCount) {
					throw new IOException("점포 공식 총건수와 대상 분기의 행·고유키 수가 다릅니다. 공식 "
							+ officialCount + "행, 원본 " + targetRows.size() + "행, 고유키 " + targetKeys.size() + "개");
				}
				// 원본 파일과 구분되는 대상 분기 추출본이다.
				byte[] target = mapper.writeValueAsBytes(Map.of("DESCRIPTION", description, "DATA", targetRows));
				targetChecksum = sha256(target);
				Files.write(staging.resolve(TARGET_FILE), target);
				metadata.put("rows", targetRows.size());
				metadata.put("uniqueKeys", targetKeys.size());
				metadata.put("targetFile", TARGET_FILE);
				metadata.put("targetSha256", targetChecksum);
			}
			metadata.put("status", "DOWNLOADED");
			metadata.put("finishedAt", Instant.now().toString());
			writeMetadata(staging, metadata);
			Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException | InterruptedException exception) {
			metadata.put("status", "FAILED");
			metadata.put("failedPartition", activeCode);
			metadata.put("error", exception.getMessage());
			metadata.put("finishedAt", Instant.now().toString());
			Path failed = parent.resolve("failed-" + runId);
			try {
				writeMetadata(staging, metadata);
				Files.move(staging, failed, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException | RuntimeException recordError) {
				exception.addSuppressed(recordError);
				failed = staging;
			}
			if (exception instanceof InterruptedException interrupted) {
				throw interrupted;
			}
			throw new IOException("점포 수집 실패. 수집 기록: " + failed + ". " + exception.getMessage(), exception);
		}
		return new Result("DOWNLOADED", destination.resolve(METADATA_FILE).toString(),
				destination.resolve(TARGET_FILE).toString(), partitions.size(), officialCount, targetRows.size(), targetChecksum);
	}

	private void validatePartitions(DownloadPlan plan, List<String> codes) {
		if (!"STORE".equals(plan.dataset()) || plan.strategy() != SourceCatalog.Strategy.SHEET_INDUSTRY_PARTITION
				|| codes == null || codes.isEmpty() || codes.size() != plan.partitionCount()
				|| new HashSet<>(codes).size() != codes.size()
				|| codes.stream().anyMatch(code -> code == null || !code.matches("CS[0-9]{6}"))) {
			throw new IllegalArgumentException("점포 수집 계획과 업종 목록이 일치하지 않습니다.");
		}
	}

	private DownloadPlan.Request partitionRequest(DownloadPlan plan, String code) {
		var template = plan.downloadRequestTemplate();
		var parameters = new LinkedHashMap<>(template.parameters());
		parameters.replaceAll((key, value) -> value.replace("{businessTypeCode}", code));
		return new DownloadPlan.Request(template.method(), template.url(), template.contentType(), parameters);
	}

	private void validateRows(JsonNode document, String code, String quarter,
			List<JsonNode> targetRows, HashSet<String> targetKeys) throws IOException {
		JsonNode rows = document.path("DATA");
		if (!document.path("DESCRIPTION").isObject() || !rows.isArray()) {
			throw new IOException(code + " 원본에 DESCRIPTION 객체 또는 DATA 배열이 없습니다.");
		}
		var partitionKeys = new HashSet<String>();
		for (JsonNode row : rows) {
			String rowQuarter = row.path("stdr_yyqu_cd").asString();
			String dong = row.path("adstrd_cd").asString();
			if (!code.equals(row.path("svc_induty_cd").asString())
					|| rowQuarter == null || !rowQuarter.matches("[0-9]{4}[1-4]")
					|| dong == null || !dong.matches("[0-9]{8}") || !row.has("similr_induty_stor_co")) {
				throw new IOException(code + " 원본의 업종·분기·행정동·핵심 필드가 올바르지 않습니다.");
			}
			String key = rowQuarter + "|" + dong + "|" + code;
			if (!partitionKeys.add(key)) {
				throw new IOException(code + " 원본에 중복 고유키가 있습니다: " + key);
			}
			if (quarter.equals(rowQuarter)) {
				if (!targetKeys.add(key)) {
					throw new IOException("점포 분할 간 중복 고유키가 있습니다: " + key);
				}
				targetRows.add(row);
			}
		}
	}

	private void writeMetadata(Path directory, Map<String, Object> metadata) throws IOException {
		Path temporary = directory.resolve("metadata.tmp");
		Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
		Files.move(temporary, directory.resolve(METADATA_FILE),
				StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private String sha256(byte[] raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private record Partition(String businessTypeCode, String rawFile, String collectedAt, int bytes, String sha256,
			DownloadPlan.Request request, Integer rows, Integer targetRows) {}

	public record Result(String status, String metadataFile, String targetFile, int partitionCount,
			int officialCount, int rows, String sha256) {}
}
