package com.pickplace.backend.common.response;

import java.util.List;

public record ListResponse<T>(List<T> items) {

	// 목록을 공통 목록 응답 {items: [...]} 형식으로 감싼다. 결과가 없으면 빈 배열이다.
	public static <T> ListResponse<T> of(List<T> items) {
		return new ListResponse<>(List.copyOf(items));
	}

}
