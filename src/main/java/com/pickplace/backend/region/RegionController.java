package com.pickplace.backend.region;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ErrorDetail;

@RestController
@RequestMapping("/api/regions")
public class RegionController {

	// 공식 행정기관 코드(H10)는 ASCII 숫자 10자리다. 앞뒤 공백 제거·숫자 변환·자릿수 보정은 하지 않는다.
	private static final Pattern REGION_CODE_FORMAT = Pattern.compile("[0-9]{10}");
	private static final String PROVINCE_CODE_FIELD = "provinceCode";
	private static final String GROUP_CODE_FIELD = "groupCode";
	// 정해지지 않은 파라미터는 이름 대신 query로 알린다. 사용자가 보낸 임의의 이름을 응답에 되돌려주지 않기 위해서다.
	private static final String UNKNOWN_QUERY_FIELD = "query";
	private static final String REASON_INVALID_FORMAT = "INVALID_FORMAT";
	private static final String REASON_INVALID_VALUE = "INVALID_VALUE";

	private final RegionService regionService;

	public RegionController(RegionService regionService) {
		this.regionService = regionService;
	}

	// GET /api/regions/provinces: 게시된 시·도 목록을 코드순으로 돌려준다.
	@GetMapping("/provinces")
	public RegionListResponse getProvinces(HttpServletRequest request) {
		validateQuery(request, Set.of());
		return RegionListResponse.of(regionService.getProvinces());
	}

	// GET /api/regions/groups?provinceCode=: 시·도에 속한 화면 그룹(구·시·군, 세종 등)을 이름순으로 돌려준다.
	@GetMapping("/groups")
	public RegionListResponse getGroups(@RequestParam String provinceCode, HttpServletRequest request) {
		validateQuery(request, Set.of(PROVINCE_CODE_FIELD));
		validateRegionCode(PROVINCE_CODE_FIELD, provinceCode);
		return RegionListResponse.of(regionService.getGroups(provinceCode));
	}

	// GET /api/regions/localities?groupCode=: 화면 그룹에 속한 읍·면·동을 이름순으로 돌려준다.
	@GetMapping("/localities")
	public RegionListResponse getLocalities(@RequestParam String groupCode, HttpServletRequest request) {
		validateQuery(request, Set.of(GROUP_CODE_FIELD));
		validateRegionCode(GROUP_CODE_FIELD, groupCode);
		return RegionListResponse.of(regionService.getLocalities(groupCode));
	}

	// 정해지지 않은 파라미터나 같은 파라미터를 두 번 보낸 요청을 400 INVALID_REQUEST로 거절한다.
	// Spring은 중복 값을 "a,b"로 합치고 모르는 파라미터는 조용히 무시하므로 요청의 파라미터 목록을 직접 확인한다.
	// 버전 파라미터(regionVersion)도 현재 계약에 없으므로 여기서 거절된다.
	private void validateQuery(HttpServletRequest request, Set<String> allowedNames) {
		for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
			if (!allowedNames.contains(parameter.getKey())) {
				throw new ApiException(ErrorCode.INVALID_REQUEST,
						List.of(new ErrorDetail(UNKNOWN_QUERY_FIELD, REASON_INVALID_VALUE)));
			}
			// 값이 같아도 두 번 보낸 요청은 어느 값을 쓸지 모호하므로 거절한다.
			if (parameter.getValue().length > 1) {
				throw new ApiException(ErrorCode.INVALID_REQUEST,
						List.of(new ErrorDetail(parameter.getKey(), REASON_INVALID_VALUE)));
			}
		}
	}

	// 지역 코드가 숫자 10자리가 아니면 400 INVALID_REGION_CODE로 거절한다.
	// Service는 트랜잭션 시작 때 DB 연결부터 열므로, 형식 검사를 여기서 해야 DB 장애가 입력 오류를 가리지 않는다.
	private void validateRegionCode(String field, String regionCode) {
		if (!REGION_CODE_FORMAT.matcher(regionCode).matches()) {
			throw new ApiException(ErrorCode.INVALID_REGION_CODE, List.of(new ErrorDetail(field, REASON_INVALID_FORMAT)));
		}
	}

}
