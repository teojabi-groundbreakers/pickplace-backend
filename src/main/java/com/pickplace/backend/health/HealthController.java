package com.pickplace.backend.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.response.ApiResponse;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	private final DatabaseHealthChecker databaseHealthChecker;

	public HealthController(DatabaseHealthChecker databaseHealthChecker) {
		this.databaseHealthChecker = databaseHealthChecker;
	}

	// GET /api/health: 서버가 요청을 받을 수 있는지(생존) 확인한다.
	// DB가 잠깐 멈췄다고 생존 확인까지 실패하면 멀쩡한 서버를 재시작하게 되므로 DB를 보지 않는다.
	@GetMapping
	public ApiResponse<HealthResponse> liveness() {
		return ApiResponse.ok(HealthResponse.up());
	}

	// GET /api/health/ready: DB까지 연결돼 실제 조회가 가능한지(준비) 확인한다. 실패하면 503이다.
	@GetMapping("/ready")
	public ApiResponse<HealthResponse> readiness() {
		databaseHealthChecker.check();
		return ApiResponse.ok(HealthResponse.up());
	}

}
