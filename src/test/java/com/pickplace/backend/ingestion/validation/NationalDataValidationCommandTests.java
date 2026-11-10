package com.pickplace.backend.ingestion.validation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import com.pickplace.backend.PickplaceBackendApplication;
import com.pickplace.backend.ingestion.validation.cli.NationalDataValidationCommand;
import static org.assertj.core.api.Assertions.*;

class NationalDataValidationCommandTests {
    @Test void detectsOnlyExplicitValidationProfile() {
        assertThat(NationalDataValidationCommand.isRequested(new String[]{"--spring.profiles.active=national-data-validation"})).isTrue();
        assertThat(NationalDataValidationCommand.isRequested(new String[]{"--spring.profiles.active=local"})).isFalse();
    }
    @Test void undefinedOptionFailsBeforeAnySpringOrDatabaseBootstrap() {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = NationalDataValidationCommand.execute(new String[]{"--spring.profiles.active=national-data-validation", "--unknown=true"},
                new PrintStream(out), new PrintStream(err));
        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("정의된 옵션");
    }
    @Test void mixedProfileIsRejected() {
        int code = NationalDataValidationCommand.execute(new String[]{"--spring.profiles.active=national-data-validation,local"},
                System.out, new PrintStream(new ByteArrayOutputStream()));
        assertThat(code).isEqualTo(2);
    }
    @Test void applicationDispatchesValidationWithoutStartingServer() {
        try (var mocked = org.mockito.Mockito.mockStatic(NationalDataValidationCommand.class)) {
            String[] args = {"--spring.profiles.active=national-data-validation"};
            mocked.when(() -> NationalDataValidationCommand.isRequested(args)).thenReturn(true);
            mocked.when(() -> NationalDataValidationCommand.execute(args, System.out, System.err)).thenReturn(0);
            PickplaceBackendApplication.main(args);
            mocked.verify(() -> NationalDataValidationCommand.execute(args, System.out, System.err));
        }
    }
}
