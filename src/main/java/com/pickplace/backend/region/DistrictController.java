package com.pickplace.backend.region;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ApiResponse;
import com.pickplace.backend.common.response.ErrorDetail;
import com.pickplace.backend.common.response.ListResponse;

@RestController
@RequestMapping("/api/districts")
public class DistrictController {

	// 원천 자치구 코드(SIGNGU_CD)는 숫자 5자리다.
	private static final Pattern DISTRICT_CODE_FORMAT = Pattern.compile("^[0-9]{5}$");
	private static final String DISTRICT_CODE_FIELD = "districtCode";
	private static final String REASON_INVALID_FORMAT = "INVALID_FORMAT";

	private final DistrictService districtService;

	public DistrictController(DistrictService districtService) {
		this.districtService = districtService;
	}

	// GET /api/districts: 서울 자치구 목록을 코드순으로 돌려준다.
	@GetMapping
	public ApiResponse<ListResponse<DistrictItem>> getDistricts() {
		return ApiResponse.ok(ListResponse.of(districtService.getDistricts()));
	}

	// GET /api/districts/{districtCode}/administrative-dongs: 자치구에 속한 행정동 목록을 이름순으로 돌려준다.
	@GetMapping("/{districtCode}/administrative-dongs")
	public ApiResponse<ListResponse<AdministrativeDongItem>> getAdministrativeDongs(@PathVariable String districtCode) {
		validateDistrictCode(districtCode);
		return ApiResponse.ok(ListResponse.of(districtService.getAdministrativeDongs(districtCode)));
	}

	// 자치구 코드가 숫자 5자리가 아니면 400 INVALID_REGION_CODE로 거절한다.
	// Service는 트랜잭션 시작 때 DB 연결부터 열므로, 형식 검사를 여기서 해야 DB 장애가 입력 오류를 가리지 않는다.
	private void validateDistrictCode(String districtCode) {
		if (!DISTRICT_CODE_FORMAT.matcher(districtCode).matches()) {
			throw new ApiException(ErrorCode.INVALID_REGION_CODE,
					List.of(new ErrorDetail(DISTRICT_CODE_FIELD, REASON_INVALID_FORMAT)));
		}
	}

}
