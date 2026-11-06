package com.pickplace.backend.ingestion.download.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// 수집 대상, 분기, 저장 경로를 담는다.
public record DownloadRequest(String dataset, String quarter, Path output) {

	// 입력 형식을 확인하고 저장 경로를 정리한다.
	public DownloadRequest {
		if (dataset == null || dataset.isBlank()) {
			throw new IllegalArgumentException("--dataset은 필수입니다.");
		}
		dataset = dataset.toUpperCase(Locale.ROOT);
		if (quarter == null || !quarter.matches("[0-9]{4}[1-4]")) {
			throw new IllegalArgumentException("--quarter는 YYYYQ 형식이어야 합니다. 예: 20262");
		}
		if (output == null) {
			throw new IllegalArgumentException("--output 경로가 필요합니다.");
		}
		output = output.toAbsolutePath().normalize();
		if (Files.exists(output) && !Files.isDirectory(output)) {
			throw new IllegalArgumentException("--output에는 파일이 아닌 디렉터리 경로를 지정해야 합니다.");
		}
	}
}
