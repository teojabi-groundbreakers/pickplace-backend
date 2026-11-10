package com.pickplace.backend.ingestion.statistics.cli;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;
import com.pickplace.backend.ingestion.statistics.service.NationalStatisticsImporter;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher;
import com.pickplace.backend.ingestion.statistics.service.NationalSummaryPublisher.Expected;
import com.pickplace.backend.ingestion.statistics.service.StatisticsBatchService;
import com.pickplace.backend.ingestion.statistics.service.StatisticsInputLoader;

/** DB 쓰기 명령의 진입점이다. 대상 DB는 이 프로필 전용 환경변수로 명시하며 API 서버는 띄우지 않는다. */
@Component
@Profile("national-statistics")
public class NationalStatisticsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(NationalStatisticsRunner.class);
    // 필수 값이 여러 개 빠졌어도 같은 순서로 안내하도록 옵션 정의 순서를 유지한다.
    private static final Map<String, List<String>> ACTIONS = Map.of(
            "import", List.of("input", "raw", "metadata", "reference"),
            "prepare-summary", List.of("version", "population-batch-id", "store-batch-id"),
            "approve-summary", List.of("version", "actor"),
            "publish-month", List.of("version", "actor", "reason", "expected-month-revision", "expected-month-summary-id", "sync-default", "expected-default-revision", "expected-default-summary-id"),
            "rollback-month", List.of("version", "actor", "reason", "expected-month-revision", "expected-month-summary-id", "sync-default", "expected-default-revision", "expected-default-summary-id"),
            "set-default", List.of("version", "actor", "reason", "expected-default-revision", "expected-default-summary-id"),
            "recover-batch", List.of("batch-id", "expected-status", "actor", "reason"),
            "status", List.of());
    private final StatisticsInputLoader loader;
    private final NationalStatisticsImporter importer;
    private final NationalSummaryPublisher publisher;
    private final StatisticsBatchService batches;

    public NationalStatisticsRunner(StatisticsInputLoader loader, NationalStatisticsImporter importer,
            NationalSummaryPublisher publisher, StatisticsBatchService batches) {
        this.loader = loader; this.importer = importer; this.publisher = publisher; this.batches = batches;
    }

    public static boolean isRequested(String[] args) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return Arrays.stream(env.getProperty("spring.profiles.active", "").split(",")).map(String::trim).anyMatch("national-statistics"::equals);
    }

    // main에서도 호출해 프로필 혼합·미정의 옵션이 다른 Runner를 실행하거나 DB를 초기화하기 전에 거부되게 한다.
    public static void validateArguments(ApplicationArguments args) {
        require(value(args, "spring.profiles.active").equals("national-statistics"), "national-statistics 프로필만 단독으로 지정하세요.");
        String action = value(args, "action");
        require(ACTIONS.containsKey(action) && args.getNonOptionArgs().isEmpty(), "알 수 없는 작업 또는 이름 없는 인자입니다.");
        var allowed = new HashSet<>(ACTIONS.get(action)); allowed.addAll(Set.of("spring.profiles.active", "action", "confirm"));
        require(allowed.containsAll(args.getOptionNames()), "정의되지 않은 옵션입니다.");
        for (String name : args.getOptionNames()) {
            if (name.equals("sync-default")) require(args.getOptionValues(name).isEmpty(), "sync-default는 값을 받지 않습니다.");
            else value(args, name);
        }
        var conditional = Set.of("expected-month-summary-id", "expected-default-revision", "expected-default-summary-id", "sync-default");
        for (String name : ACTIONS.get(action)) if (!conditional.contains(name)) value(args, name);
        for (String id : List.of("batch-id", "population-batch-id", "store-batch-id")) if (args.containsOption(id)) positive(args, id);
        if (action.equals("set-default")) expected(args, "default");
        if (action.equals("publish-month") || action.equals("rollback-month")) {
            expected(args, "month");
            if (args.containsOption("sync-default")) expected(args, "default");
        }
        if (!action.equals("status")) require(value(args, "confirm").equals("true"), "DB 쓰기 작업에는 --confirm=true가 필요합니다.");
        if ((action.equals("publish-month") || action.equals("rollback-month")) && !args.containsOption("sync-default"))
            require(!args.containsOption("expected-default-revision") && !args.containsOption("expected-default-summary-id"), "기본 동기화를 요청하지 않았습니다.");
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        validateArguments(args);
        String action = value(args, "action");
        switch (action) {
            case "import" -> log.info("Statistics result: {}", importer.importData(loader.load(path(args, "input"), path(args, "raw"), path(args, "metadata"), path(args, "reference"))));
            case "prepare-summary" -> log.info("Summary READY id={}", publisher.prepare(value(args, "version"), positive(args, "population-batch-id"), positive(args, "store-batch-id")));
            case "approve-summary" -> publisher.approve(value(args, "version"), value(args, "actor"));
            case "publish-month", "rollback-month" -> log.info("MONTH publication revision={}", publisher.publishMonth(value(args, "version"),
                    expected(args, "month"), args.containsOption("sync-default") ? expected(args, "default") : null,
                    value(args, "actor"), value(args, "reason"), action.equals("rollback-month")));
            case "set-default" -> log.info("DEFAULT publication revision={}", publisher.setDefault(value(args, "version"), expected(args, "default"), value(args, "actor"), value(args, "reason")));
            case "recover-batch" -> batches.recover(positive(args, "batch-id"), value(args, "expected-status"), value(args, "actor"), value(args, "reason"));
            case "status" -> log.info("Current summary pointers: {}", publisher.pointers());
            default -> throw new IllegalArgumentException("알 수 없는 작업입니다.");
        }
    }

    private static Expected expected(ApplicationArguments args, String type) {
        long revision = Long.parseLong(value(args, "expected-" + type + "-revision"));
        String idKey = "expected-" + type + "-summary-id";
        require(revision >= 0 && (revision == 0 ? !args.containsOption(idKey) : args.containsOption(idKey)), "revision과 기존 묶음 ID를 함께 지정하세요. 첫 게시는 revision=0만 지정합니다.");
        return new Expected(revision, revision == 0 ? null : positive(args, idKey));
    }
    private static Path path(ApplicationArguments args, String key) { return Path.of(value(args, key)); }
    private static long positive(ApplicationArguments args, String key) { long n = Long.parseLong(value(args, key)); require(n > 0, "양의 ID가 필요합니다."); return n; }
    private static String value(ApplicationArguments args, String key) {
        var values = args.getOptionValues(key);
        require(values != null && values.size() == 1 && !values.getFirst().isBlank(), "--" + key + "에 값 하나가 필요합니다.");
        return values.getFirst();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
