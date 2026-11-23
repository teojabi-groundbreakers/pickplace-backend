package com.pickplace.backend.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("ai-scope-review")
class AiScopeReviewRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AiScopeReviewRunner.class);

    private final AiScopeReviewStager stager;
    private final JsonMapper mapper;

    AiScopeReviewRunner(AiScopeReviewStager stager, JsonMapper mapper) {
        this.stager = stager;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        var confirmation = args.getOptionValues("confirm");
        var files = args.getOptionValues("scope-review");
        if (confirmation == null || confirmation.size() != 1 || !"true".equals(confirmation.getFirst())
                || files == null || files.size() != 1 || files.getFirst().isBlank()) {
            throw new IllegalArgumentException("Exactly one --confirm=true and --scope-review are required");
        }
        try (var input = Files.newInputStream(Path.of(files.getFirst()))) {
            var candidate = mapper.readValue(input, AiScopeReviewStager.Candidate.class);
            var action = args.getOptionValues("approve-by");
            if (action == null) {
                long id = stager.stage(candidate);
                LOG.info("AI scope review recorded: reviewId={}, version={}", id, candidate.reviewVersion());
            } else {
                if (action.size() != 1 || action.getFirst().isBlank()) {
                    throw new IllegalArgumentException("Exactly one reviewer is required for --approve-by");
                }
                long id = stager.approve(candidate, action.getFirst());
                LOG.info("AI scope review approved: reviewId={}, version={}", id, candidate.reviewVersion());
            }
        }
    }
}
