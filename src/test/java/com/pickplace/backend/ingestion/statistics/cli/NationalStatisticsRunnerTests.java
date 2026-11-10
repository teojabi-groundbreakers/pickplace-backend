package com.pickplace.backend.ingestion.statistics.cli;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import com.pickplace.backend.PickplaceBackendApplication;
import static org.assertj.core.api.Assertions.*;

class NationalStatisticsRunnerTests {
    private static String[] args(String... extra) {
        var values = new java.util.ArrayList<String>(); values.add("--spring.profiles.active=national-statistics"); values.addAll(java.util.List.of(extra));
        return values.toArray(String[]::new);
    }
    private static void validate(String... extra) { NationalStatisticsRunner.validateArguments(new DefaultApplicationArguments(args(extra))); }

    @Test void statusIsReadOnlyAndNeedsNoConfirmation() { assertThatCode(() -> validate("--action=status")).doesNotThrowAnyException(); }
    @Test void writeRequiresExplicitConfirmation() {
        assertThatThrownBy(() -> validate("--action=approve-summary", "--version=v1", "--actor=test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingInputIsRejectedBeforeSpringBootstrap() {
        assertThatThrownBy(() -> PickplaceBackendApplication.main(args("--action=import", "--confirm=true")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--input");
    }
    @Test void mixedProfileCannotStartOtherDatabaseRunners() {
        assertThatThrownBy(() -> NationalStatisticsRunner.validateArguments(new DefaultApplicationArguments(
                "--spring.profiles.active=national-statistics,national-reference", "--action=status"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownAndRepeatedOptionsAreRejected() {
        assertThatThrownBy(() -> validate("--action=status", "--unknown=true")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("--action=status", "--action=status")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void initialPublishUsesZeroRevisionWithoutOldId() {
        assertThatCode(() -> validate("--action=publish-month", "--confirm=true", "--version=v1", "--actor=test", "--reason=initial", "--expected-month-revision=0")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate("--action=publish-month", "--confirm=true", "--version=v1", "--actor=test", "--reason=initial", "--expected-month-revision=0", "--expected-month-summary-id=1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void existingRevisionRequiresOldId() {
        assertThatThrownBy(() -> validate("--action=set-default", "--confirm=true", "--version=v1", "--actor=test", "--reason=select", "--expected-default-revision=1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void defaultExpectationsWithoutSyncAreRejected() {
        assertThatThrownBy(() -> validate("--action=publish-month", "--confirm=true", "--version=v1", "--actor=test", "--reason=fix",
                "--expected-month-revision=1", "--expected-month-summary-id=1", "--expected-default-revision=0")).isInstanceOf(IllegalArgumentException.class);
    }
}
