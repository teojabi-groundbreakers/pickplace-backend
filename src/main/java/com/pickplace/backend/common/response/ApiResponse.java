package com.pickplace.backend.common.response;

public record ApiResponse<T>(T data, Meta meta) {

	// data를 공통 성공 응답 {data, meta} 형식으로 감싼다.
	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(data, Meta.current());
	}

}
