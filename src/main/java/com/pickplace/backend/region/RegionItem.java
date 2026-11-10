package com.pickplace.backend.region;

// 지역 목록 응답의 한 줄이다. 코드는 공식 행정기관 코드(H10, 숫자 10자리 문자열)다.
// parentCode는 공식 행정 계층이 아니라 화면 선택 계층의 부모다. 시·도는 부모가 없어 null이다.
public record RegionItem(
		String regionCode,
		String regionName,
		SelectionLevel selectionLevel,
		String parentCode,
		boolean autoSelect) {
}
