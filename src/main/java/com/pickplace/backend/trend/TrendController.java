package com.pickplace.backend.trend;

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
public class TrendController {

	private static final String DATASET_CODE = "datasetCode";
	private static final String INDUSTRY_CODE = "industryCode";
	private static final String BUSINESS_TYPE_CODE = "businessTypeCode";
	private static final String FROM_QUARTER = "fromQuarter";
	private static final String TO_QUARTER = "toQuarter";
	private static final Set<String> ALLOWED_QUERY = Set.of(DATASET_CODE, INDUSTRY_CODE, BUSINESS_TYPE_CODE, FROM_QUARTER, TO_QUARTER);
	// 정해지지 않은 파라미터는 이름 대신 query로 알린다. 사용자가 보낸 임의의 이름을 응답에 되돌려주지 않기 위해서다.
	private static final String UNKNOWN_QUERY_FIELD = "query";
	private static final String REGION_CODE_FIELD = "regionCode";
	private static final String REASON_REQUIRED = "REQUIRED";
	private static final String REASON_INVALID_FORMAT = "INVALID_FORMAT";
	private static final String REASON_INVALID_VALUE = "INVALID_VALUE";
	private static final String REGION_CODE_PATTERN = "[0-9]{10}";
	// 서울 상권 원천의 업종 코드 형식이다.
	private static final String SEOUL_INDUSTRY_PATTERN = "CS[0-9]{6}";
	// 소진공 소분류 코드 형식이다. 현황 API와 같다.
	private static final String SEMAS_INDUSTRY_PATTERN = "[A-Z][0-9]{5}";

	private final TrendService trendService;

	public TrendController(TrendService trendService) {
		this.trendService = trendService;
	}

	// GET /api/regions/{regionCode}/trends: 게시된 상세 통계(서울 행정동 자료·전국 수록 업소)를 분기별로 돌려준다.
	@GetMapping("/{regionCode}/trends")
	@Operation(summary = "지역 상세 통계 분기별 추이 조회",
			description = "현행 읍·면·동(H10)의 분기별 상세 통계를 요청한 범위(최대 8분기)에서 시간순으로 돌려준다. "
					+ "범위의 모든 분기가 응답에 들어가며, 값이 없는 분기는 status·reasonCode로 이유를 알린다. "
					+ "값이 있는 지표에는 전 분기·전년 동기 대비 증감을 함께 준다. "
					+ "전국 수록 업소(LISTED_STORE)는 소진공 업종 코드(businessTypeCode), 서울 점포(STORE)·매출(SALES)은 서울 업종 코드(industryCode)가 필요하다.")
	@ApiResponse(responseCode = "200", description = "정상. 분기별 값 또는 null과 사유")
	@ApiResponse(responseCode = "400", description = "INVALID_REQUEST(자료·업종 누락·형식, 미정의·중복 파라미터), "
			+ "INVALID_REGION_CODE 또는 INVALID_QUARTER(분기 형식, 종료가 시작보다 앞, 8분기 초과)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "404", description = "REGION_NOT_FOUND(현행 읍·면·동 아님) 또는 BUSINESS_TYPE_NOT_FOUND(현재 분류에 없는 소분류)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "422", description = "BUSINESS_TYPE_NOT_SUPPORTED(등록됐지만 지원하지 않는 업종)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "500", description = "INTERNAL_ERROR(게시 자료의 참조 무결성 오류 등)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	@ApiResponse(responseCode = "503", description = "REFERENCE_DATA_NOT_READY(지역 기준정보 미게시) 또는 DATA_SERVICE_UNAVAILABLE(DB 장애)",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	// 누락·중복을 직접 구분해야 해서 @RequestParam 대신 요청에서 꺼내므로, 문서용 설명만 따로 단다.
	@Parameter(name = DATASET_CODE, in = ParameterIn.QUERY, required = true,
			description = "자료 종류: LISTED_STORE(전국 수록 업소), STORE(서울 점포·개폐업), SALES(서울 추정매출), "
					+ "WORKER(서울 직장인구), FACILITY(서울 시설), FLOW(서울 길단위인구)", example = "LISTED_STORE")
	@Parameter(name = BUSINESS_TYPE_CODE, in = ParameterIn.QUERY,
			description = "소진공 소분류 업종 코드(대문자 1자 + 숫자 5자리). LISTED_STORE에서만 필수이고 다른 자료에 보내면 400", example = "I21201")
	@Parameter(name = INDUSTRY_CODE, in = ParameterIn.QUERY,
			description = "서울 상권 업종 코드(CS + 숫자 6자리). STORE·SALES에서만 필수이고 다른 자료에 보내면 400", example = "CS100010")
	@Parameter(name = FROM_QUARTER, in = ParameterIn.QUERY, required = true, description = "시작 분기(YYYYQ)", example = "20243")
	@Parameter(name = TO_QUARTER, in = ParameterIn.QUERY, required = true, description = "종료 분기(YYYYQ, 시작 포함 최대 8분기)", example = "20262")
	public TrendResponse getTrend(
			@Parameter(description = "읍·면·동 행정기관 코드(H10, 숫자 10자리)", example = "1111051500") @PathVariable String regionCode,
			HttpServletRequest request) {
		validateQuery(request);
		return TrendResponse.of(trendService.getTrend(toRequest(regionCode, request)));
	}

	// 정해지지 않은 파라미터(month·quarter 등)나 같은 파라미터를 두 번 보낸 요청을 400 INVALID_REQUEST로 거절한다.
	// Spring은 중복 값을 합치고 모르는 파라미터는 조용히 무시하므로 요청의 파라미터 목록을 직접 확인한다.
	private void validateQuery(HttpServletRequest request) {
		for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
			if (!ALLOWED_QUERY.contains(parameter.getKey())) {
				throw error(ErrorCode.INVALID_REQUEST, UNKNOWN_QUERY_FIELD, REASON_INVALID_VALUE);
			}
			// 값이 같아도 두 번 보낸 요청은 어느 값을 쓸지 모호하므로 거절한다.
			if (parameter.getValue().length > 1) {
				throw error(ErrorCode.INVALID_REQUEST, parameter.getKey(), REASON_INVALID_VALUE);
			}
		}
	}

	// 지역 → 자료 → 업종 → 시작 분기 → 종료 분기 → 범위 순서로 DB 연결 전에 검사해 조회 조건을 만든다.
	// 공백 제거·대소문자 변환·자릿수 보정은 하지 않는다.
	private TrendRequest toRequest(String regionCode, HttpServletRequest request) {
		if (!regionCode.matches(REGION_CODE_PATTERN)) {
			throw error(ErrorCode.INVALID_REGION_CODE, REGION_CODE_FIELD, REASON_INVALID_FORMAT);
		}
		TrendDataset dataset = TrendDataset.find(required(request, DATASET_CODE))
				.orElseThrow(() -> error(ErrorCode.INVALID_REQUEST, DATASET_CODE, REASON_INVALID_VALUE));
		String industryCode = industryCode(dataset, request);
		String from = quarter(request, FROM_QUARTER);
		String to = quarter(request, TO_QUARTER);
		int count = Quarters.count(from, to);
		// 종료가 시작보다 앞이거나 8분기를 넘으면 종료 분기를 고쳐야 하는 요청으로 알린다.
		if (count < 1 || count > Quarters.MAX_QUARTERS) {
			throw error(ErrorCode.INVALID_QUARTER, TO_QUARTER, REASON_INVALID_VALUE);
		}
		return new TrendRequest(regionCode, dataset, industryCode, Quarters.between(from, to));
	}

	// 자료의 업종 체계에 맞는 파라미터(서울 CS는 industryCode, 소진공은 businessTypeCode)만 받는다.
	// 맞지 않는 업종 파라미터는 거절한다. 조용히 무시하면 FE가 업종별 값으로 오해하거나 두 체계를 섞어 쓸 수 있기 때문이다.
	private static String industryCode(TrendDataset dataset, HttpServletRequest request) {
		String expected = dataset.industryKind().parameter();
		for (String name : List.of(INDUSTRY_CODE, BUSINESS_TYPE_CODE)) {
			if (!name.equals(expected) && request.getParameter(name) != null) {
				throw error(ErrorCode.INVALID_REQUEST, name, REASON_INVALID_VALUE);
			}
		}
		if (expected == null) {
			return null;
		}
		String code = required(request, expected);
		String pattern = expected.equals(INDUSTRY_CODE) ? SEOUL_INDUSTRY_PATTERN : SEMAS_INDUSTRY_PATTERN;
		if (!code.matches(pattern)) {
			throw error(ErrorCode.INVALID_REQUEST, expected, REASON_INVALID_FORMAT);
		}
		return code;
	}

	// 분기 파라미터가 있고 YYYYQ 형식인지 확인한다.
	private static String quarter(HttpServletRequest request, String name) {
		String quarter = required(request, name);
		if (!Quarters.isValid(quarter)) {
			throw error(ErrorCode.INVALID_QUARTER, name, REASON_INVALID_FORMAT);
		}
		return quarter;
	}

	// 필수 파라미터가 없거나 빈 값이면 400 REQUIRED다.
	private static String required(HttpServletRequest request, String name) {
		String value = request.getParameter(name);
		if (value == null || value.isEmpty()) {
			throw error(ErrorCode.INVALID_REQUEST, name, REASON_REQUIRED);
		}
		return value;
	}

	// 필드 하나와 사유 하나를 담은 400 계열 오류를 만든다.
	private static ApiException error(ErrorCode code, String field, String reason) {
		return new ApiException(code, List.of(new ErrorDetail(field, reason)));
	}

}
