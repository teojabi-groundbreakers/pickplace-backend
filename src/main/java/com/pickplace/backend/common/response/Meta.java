package com.pickplace.backend.common.response;

import com.pickplace.backend.common.web.RequestIds;

public record Meta(String requestId) {

	// 현재 요청의 requestId를 담은 meta를 만든다.
	public static Meta current() {
		return new Meta(RequestIds.current());
	}

}
