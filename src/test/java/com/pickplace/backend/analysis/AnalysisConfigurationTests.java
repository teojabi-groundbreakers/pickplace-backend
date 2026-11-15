package com.pickplace.backend.analysis;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 분석 기준일이 한국 날짜를 따르는지 확인한다. */
class AnalysisConfigurationTests {

    @Test
    void analysisClockUsesKoreanDateAtUtcQuarterBoundary() {
        Clock configured = new AnalysisConfiguration().analysisClock();

        assertThat(configured.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        // UTC에서는 3월 31일이어도 한국에서는 다음 분기 첫날이다.
        Clock boundary = Clock.fixed(Instant.parse("2026-03-31T15:30:00Z"), configured.getZone());
        assertThat(LocalDate.now(boundary)).isEqualTo(LocalDate.of(2026, 4, 1));
    }
}
