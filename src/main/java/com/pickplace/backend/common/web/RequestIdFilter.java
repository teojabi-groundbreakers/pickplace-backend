package com.pickplace.backend.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 오류 응답에도 requestId가 들어가도록 다른 처리보다 먼저 실행한다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	// 줄바꿈 등이 섞인 헤더로 로그를 위조하지 못하게 클라이언트가 보낸 값의 형식을 제한한다.
	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	// 요청마다 requestId를 정해 응답 헤더에 붙이고, 요청이 끝나면 요청 로그를 한 줄 남긴다.
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(RequestIds.HEADER_NAME));
		long startNanos = System.nanoTime();

		request.setAttribute(RequestIds.ATTRIBUTE_NAME, requestId);
		MDC.put(RequestIds.ATTRIBUTE_NAME, requestId);
		response.setHeader(RequestIds.HEADER_NAME, requestId);

		try {
			filterChain.doFilter(request, response);
		}
		finally {
			long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
			// 쿼리스트링·본문·헤더에는 개인정보나 비밀값이 섞일 수 있어 남기지 않는다.
			log.info("requestId={} method={} path={} status={} durationMs={}",
					requestId, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
			// 스레드가 재사용되므로 지우지 않으면 다음 요청 로그에 이전 requestId가 섞인다.
			MDC.remove(RequestIds.ATTRIBUTE_NAME);
		}
	}

	// 요청 헤더의 값이 안전한 형식이면 그대로 쓰고, 아니면 새 UUID를 만든다.
	private String resolveRequestId(String headerValue) {
		if (headerValue != null && SAFE_REQUEST_ID.matcher(headerValue).matches()) {
			return headerValue;
		}
		return UUID.randomUUID().toString();
	}

}
