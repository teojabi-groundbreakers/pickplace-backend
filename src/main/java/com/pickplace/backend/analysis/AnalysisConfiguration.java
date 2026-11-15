package com.pickplace.backend.analysis;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pickplace.backend.ai.AiPredictClient;

/** 승인된 조회 결과와 AI 호출기를 연결한다. */
@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {

    @Bean
    Clock analysisClock() {
        // 한국 서비스의 날짜 경계에서 전날 분기가 선택되지 않도록 KST를 사용한다.
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    AnalysisOrchestrator analysisOrchestrator(AnalysisOrchestrator.SnapshotReader reader,
            AiPredictClient client, Clock analysisClock) {
        return new AnalysisOrchestrator(reader, client::predict, analysisClock);
    }
}
