package com.pickplace.backend.common.error;

import java.util.List;

import com.pickplace.backend.common.response.ErrorDetail;

public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;
	private final List<ErrorDetail> details;

	public ApiException(ErrorCode errorCode) {
		this(errorCode, List.of());
	}

	public ApiException(ErrorCode errorCode, List<ErrorDetail> details) {
		super(errorCode.name());
		this.errorCode = errorCode;
		this.details = List.copyOf(details);
	}

	// 응답에 쓸 오류 코드를 돌려준다.
	public ErrorCode errorCode() {
		return errorCode;
	}

	// 응답의 details에 담을 세부 원인 목록을 돌려준다.
	public List<ErrorDetail> details() {
		return details;
	}

}
