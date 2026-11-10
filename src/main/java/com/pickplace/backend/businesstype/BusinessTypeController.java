package com.pickplace.backend.businesstype;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.common.response.ErrorDetail;
import com.pickplace.backend.common.response.ListResponse;

@RestController
@RequestMapping("/api/business-types")
public class BusinessTypeController {

	// 정해지지 않은 파라미터는 이름 대신 query로 알린다. 사용자가 보낸 임의의 이름을 응답에 되돌려주지 않기 위해서다.
	private static final String UNKNOWN_QUERY_FIELD = "query";
	private static final String REASON_INVALID_VALUE = "INVALID_VALUE";

	private final BusinessTypeService businessTypeService;

	public BusinessTypeController(BusinessTypeService businessTypeService) {
		this.businessTypeService = businessTypeService;
	}

	// GET /api/business-types: 화면에서 선택할 수 있는 지원 업종 목록을 표시 순서대로 돌려준다.
	@GetMapping
	public ApiResponse<ListResponse<BusinessTypeItem>> getBusinessTypes(HttpServletRequest request) {
		validateNoQuery(request);
		return ApiResponse.ok(ListResponse.of(businessTypeService.getSupportedBusinessTypes()));
	}

	// 이 경로는 파라미터가 없으므로 무엇이든 붙어 오면 400 INVALID_REQUEST로 거절한다.
	// Service는 트랜잭션 시작 때 DB 연결부터 열므로, 여기서 검사해야 DB 장애가 입력 오류를 가리지 않는다.
	private void validateNoQuery(HttpServletRequest request) {
		if (!request.getParameterMap().isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST,
					List.of(new ErrorDetail(UNKNOWN_QUERY_FIELD, REASON_INVALID_VALUE)));
		}
	}

}
