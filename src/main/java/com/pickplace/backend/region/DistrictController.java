package com.pickplace.backend.region;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.common.response.ListResponse;

@RestController
@RequestMapping("/api/districts")
public class DistrictController {

	private final DistrictService districtService;

	public DistrictController(DistrictService districtService) {
		this.districtService = districtService;
	}

	// GET /api/districts: 서울 자치구 목록을 코드순으로 돌려준다.
	@GetMapping
	public ApiResponse<ListResponse<DistrictItem>> getDistricts() {
		return ApiResponse.ok(ListResponse.of(districtService.getDistricts()));
	}

}
