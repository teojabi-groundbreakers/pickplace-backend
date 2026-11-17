package com.pickplace.backend.summary;

import org.springframework.stereotype.Service;

// 한 지역·업종의 인구·업소 현황을 만든다. 형식 검사·기준 고정·지역/업종 확인은 SummaryContextService가 하고,
// 여기서는 같은 읽기 시점(콜백) 안에서 근거를 읽어 판정한다.
@Service
public class SummaryService {

	private final SummaryContextService contexts;
	private final SummaryEvidenceReader evidence;
	private final MetricAvailabilityPolicy policy;

	public SummaryService(SummaryContextService contexts, SummaryEvidenceReader evidence, MetricAvailabilityPolicy policy) {
		this.contexts = contexts;
		this.evidence = evidence;
		this.policy = policy;
	}

	// 고정한 DEFAULT 묶음으로 두 지표를 판정해 돌려준다. 한 지표가 보류여도 다른 지표는 따로 판정한다.
	public SummaryResult getSummary(String regionCode, String businessTypeCode) {
		return contexts.withContext(regionCode, businessTypeCode, this::decide);
	}

	// 이미 고정한 조회 기준으로 두 지표를 판정한다. 반드시 withContext 콜백(같은 읽기 트랜잭션) 안에서 불러야 한다.
	// 분석 API가 자기 읽기 트랜잭션 안에서 현황 API와 같은 판정을 그대로 쓰도록 공개한다.
	public SummaryResult decide(SummaryReadContext context) {
		return new SummaryResult(context,
				policy.population(evidence.population(context)),
				policy.stores(context, evidence.stores(context)));
	}

	// 판정 결과와 그 근거가 된 조회 기준이다. 응답 변환에 필요한 출처·기준월이 context에 있다.
	public record SummaryResult(SummaryReadContext context, MetricDecision population, MetricDecision stores) {
	}

}
