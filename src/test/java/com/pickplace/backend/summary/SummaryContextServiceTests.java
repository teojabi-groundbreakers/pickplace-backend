package com.pickplace.backend.summary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import com.pickplace.backend.businesstype.BusinessTypeQueryRepository;
import com.pickplace.backend.common.error.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SummaryContextServiceTests {
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final SummaryContextService service = new SummaryContextService(mock(BusinessTypeQueryRepository.class),
            mock(SummaryContextRepository.class), JsonMapper.builder().build(), transactions);

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"11110515","１１１１０５１５００","1111051500 ","111105150A"})
    void invalidRegionIsRejectedBeforeOpeningDatabase(String region) {
        assertThatThrownBy(() -> service.withContext(region,"I21201", c -> c))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REGION_CODE));
        verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(strings={" ","i21201","CS100010","I21201 ","I１２２０１"})
    void neverNormalizesIndustryCodes(String industry) {
        assertThatThrownBy(() -> service.withContext("1111051500",industry,c -> c)).isInstanceOf(ApiException.class);
        verifyNoInteractions(transactions);
    }
    @ParameterizedTest @NullAndEmptySource void missingBusinessCodeHasPriority(String industry) {
        assertThatThrownBy(() -> service.withContext("bad",industry,c -> c)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST); assertThat(e.details().getFirst().reason()).isEqualTo("REQUIRED");
        });
        verifyNoInteractions(transactions);
    }
    @Test void connectionFailureIsNotMissingStatistics() {
        when(transactions.getTransaction(any())).thenThrow(new CannotCreateTransactionException("test database unavailable"));
        assertThatThrownBy(() -> service.withContext("1111051500","I21201",c -> c)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DATA_SERVICE_UNAVAILABLE));
    }
}
