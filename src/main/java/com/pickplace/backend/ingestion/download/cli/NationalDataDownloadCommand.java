package com.pickplace.backend.ingestion.download.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;
import com.pickplace.backend.ingestion.download.service.NationalDownloadService;
import com.pickplace.backend.ingestion.download.service.NationalSourceCatalogLoader;

public final class NationalDataDownloadCommand {
    private static final String PROFILE = "national-data-download";
    private static final Set<String> OPTIONS = Set.of("spring.profiles.active", "dataset", "month", "output",
            "plan-only", "download", "source-file", "source-metadata");

    private NationalDataDownloadCommand() {}

    public static boolean isRequested(String[] args) { return profiles(args).contains(PROFILE); }

    public static int execute(String[] args, PrintStream output, PrintStream error) {
        try {
            var arguments = new DefaultApplicationArguments(args);
            if (!profiles(args).equals(List.of(PROFILE)) || !arguments.getNonOptionArgs().isEmpty()
                    || !OPTIONS.containsAll(arguments.getOptionNames())) throw new IllegalArgumentException("national-data-download 프로필과 정의된 옵션만 지정하세요.");
            for (String key : arguments.getOptionNames()) {
                if (arguments.getOptionValues(key).size() > 1) throw new IllegalArgumentException("옵션 중복: " + key);
            }
            for (String flag : List.of("plan-only", "download")) {
                if (arguments.containsOption(flag) && !arguments.getOptionValues(flag).isEmpty()) throw new IllegalArgumentException("--" + flag + "는 값을 받지 않습니다.");
            }
            boolean local = arguments.containsOption("source-file"), metadata = arguments.containsOption("source-metadata");
            boolean download = arguments.containsOption("download"), plan = arguments.containsOption("plan-only");
            if (local != metadata || (local && (download || plan)) || (download && plan)) throw new IllegalArgumentException("다운로드·기존 원본 재사용·계획 모드는 함께 사용할 수 없습니다. 재사용은 source-file/source-metadata가 모두 필요합니다.");
            String dataset = value(arguments, "dataset", null).toUpperCase(Locale.ROOT);
            String month = value(arguments, "month", null);
            if (!month.matches("[1-9][0-9]{3}(0[1-9]|1[0-2])")) throw new IllegalArgumentException("--month는 YYYYMM 형식입니다.");
            Path directory = Path.of(value(arguments, "output", "data/raw")).toAbsolutePath().normalize();
            Path sourceFile = local ? Path.of(value(arguments, "source-file", null)) : null;
            Path sourceMetadata = local ? Path.of(value(arguments, "source-metadata", null)) : null;
            var mapper = JsonMapper.builder().build();
            var source = new NationalSourceCatalogLoader(mapper).find(dataset, month);
            Object result;
            if (download || local) {
                result = new NationalDownloadService(mapper).acquire(source, directory, sourceFile, sourceMetadata);
            } else {
                result = Map.of("status", "PLANNED", "mode", "PLAN_ONLY", "dataset", dataset, "periodType", source.periodType(),
                        "periodKey", month, "downloadUrl", source.downloadUrl(), "sha256", source.sha256(),
                        "expectedRows", source.expectedRows(), "outputDirectory", directory.toString(), "networkRequestsExecuted", 0);
            }
            output.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return 0;
        } catch (IllegalArgumentException exception) {
            error.println("전국 수집 명령 오류: " + exception.getMessage()); return 2;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); error.println("전국 원본 수집이 중단되었습니다."); return 3;
        } catch (IOException | RuntimeException exception) {
            error.println("전국 원본 수집·검증 실패: " + exception.getMessage()); return 3;
        }
    }

    private static String value(DefaultApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        if (values == null && fallback != null) return fallback;
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) throw new IllegalArgumentException("--" + name + "에 값 하나가 필요합니다.");
        return values.getFirst();
    }

    private static List<String> profiles(String[] args) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return Arrays.stream(env.getProperty("spring.profiles.active", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
