package com.pickplace.backend.region;

import java.time.LocalDate;

// 지금 서비스에 게시된 전국 지역 기준정보 버전과 출처 정보다.
// 한 요청은 이 releaseId 하나로만 조회해, 요청 도중 게시가 바뀌어도 서로 다른 버전이 섞이지 않게 한다.
public record PublishedRegionRelease(
		long releaseId,
		String releaseStatus,
		LocalDate sourceReferenceOn,
		String sourceFileVersion) {
}
