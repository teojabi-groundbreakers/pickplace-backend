package com.pickplace.backend.ingestion.download;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.pickplace.backend.ingestion.download.cli.DataDownloadCommand;

import static org.assertj.core.api.Assertions.assertThat;

// 수집 명령의 입력 검사와 계획 출력을 확인한다.
class DataDownloadCommandTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	Path temporaryDirectory;

	// 요청 조건은 만들고, 저장 경로는 건드리지 않는지 확인한다.
	@Test
	void createsQuarterRequestPlanWithoutCreatingOutputFiles() {
		Path output = temporaryDirectory.resolve("원본 자료").resolve("..").resolve("raw");
		Result result = execute("--dataset=resident", "--quarter=20262", "--output=" + output, "--plan-only");

		assertThat(result.exitCode()).isZero();
		assertThat(result.error()).isEmpty();
		JsonNode plan = JSON.readTree(result.output());
		assertThat(plan.path("status").asString()).isEqualTo("PLANNED");
		assertThat(plan.path("mode").asString()).isEqualTo("PLAN_ONLY");
		assertThat(plan.path("dataset").asString()).isEqualTo("RESIDENT");
		assertThat(plan.path("sourceId").asString()).isEqualTo("OA-22183");
		assertThat(plan.path("outputDirectory").asString()).isEqualTo(output.toAbsolutePath().normalize().toString());
		assertThat(plan.path("partitionCount").asInt()).isEqualTo(1);
		assertThat(plan.path("countRequest").path("parameters").path("txtFilter").asString()).isEqualTo("20262");
		assertThat(plan.path("downloadRequestTemplate").path("parameters").path("txtFilter").asString()).isEqualTo("20262");
		assertThat(plan.path("downloadRequestTemplate").path("parameters").path("gridTotalCnt").asString())
				.isEqualTo("{officialQuarterCount}");
		assertThat(plan.path("unresolvedVariables").valueStream().map(JsonNode::asString).toList())
				.containsExactly("officialQuarterCount");
		assertThat(plan.path("networkRequestsExecuted").asInt()).isZero();
		assertThat(plan.path("filesWritten").asInt()).isZero();
		assertThat(Files.exists(temporaryDirectory.resolve("raw"))).isFalse();
	}

	// 점포는 지원 3개가 아닌 원천 100업종을 대상으로 잡는지 확인한다.
	@Test
	void storePlanUsesAllOneHundredSourceIndustries() {
		Result result = execute("--dataset=STORE", "--quarter=20262");

		assertThat(result.exitCode()).isZero();
		JsonNode plan = JSON.readTree(result.output());
		assertThat(plan.path("partitionCount").asInt()).isEqualTo(100);
		assertThat(plan.path("firstPartitionCode").asString()).isEqualTo("CS100001");
		assertThat(plan.path("downloadRequestTemplate").path("parameters").path("filterCol").asString())
				.isEqualTo("SVC_INDUTY_CD");
		assertThat(plan.path("downloadRequestTemplate").path("parameters").path("txtFilter").asString())
				.isEqualTo("{businessTypeCode}");
		assertThat(plan.path("countRequest").path("parameters").path("filterCol").asString())
				.isEqualTo("STDR_YYQU_CD");
		assertThat(plan.path("countRequest").path("parameters").path("txtFilter").asString()).isEqualTo("20262");
		assertThat(plan.path("unresolvedVariables").valueStream().map(JsonNode::asString).toList())
				.containsExactly("businessTypeCode");
	}

	@ParameterizedTest
	@ValueSource(strings = {"WORKER", "FACILITY"})
	void resolvesOtherConfiguredQuarterDatasets(String dataset) {
		Result result = execute("--dataset=" + dataset, "--quarter=20262");

		assertThat(result.exitCode()).isZero();
		JsonNode plan = JSON.readTree(result.output());
		assertThat(plan.path("dataset").asString()).isEqualTo(dataset);
		assertThat(plan.path("strategy").asString()).isEqualTo("SHEET_QUARTER_FILTER");
		assertThat(plan.path("partitionCount").asInt()).isEqualTo(1);
	}

	// 저장 경로를 생략했을 때 기본값을 쓰는지 확인한다.
	@Test
	void defaultsOutputToRawDirectoryAndStillOnlyPlans() {
		Result result = execute("--dataset=RESIDENT", "--quarter=20262");

		assertThat(result.exitCode()).isZero();
		JsonNode plan = JSON.readTree(result.output());
		assertThat(plan.path("outputDirectory").asString()).isEqualTo(Path.of("data/raw").toAbsolutePath().normalize().toString());
		assertThat(plan.path("mode").asString()).isEqualTo("PLAN_ONLY");
	}

	@ParameterizedTest
	@ValueSource(strings = {"20260", "20265", "2026Q2", "2026", "abcde", "20262 "})
	void rejectsMalformedQuarter(String quarter) {
		Result result = execute("--dataset=RESIDENT", "--quarter=" + quarter);
		assertInputError(result);
		assertThat(result.error()).contains("YYYYQ");
	}

	@ParameterizedTest
	@ValueSource(strings = {"20243", "20263"})
	void rejectsQuartersOutsideTheVerifiedInitialScope(String quarter) {
		Result result = execute("--dataset=STORE", "--quarter=" + quarter);
		assertInputError(result);
		assertThat(result.error()).contains("지원 분기");
	}

	@ParameterizedTest
	@ValueSource(strings = {"SALES", "FLOW", "UNKNOWN"})
	void rejectsDatasetsOutsideCurrentCatalog(String dataset) {
		Result result = execute("--dataset=" + dataset, "--quarter=20262");
		assertInputError(result);
		assertThat(result.error()).contains("지원하지 않는");
	}

	@ParameterizedTest
	@MethodSource("invalidArguments")
	void rejectsMissingDuplicateUnknownAndBlankOptions(String[] arguments) {
		assertInputError(execute(arguments));
	}

	static Stream<Arguments> invalidArguments() {
		return Stream.of(
				Arguments.of((Object) new String[]{"--quarter=20262"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT"}),
				Arguments.of((Object) new String[]{"--dataset", "--quarter=20262"}),
				Arguments.of((Object) new String[]{"--dataset= ", "--quarter=20262"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--quarter=20261"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--output="}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--quater=20262"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "unexpected"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--plan-only=false"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--download=false"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20262", "--plan-only", "--download"}),
				Arguments.of((Object) new String[]{"--dataset=SALES", "--quarter=20262", "--download"}),
				Arguments.of((Object) new String[]{"--dataset=RESIDENT", "--quarter=20261", "--download"}));
	}

	// 저장 경로에 기존 파일을 지정해도 덮어쓰지 않는지 확인한다.
	@Test
	void rejectsAnExistingFileAsOutputDirectory() throws Exception {
		Path file = temporaryDirectory.resolve("existing.json");
		Files.writeString(file, "preserve");

		Result result = execute("--dataset=RESIDENT", "--quarter=20262", "--output=" + file);

		assertInputError(result);
		assertThat(result.error()).contains("디렉터리");
		assertThat(Files.readString(file)).isEqualTo("preserve");
	}

	// 다운로드 모드와 서버 프로필을 섞으면 거부하는지 확인한다.
	@Test
	void rejectsMixingDownloadAndServerProfiles() {
		Result result = invoke("--spring.profiles.active=data-download,local", "--dataset=RESIDENT", "--quarter=20262");
		assertInputError(result);
		assertThat(result.error()).contains("단독");
	}

	// 다운로드 모드를 지정한 경우에만 CLI로 들어가는지 확인한다.
	@Test
	void selectsDownloadModeOnlyWhenRequested() {
		assertThat(DataDownloadCommand.isRequested(new String[]{"--spring.profiles.active=data-download"})).isTrue();
		assertThat(DataDownloadCommand.isRequested(new String[]{"--spring.profiles.active=local"})).isFalse();
		assertThat(DataDownloadCommand.isRequested(new String[]{"--spring.profiles.active=reference-import"})).isFalse();
	}

	private Result execute(String... options) {
		List<String> arguments = new ArrayList<>();
		arguments.add("--spring.profiles.active=data-download");
		arguments.addAll(Arrays.asList(options));
		return invoke(arguments.toArray(String[]::new));
	}

	private Result invoke(String... args) {
		var output = new ByteArrayOutputStream();
		var error = new ByteArrayOutputStream();
		int exitCode = DataDownloadCommand.execute(args,
				new PrintStream(output, true, StandardCharsets.UTF_8),
				new PrintStream(error, true, StandardCharsets.UTF_8));
		return new Result(exitCode, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
	}

	private void assertInputError(Result result) {
		assertThat(result.exitCode()).isEqualTo(2);
		assertThat(result.output()).isEmpty();
		assertThat(result.error()).contains("수집 명령 오류");
	}

	private record Result(int exitCode, String output, String error) {}
}
