package com.pickplace.backend.summary;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ApiErrorResponse;
import com.pickplace.backend.common.response.ErrorDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
@RequestMapping("/api/regions")
public class SummaryController {

	private static final String BUSINESS_TYPE_CODE = "businessTypeCode";
	private static final Set<String> ALLOWED_QUERY = Set.of(BUSINESS_TYPE_CODE);
	// 정해지지 않은 파라미터는 이름 대신 query로 알린다. 사용자가 보낸 임의의 이름을 응답에 되돌려주지 않기 위해서다.
	private static final String UNKNOWN_QUERY_FIELD = "query";
	private static final String REASON_INVALID_VALUE = "INVALID_VALUE";

	private final SummaryService summaryService;

	public SummaryController(SummaryService summaryService) {
		this.summaryService = summaryService;
	}

	// GET /api/regions/{regionCode}/summary?businessTypeCode=: 서버가 고른 기본 현황 묶음으로 인구·수록 업소 수를 돌려준다.
	@GetMapping("/{regionCode}/summary")
	@Operation(summary = "지역·업종 현황 조회",
			description = "현행 읍·면·동(H10)과 소진공 소분류 업종의 주민등록 인구·수록 업소 수를 서버의 기본(DEFAULT) 현황 묶음으로 돌려준다. "
					+ "월·분기·버전 입력은 받지 않는다. 지표가 결측·보류면 200 응답에서 해당 value가 null이고 status·reasonCode로 이유를 알린다.")
	@ApiResponse(responseCode = "200", description = "정상. 지표별 값 또는 null과 사유")
	@ApiResponse(responseCode = "400", description = "INVALID_REQUEST(업종 누락·형식, 미정의·중복 파라미터) 또는 INVALID_REGION_CODE",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "404", description = "REGION_NOT_FOUND(현행 읍·면·동 아님) 또는 BUSINESS_TYPE_NOT_FOUND(현재 분류에 없는 소분류)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "422", description = "BUSINESS_TYPE_NOT_SUPPORTED(등록됐지만 지원하지 않는 업종)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "500", description = "INTERNAL_ERROR(게시 자료의 참조 무결성 오류 등)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "REFERENCE_DATA_NOT_READY(지역 기준정보 미게시), "
			+ "SUMMARY_DATA_NOT_READY(기본 현황 묶음 미게시) 또는 DATA_SERVICE_UNAVAILABLE(DB 장애)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	// 업종 코드는 누락·중복을 직접 구분해야 해서 @RequestParam 대신 요청에서 꺼내므로, 문서용 설명만 따로 단다.
	@Parameter(name = BUSINESS_TYPE_CODE, in = ParameterIn.QUERY, required = true,
			description = "소진공 소분류 업종 코드(대문자 1자 + 숫자 5자리)", example = "I21201")
	public SummaryResponse getSummary(
			@Parameter(description = "읍·면·동 행정기관 코드(H10, 숫자 10자리)", example = "1111051500") @PathVariable String regionCode,
			HttpServletRequest request) {
		validateQuery(request);
		String businessTypeCode = request.getParameter(BUSINESS_TYPE_CODE);
		// 업종 누락 → 지역 형식 → 업종 형식 순서로 DB 연결 전에 검사한다. withContext도 같은 검사를 다시 한다.
		SummaryContextService.validateCodes(regionCode, businessTypeCode);
		return SummaryResponse.of(summaryService.getSummary(regionCode, businessTypeCode));
	}

	// 정해지지 않은 파라미터(month·quarter·regionVersion 등)나 같은 파라미터를 두 번 보낸 요청을 400 INVALID_REQUEST로 거절한다.
	// Spring은 중복 값을 합치고 모르는 파라미터는 조용히 무시하므로 요청의 파라미터 목록을 직접 확인한다.
	private void validateQuery(HttpServletRequest request) {
		for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
			if (!ALLOWED_QUERY.contains(parameter.getKey())) {
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

}
