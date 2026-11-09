package com.pickplace.backend.ingestion.download;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.pickplace.backend.PickplaceBackendApplication;
import com.pickplace.backend.ingestion.download.cli.NationalDataDownloadCommand;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class NationalDataDownloadCommandTests {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"POPULATION_MONTH", "STORE_SNAPSHOT"})
    void startsWithoutDatabaseWebServerOrAnyOutputFiles(String dataset, CapturedOutput output) throws Exception {
        Path target = temporary.resolve("raw");
        PickplaceBackendApplication.main(new String[] {"--spring.profiles.active=national-data-download", "--dataset=" + dataset,
                "--month=202609", "--output=" + target, "--plan-only"});
        var plan = JsonMapper.builder().build().readTree(output.getOut());
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        assertThat(plan.path("periodKey").asText()).isEqualTo("202609");
        assertThat(plan.path("networkRequestsExecuted").asInt()).isZero();
        assertThat(output.getOut()).doesNotContain("Flyway", "HikariPool", "Tomcat", "Starting Pickplace");
        assertThat(Files.exists(target)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"202600", "202613", "20262", "202609 ", "202610"})
    void rejectsMalformedOrUnverifiedMonth(String month) {
        assertThat(execute("--dataset=POPULATION_MONTH", "--month=" + month)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--quarter=20262", "--download=true", "--month=202609", "--source-file=missing",
            "--source-metadata=missing", "--unknown=1"})
    void rejectsUndefinedDuplicateOrIncompleteOptions(String option) {
        assertThat(execute("--dataset=POPULATION_MONTH", "--month=202609", option)).isEqualTo(2);
    }

    @Test
    void rejectsMixedModesAndProfiles() {
        assertThat(execute("--dataset=POPULATION_MONTH", "--month=202609", "--download", "--plan-only")).isEqualTo(2);
        assertThat(execute("--dataset=RESIDENT", "--month=202609")).isEqualTo(2);
        assertThat(NationalDataDownloadCommand.execute(new String[] {"--spring.profiles.active=local,national-data-download", "--dataset=POPULATION_MONTH", "--month=202609"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }

    private int execute(String... args) {
        var all = new ArrayList<>(Arrays.asList(args));
        all.add("--spring.profiles.active=national-data-download");
        return NationalDataDownloadCommand.execute(all.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void rejectsRetiredSeoulProfileBeforeNormalApplicationBootstrap() {
        String[] args = {"--spring.profiles.active=data-download", "--dataset=RESIDENT", "--quarter=20262"};
        assertThat(NationalDataDownloadCommand.isRequested(args)).isTrue();
        var error = new ByteArrayOutputStream();
        assertThat(NationalDataDownloadCommand.execute(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(error))).isEqualTo(2);
        assertThat(error.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("폐기", "national-data-download");
    }
}
