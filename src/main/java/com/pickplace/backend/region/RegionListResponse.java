package com.pickplace.backend.region;

import com.pickplace.backend.common.response.ListResponse;
import com.pickplace.backend.common.web.RequestIds;

// 지역 목록 응답 {data: {items}, meta: {requestId, codeSystem, sourceReferenceOn, sourceFileVersion}}이다.
// 공통 Meta를 고치면 기존 API 응답까지 바뀌므로, 출처 정보가 필요한 지역 목록만 전용 meta를 쓴다.
public record RegionListResponse(ListResponse<RegionItem> data, Meta meta) {

	// 지역 코드 체계 이름이다. 모든 regionCode가 행정안전부 행정기관 코드(H10)라는 뜻이다.
	static final String CODE_SYSTEM = "MOIS_H10";

	// sourceReferenceOn(공식 자료 기준일)과 sourceFileVersion(공식 파일 버전)은 출처 설명용이다.
	// FE가 다음 요청에 다시 보내는 값이 아니다.
	public record Meta(String requestId, String codeSystem, String sourceReferenceOn, String sourceFileVersion) {
	}

	// 조회 결과를 공통 목록 형식과 출처 meta로 감싼다.
	public static RegionListResponse of(RegionList list) {
		PublishedRegionRelease release = list.release();
		// 날짜는 JSON 설정에 따라 숫자 배열로 바뀔 수 있어 "2026-10-30" 문자열로 고정한다. 기준일이 없으면 null이다.
		String referenceOn = release.sourceReferenceOn() == null ? null : release.sourceReferenceOn().toString();
		var meta = new Meta(RequestIds.current(), CODE_SYSTEM, referenceOn, release.sourceFileVersion());
		return new RegionListResponse(ListResponse.of(list.items()), meta);
	}

}
