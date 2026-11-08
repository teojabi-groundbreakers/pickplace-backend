package com.pickplace.backend.reference.nationwide;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("national-reference")
public class NationalReferenceRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(NationalReferenceRunner.class);
    private final NationalReferenceLoader loader;
    private final NationalReferenceImporter importer;
    private final NationalReferencePublisher publisher;

    public NationalReferenceRunner(NationalReferenceLoader loader, NationalReferenceImporter importer, NationalReferencePublisher publisher) {
        this.loader = loader;
        this.importer = importer;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!option(args, "confirm").equals("true")) throw new IllegalArgumentException("Explicit --confirm=true required");
        switch (option(args, "action")) {
            case "import" -> {
                var result = importer.importSnapshot(loader.load(Path.of(option(args, "snapshot")), option(args, "sha256")));
                log.info("Nationwide reference import: releaseId={}, classificationId={}, inserted={}",
                        result.releaseId(), result.classificationId(), result.inserted());
            }
            case "approve" -> publisher.approve(option(args, "version"), option(args, "actor"));
            case "publish", "rollback" -> {
                long revision = publisher.publish(option(args, "version"), Long.parseLong(option(args, "expected-revision")),
                        option(args, "actor"), option(args, "reason"), option(args, "action").equals("rollback"));
                log.info("Nationwide reference publication: revision={}", revision);
            }
            default -> throw new IllegalArgumentException("Unknown national reference action");
        }
    }

    private String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Exactly one --" + name + " is required");
        }
        return values.getFirst();
    }
}
