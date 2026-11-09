package com.pickplace.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.pickplace.backend.ingestion.download.cli.NationalDataDownloadCommand;

@SpringBootApplication
public class PickplaceBackendApplication {

	public static void main(String[] args) {
		// 수집 명령은 웹 서버·DB 초기화 전에 처리한다. 폐기된 프로필도 CLI에서 안내하고 종료한다.
		if (NationalDataDownloadCommand.isRequested(args)) {
			int exitCode = NationalDataDownloadCommand.execute(args, System.out, System.err);
			if (exitCode != 0) System.exit(exitCode);
			return;
		}
		var context = SpringApplication.run(PickplaceBackendApplication.class, args);
		if (context.getEnvironment().matchesProfiles("reference-import", "national-reference")) {
			context.close();
		}
	}

}
