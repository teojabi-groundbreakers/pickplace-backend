package com.pickplace.backend.region;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 전국 지역 목록 응답을 브라우저가 저장해 두고 다시 쓰지 않게 Cache-Control: no-store를 붙인다.
// 게시본이 바뀐 뒤에도 예전 부모·자식 목록이 화면에 남는 일을 막기 위해서다.
// 오류 응답에도 같은 헤더가 붙도록 Controller가 아닌 필터에서 요청 처리 전에 넣는다.
@Component
public class RegionCacheControlFilter extends OncePerRequestFilter {

	private static final String REGION_API_PREFIX = "/api/regions/";
	private static final String NO_STORE = "no-store";

	// 지역 목록 API가 아닌 요청은 건드리지 않는다.
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(REGION_API_PREFIX);
	}

	// 응답 헤더에 no-store를 넣고 나머지 처리를 이어간다.
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
		filterChain.doFilter(request, response);
	}

}
