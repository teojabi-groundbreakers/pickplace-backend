package com.pickplace.backend.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	INVALID_QUARTER(HttpStatus.BAD_REQUEST, "분기는 YYYYQ 형식이며 Q는 1~4여야 합니다."),
	INVALID_REGION_CODE(HttpStatus.BAD_REQUEST, "지역 코드 형식이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
	REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 지역을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	BUSINESS_TYPE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "아직 지원하지 않는 업종입니다."),
	QUARTER_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "지원하지 않는 분기입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
	DATA_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	// 이 오류 코드로 응답할 HTTP 상태를 돌려준다.
	public HttpStatus status() {
		return status;
	}

	// 화면에 보여줄 사용자용 메시지를 돌려준다.
	public String message() {
		return message;
	}

}
