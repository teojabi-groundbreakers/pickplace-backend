package com.pickplace.backend.region;

// 화면에서 고르는 단계다. 법적 행정구역 유형(시·군·구·읍·면·동)과 다르다.
// 값 이름을 DB region_navigation.selection_role 값과 똑같이 맞춰 SQL 조건에 그대로 쓴다.
public enum SelectionLevel {

	PROVINCE,
	GROUP,
	LOCALITY

}
