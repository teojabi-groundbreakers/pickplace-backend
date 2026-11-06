package com.pickplace.backend.ingestion.download;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import com.pickplace.backend.PickplaceBackendApplication;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 시작점에서도 웹 서버와 DB 없이 실행되는지 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class DataDownloadBootstrapTests {

	@TempDir
	Path temporaryDirectory;

	// 계획을 출력하고 파일 생성 없이 종료하는지 확인한다.
	@Test
	void mainReturnsWithoutBootstrappingWebOrDatabase(CapturedOutput output) {
		Path destination = temporaryDirectory.resolve("raw");
		PickplaceBackendApplication.main(new String[]{
				"--spring.profiles.active=data-download", "--dataset=RESIDENT",
				"--quarter=20262", "--output=" + destination, "--plan-only"});

		var plan = JsonMapper.builder().build().readTree(output.getOut());
		assertThat(plan.path("status").asString()).isEqualTo("PLANNED");
		assertThat(plan.path("mode").asString()).isEqualTo("PLAN_ONLY");
		assertThat(output.getErr()).isEmpty();
		assertThat(output.getOut()).doesNotContain("HikariPool", "Tomcat", "Flyway", "Starting Pickplace");
		assertThat(Files.exists(destination)).isFalse();
	}
}
