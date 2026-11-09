package com.pickplace.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.pickplace.backend.ingestion.download.cli.DataDownloadCommand;
import com.pickplace.backend.ingestion.download.cli.NationalDataDownloadCommand;

@SpringBootApplication
public class PickplaceBackendApplication {

	public static void main(String[] args) {
		if (NationalDataDownloadCommand.isRequested(args)) {
			int exitCode = NationalDataDownloadCommand.execute(args, System.out, System.err);
			if (exitCode != 0) System.exit(exitCode);
			return;
		}
		// 다운로드 CLI는 웹 서버·DB·Flyway 초기화 전에 독립적으로 실행한다.
		if (DataDownloadCommand.isRequested(args)) {
			int exitCode = DataDownloadCommand.execute(args, System.out, System.err);
			if (exitCode != 0) {
				System.exit(exitCode);
			}
			return;
		}
		var context = SpringApplication.run(PickplaceBackendApplication.class, args);
		if (context.getEnvironment().matchesProfiles("reference-import", "national-reference")) {
			context.close();
		}
	}

}
