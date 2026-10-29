package com.pickplace.backend;

import com.pickplace.backend.reference.ReferenceImportRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceImportCommandTests {

	@Test
	void refusesImportWithoutExplicitConfirmation() {
		// 확인 거부 경로는 로더나 DB에 접근하면 안 된다.
		var runner = new ReferenceImportRunner(null, null, false);
		assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("confirm=true");
	}
}
