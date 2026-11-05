package com.pickplace.backend.businesstype;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.common.response.ListResponse;

@RestController
@RequestMapping("/api/business-types")
public class BusinessTypeController {

	private final BusinessTypeService businessTypeService;

	public BusinessTypeController(BusinessTypeService businessTypeService) {
		this.businessTypeService = businessTypeService;
	}

	// GET /api/business-types: 화면에서 선택할 수 있는 지원 업종 목록을 표시 순서대로 돌려준다.
	@GetMapping
	public ApiResponse<ListResponse<BusinessTypeItem>> getBusinessTypes() {
		return ApiResponse.ok(ListResponse.of(businessTypeService.getSupportedBusinessTypes()));
	}

}
