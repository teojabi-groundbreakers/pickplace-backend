package com.pickplace.backend.common.web;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

public final class RequestIds {

	public static final String ATTRIBUTE_NAME = "requestId";
	public static final String HEADER_NAME = "X-Request-Id";

	private RequestIds() {
	}

	// 현재 처리 중인 요청의 requestId를 돌려준다. 요청 밖에서 부르면 null이다.
	public static String current() {
		// 오류 처리 단계에서는 MDC가 이미 비워졌을 수 있어 요청 속성을 먼저 본다.
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes != null
				&& attributes.getAttribute(ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST) instanceof String requestId) {
			return requestId;
		}
		return MDC.get(ATTRIBUTE_NAME);
	}

}
