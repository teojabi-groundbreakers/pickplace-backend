package com.pickplace.backend.ingestion.download.cli;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

import com.pickplace.backend.ingestion.download.model.DownloadRequest;
import com.pickplace.backend.ingestion.download.service.DownloadPlanService;
import com.pickplace.backend.ingestion.download.service.SourceCatalogLoader;

// 수집 명령을 받아 실행 계획을 출력한다.
public final class DataDownloadCommand {

	private static final String PROFILE = "data-download";
	private static final Set<String> OPTIONS =
			Set.of("spring.profiles.active", "dataset", "quarter", "output", "plan-only");

	private DataDownloadCommand() {}

	// 다운로드 모드로 실행했는지 확인한다.
	public static boolean isRequested(String[] args) {
		return profiles(args).contains(PROFILE);
	}

	// 입력을 검사하고 실행 결과에 맞는 종료 코드를 돌려준다.
	public static int execute(String[] args, PrintStream output, PrintStream error) {
		DownloadRequest request;
		try {
			request = parse(args);
		} catch (IllegalArgumentException exception) {
			error.println("수집 명령 오류: " + exception.getMessage());
			error.println("사용 예: --spring.profiles.active=data-download --dataset=RESIDENT --quarter=20262 --output=data/raw");
			return 2;
		}

		var mapper = JsonMapper.builder().build();
		try {
			var loader = new SourceCatalogLoader(mapper);
			var plan = new DownloadPlanService(loader.load(), loader).plan(request);
			output.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
			return 0;
		} catch (IllegalArgumentException exception) {
			error.println("수집 명령 오류: " + exception.getMessage());
			return 2;
		} catch (RuntimeException exception) {
			error.println("수집 계획을 생성할 수 없습니다: " + exception.getMessage());
			return 3;
		}
	}

	// 명령줄 옵션을 수집 요청으로 바꾼다.
	private static DownloadRequest parse(String[] args) {
		var arguments = new DefaultApplicationArguments(args);
		if (!arguments.getNonOptionArgs().isEmpty() || !OPTIONS.containsAll(arguments.getOptionNames())) {
			throw new IllegalArgumentException("알 수 없는 인자입니다. dataset·quarter·output·plan-only 옵션을 확인하세요.");
		}
		for (String name : arguments.getOptionNames()) {
			if (arguments.getOptionValues(name).size() > 1) {
				throw new IllegalArgumentException("옵션을 중복 지정할 수 없습니다: --" + name);
			}
		}
		if (!profiles(args).equals(List.of(PROFILE))) {
			throw new IllegalArgumentException("data-download 프로필만 단독으로 지정하세요.");
		}
		if (arguments.containsOption("plan-only") && !arguments.getOptionValues("plan-only").isEmpty()) {
			throw new IllegalArgumentException("--plan-only는 값을 받지 않는 플래그입니다.");
		}
		String dataset = value(arguments, "dataset", null);
		String quarter = value(arguments, "quarter", null);
		String directory = value(arguments, "output", "data/raw");
		try {
			return new DownloadRequest(dataset, quarter, Path.of(directory));
		} catch (InvalidPathException exception) {
			throw new IllegalArgumentException("--output 경로 형식이 올바르지 않습니다.");
		}
	}

	// 옵션 값을 하나만 받고 생략하면 기본값을 쓴다.
	private static String value(DefaultApplicationArguments arguments, String name, String defaultValue) {
		List<String> values = arguments.getOptionValues(name);
		if (values == null) {
			if (defaultValue != null) {
				return defaultValue;
			}
			throw new IllegalArgumentException("--" + name + "은 필수입니다.");
		}
		if (values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException("--" + name + "에는 값을 하나 지정해야 합니다.");
		}
		return values.getFirst();
	}

	// 명령줄과 환경변수에서 실행 프로필을 읽는다.
	private static List<String> profiles(String[] args) {
		var environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
		return Arrays.stream(environment.getProperty("spring.profiles.active", "").split(","))
				.map(String::trim).filter(value -> !value.isEmpty()).toList();
	}
}
