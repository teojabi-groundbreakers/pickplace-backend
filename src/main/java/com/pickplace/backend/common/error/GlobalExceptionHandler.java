package com.pickplace.backend.common.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pickplace.backend.common.response.ApiErrorResponse;
import com.pickplace.backend.common.response.ErrorDetail;
import com.pickplace.backend.common.web.RequestIds;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String REASON_REQUIRED = "REQUIRED";
	private static final String REASON_INVALID_FORMAT = "INVALID_FORMAT";
	private static final String REASON_INVALID_VALUE = "INVALID_VALUE";

	// 서비스 코드가 던진 ApiException을 그 오류 코드 그대로 응답한다.
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
		return respond(ex.errorCode(), ex.details(), ex);
	}

	// 필수 쿼리 파라미터가 빠진 요청을 400으로 응답한다.
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
		return respond(ErrorCode.INVALID_REQUEST, List.of(new ErrorDetail(ex.getParameterName(), REASON_REQUIRED)), ex);
	}

	// 파라미터 타입이 맞지 않는 요청(예: 숫자 자리에 문자)을 400으로 응답한다.
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return respond(ErrorCode.INVALID_REQUEST, List.of(new ErrorDetail(ex.getName(), REASON_INVALID_FORMAT)), ex);
	}

	// 요청 본문(JSON) 필드 검증에 실패한 요청을 400으로 응답한다.
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
		List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ErrorDetail(fieldError.getField(), REASON_INVALID_VALUE))
				.toList();
		return respond(ErrorCode.INVALID_REQUEST, details, ex);
	}

	// 쿼리·경로 파라미터 검증(@Pattern 등)에 실패한 요청을 400으로 응답한다.
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiErrorResponse> handleParameterValidation(HandlerMethodValidationException ex) {
		List<ErrorDetail> details = ex.getParameterValidationResults().stream()
				.map(result -> new ErrorDetail(result.getMethodParameter().getParameterName(), REASON_INVALID_VALUE))
				.toList();
		return respond(ErrorCode.INVALID_REQUEST, details, ex);
	}

	// 요청 본문을 JSON으로 읽을 수 없는 요청을 400으로 응답한다.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return respond(ErrorCode.INVALID_REQUEST, List.of(), ex);
	}

	// 존재하지 않는 주소로 온 요청을 404로 응답한다.
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
		return respond(ErrorCode.NOT_FOUND, List.of(), ex);
	}

	// 지원하지 않는 HTTP 메서드로 온 요청을 405로 응답한다.
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
		return respond(ErrorCode.METHOD_NOT_ALLOWED, List.of(), ex);
	}

	// DB 연결·조회 실패를 503으로 응답한다. 빈 결과로 위장하지 않는다.
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException ex) {
		return respond(ErrorCode.DATA_SERVICE_UNAVAILABLE, List.of(), ex);
	}

	// 위에서 처리하지 못한 나머지 모든 예외를 응답한다.
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
		// 415처럼 Spring이 이미 4xx로 판단한 요청 오류를 500 서버 오류로 바꾸지 않는다.
		if (ex instanceof ErrorResponse springError && springError.getStatusCode().is4xxClientError()) {
			logClientError(ErrorCode.INVALID_REQUEST, ex);
			return ResponseEntity.status(springError.getStatusCode())
					.body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST, List.of()));
		}
		return respond(ErrorCode.INTERNAL_ERROR, List.of(), ex);
	}

	// 로그를 남기고 오류 코드의 HTTP 상태로 공통 오류 응답을 만든다.
	private ResponseEntity<ApiErrorResponse> respond(ErrorCode errorCode, List<ErrorDetail> details, Exception ex) {
		HttpStatusCode status = errorCode.status();
		if (status.is5xxServerError()) {
			// 예외 메시지에 SQL·DB 주소가 들어 있을 수 있어 응답이 아닌 로그에만 남긴다.
			log.error("requestId={} code={} 서버 오류", RequestIds.current(), errorCode.name(), ex);
		}
		else {
			logClientError(errorCode, ex);
		}
		return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, details));
	}

	// 잘못된 요청(4xx)을 스택트레이스 없이 WARN 로그로 남긴다.
	private void logClientError(ErrorCode errorCode, Exception ex) {
		// 예외 메시지에 사용자가 보낸 값이 들어 있을 수 있어 예외 종류만 남긴다.
		log.warn("requestId={} code={} exception={}", RequestIds.current(), errorCode.name(), ex.getClass().getSimpleName());
	}

}
