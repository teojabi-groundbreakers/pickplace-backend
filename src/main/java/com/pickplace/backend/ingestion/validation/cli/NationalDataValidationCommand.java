package com.pickplace.backend.ingestion.validation.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import com.pickplace.backend.ingestion.download.service.NationalSourceCatalogLoader;
import com.pickplace.backend.ingestion.validation.service.NationalValidationService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전국 원본의 정제·검증 명령을 실행한다.
 * 애플리케이션 진입점에서 먼저 호출하므로 Spring 컨텍스트와 DB 연결을 만들지 않는다.
 */
public final class NationalDataValidationCommand {
    private static final String PROFILE = "national-data-validation";
    private static final Set<String> OPTIONS = Set.of("spring.profiles.active", "dataset", "month", "raw",
            "metadata", "reference", "reference-sha256", "output");
    private NationalDataValidationCommand() {}

    public static boolean isRequested(String[] args) { return profiles(args).contains(PROFILE); }

    /** 입력 오류는 2, 파일 처리·검증 실행 실패는 3을 반환한다. 개별 보류 행은 보고서에 남긴다. */
    public static int execute(String[] args, PrintStream output, PrintStream error) {
        try {
            var arguments = new DefaultApplicationArguments(args);
            // local 같은 서버용 프로필과 섞이거나 옵션에 오타가 있으면 실행 전에 중단한다.
            if (!profiles(args).equals(List.of(PROFILE)) || !arguments.getNonOptionArgs().isEmpty()
                    || !OPTIONS.containsAll(arguments.getOptionNames()))
                throw new IllegalArgumentException("national-data-validation 프로필과 정의된 옵션만 지정하세요.");
            for (String option : arguments.getOptionNames()) value(arguments, option);
            String checksum = value(arguments, "reference-sha256");
            if (!checksum.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("reference-sha256 형식 오류");
            var mapper = JsonMapper.builder().build();
            // 카탈로그에 등록된 배포본만 처리한다. 최신 월이나 다른 파일로 자동 대체하지 않는다.
            var source = new NationalSourceCatalogLoader(mapper).find(value(arguments, "dataset"), value(arguments, "month"));
            var result = new NationalValidationService(mapper).validate(source, path(arguments, "raw"),
                    path(arguments, "metadata"), path(arguments, "reference"), checksum, path(arguments, "output"));
            // 상세 지역·이슈 목록은 파일에 두고 콘솔에는 실행 결과와 보고서 위치만 보여 준다.
            output.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "status", result.status(), "dataset", result.dataset(), "sourceRows", result.sourceRows(),
                    "dispositions", result.dispositions(), "blocksNewPublication", result.blocksNewPublication(),
                    "report", path(arguments, "output").toAbsolutePath().normalize().resolve("report.json").toString())));
            return 0;
        } catch (IllegalArgumentException exception) {
            error.println("전국 검증 명령 오류: " + exception.getMessage()); return 2;
        } catch (IOException | RuntimeException exception) {
            error.println("전국 데이터 검증 실패: " + exception.getMessage()); return 3;
        }
    }

    private static Path path(DefaultApplicationArguments arguments, String name) { return Path.of(value(arguments, name)); }
    private static String value(DefaultApplicationArguments arguments, String name) {
        // 누락·빈 값·중복 옵션을 함께 검사해 어느 값을 사용할지 임의로 결정하지 않는다.
        var values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank())
            throw new IllegalArgumentException("--" + name + "에 값 하나가 필요합니다.");
        return values.getFirst();
    }
    private static List<String> profiles(String[] args) {
        // Spring 부팅 전에 확인해야 하므로 명령행·환경변수에서 직접 프로필을 읽는다.
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return Arrays.stream(env.getProperty("spring.profiles.active", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
