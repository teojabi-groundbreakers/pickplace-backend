package com.pickplace.backend.common.response;

import java.util.List;

import com.pickplace.backend.common.error.ErrorCode;

public record ApiErrorResponse(ErrorBody error, Meta meta) {

	public record ErrorBody(String code, String message, List<ErrorDetail> details) {
	}

	// 오류 코드와 세부 원인으로 공통 오류 응답 {error, meta}를 만든다.
	public static ApiErrorResponse of(ErrorCode errorCode, List<ErrorDetail> details) {
		// FE가 null 검사를 따로 하지 않도록 details는 비어 있어도 배열로 보낸다.
		var body = new ErrorBody(errorCode.name(), errorCode.message(), details == null ? List.of() : List.copyOf(details));
		return new ApiErrorResponse(body, Meta.current());
	}

}
