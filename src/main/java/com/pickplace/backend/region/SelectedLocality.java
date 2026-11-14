package com.pickplace.backend.region;

// 현재 지역 선택 게시본에서 확인한 읍·면·동이다. releaseId는 그 확인에 쓴 게시 릴리스 번호다.
public record SelectedLocality(
		long releaseId,
		String regionCode,
		String regionName) {
}
