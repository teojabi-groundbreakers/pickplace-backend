package com.pickplace.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.pickplace.backend.ingestion.download.cli.NationalDataDownloadCommand;
import com.pickplace.backend.ingestion.validation.cli.NationalDataValidationCommand;
import com.pickplace.backend.ingestion.statistics.cli.NationalStatisticsRunner;
import com.pickplace.backend.ingestion.detail.cli.RegionalDetailRunner;

@SpringBootApplication
public class PickplaceBackendApplication {

	public static void main(String[] args) {
		if (RegionalDetailRunner.isRequested(args)) {
			RegionalDetailRunner.validateArguments(new org.springframework.boot.DefaultApplicationArguments(args));
		}
		// 정제·검증도 독립 명령으로 실행한다. DB가 꺼져 있어도 파일 검사를 할 수 있어야 한다.
		if (NationalDataValidationCommand.isRequested(args)) {
			int exitCode = NationalDataValidationCommand.execute(args, System.out, System.err);
			if (exitCode != 0) System.exit(exitCode);
			return;
		}
		// 수집 명령은 웹 서버·DB 초기화 전에 처리한다. 폐기된 프로필도 CLI에서 안내하고 종료한다.
		if (NationalDataDownloadCommand.isRequested(args)) {
			int exitCode = NationalDataDownloadCommand.execute(args, System.out, System.err);
			if (exitCode != 0) System.exit(exitCode);
			return;
		}
		if (NationalStatisticsRunner.isRequested(args)) {
			NationalStatisticsRunner.validateArguments(new org.springframework.boot.DefaultApplicationArguments(args));
		}
		var context = SpringApplication.run(PickplaceBackendApplication.class, args);
		if (context.getEnvironment().matchesProfiles("reference-import", "national-reference", "national-statistics", "regional-detail")) {
			context.close();
		}
	}

}
