package com.pickplace.backend.ingestion.detail.cli;

import static com.pickplace.backend.ingestion.detail.service.DetailInputLoader.require;

import com.pickplace.backend.ingestion.detail.service.*;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

@Component
@Profile("regional-detail")
public class RegionalDetailRunner implements ApplicationRunner {
    private static final Map<String, Set<String>> OPTIONS =
            Map.of(
                    "import",
                    Set.of("input"),
                    "import-catalog",
                    Set.of("catalog"),
                    "approve",
                    Set.of("batch-id", "actor"),
                    "publish",
                    Set.of(
                            "batch-id",
                            "period",
                            "expected-revision",
                            "previous-batch-id",
                            "actor",
                            "reason"),
                    "recover",
                    Set.of("batch-id", "actor", "reason"));
    private final DetailInputLoader loader;
    private final RegionalDetailImporter importer;
    private final DetailBatchService batches;
    private final DetailCatalogImporter catalogs;

    public RegionalDetailRunner(
            DetailInputLoader loader,
            RegionalDetailImporter importer,
            DetailBatchService batches,
            DetailCatalogImporter catalogs) {
        this.loader = loader;
        this.importer = importer;
        this.batches = batches;
        this.catalogs = catalogs;
    }

    public static boolean isRequested(String[] args) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return Arrays.stream(env.getProperty("spring.profiles.active", "").split(","))
                .map(String::trim)
                .anyMatch("regional-detail"::equals);
    }

    // DB 초기화 전에 프로필 혼합·미정의 인자·쓰기 확인 누락을 거절한다.
    public static void validateArguments(ApplicationArguments args) {
        require(
                value(args, "spring.profiles.active").equals("regional-detail"),
                "regional-detail 프로필만 단독 사용하세요.");
        String action = value(args, "action");
        require(OPTIONS.containsKey(action) && args.getNonOptionArgs().isEmpty(), "잘못된 작업입니다.");
        var allowed = new HashSet<>(OPTIONS.get(action));
        allowed.addAll(Set.of("action", "spring.profiles.active", "confirm"));
        require(allowed.containsAll(args.getOptionNames()), "미정의 옵션입니다.");
        for (String name : args.getOptionNames()) value(args, name);
        for (String name : OPTIONS.get(action))
            if (!name.equals("previous-batch-id")) value(args, name);
        require(value(args, "confirm").equals("true"), "DB 쓰기에는 --confirm=true가 필요합니다.");
        if (args.containsOption("batch-id"))
            require(Long.parseLong(value(args, "batch-id")) > 0, "배치 ID 오류");
        if (action.equals("publish")) {
            long revision = Long.parseLong(value(args, "expected-revision"));
            require(
                    revision >= 0
                            && (revision == 0
                                    ? !args.containsOption("previous-batch-id")
                                    : args.containsOption("previous-batch-id")),
                    "기존 revision·배치를 함께 지정하세요.");
            require(value(args, "period").matches("[0-9]{4}[1-4]"), "분기 형식 오류");
            if (args.containsOption("previous-batch-id"))
                require(Long.parseLong(value(args, "previous-batch-id")) > 0, "기존 배치 ID 오류");
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateArguments(args);
        switch (value(args, "action")) {
            case "import-catalog" -> catalogs.importCatalog(Path.of(value(args, "catalog")));
            case "import" ->
                    System.out.println(
                            importer.importData(loader.load(Path.of(value(args, "input")))));
            case "approve" -> batches.approve(id(args), value(args, "actor"));
            case "recover" ->
                    batches.recover(id(args), value(args, "actor"), value(args, "reason"));
            case "publish" ->
                    System.out.println(
                            "revision="
                                    + batches.publish(
                                            id(args),
                                            value(args, "period"),
                                            Long.parseLong(value(args, "expected-revision")),
                                            args.containsOption("previous-batch-id")
                                                    ? Long.valueOf(value(args, "previous-batch-id"))
                                                    : null,
                                            value(args, "actor"),
                                            value(args, "reason")));
            default -> throw new IllegalArgumentException("잘못된 작업");
        }
    }

    private static long id(ApplicationArguments args) {
        return Long.parseLong(value(args, "batch-id"));
    }

    private static String value(ApplicationArguments args, String key) {
        var values = args.getOptionValues(key);
        require(
                values != null && values.size() == 1 && !values.getFirst().isBlank(),
                "--" + key + " 값 하나 필요");
        return values.getFirst();
    }
}
