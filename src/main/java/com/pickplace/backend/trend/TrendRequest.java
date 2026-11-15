package com.pickplace.backend.trend;

import java.util.List;

// 형식 검사를 통과한 추이 조회 조건이다. industryCode는 업종이 없는 자료면 null이다.
// quarters는 시작부터 종료까지의 모든 분기를 시간순으로 담는다.
record TrendRequest(
		String regionCode,
		TrendDataset dataset,
		String industryCode,
		List<String> quarters) {

	// DB의 source_industry_code와 비교할 값이다. 업종이 없는 자료는 빈 문자열로 저장돼 있다.
	String storedIndustryCode() {
		return industryCode == null ? "" : industryCode;
	}

}
