package com.pickplace.backend.health;

public record HealthResponse(String status) {

	// 정상 상태(UP) 응답을 만든다.
	public static HealthResponse up() {
		return new HealthResponse("UP");
	}

}
