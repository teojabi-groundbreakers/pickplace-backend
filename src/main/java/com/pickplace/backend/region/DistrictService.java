package com.pickplace.backend.region;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ErrorDetail;

@Service
@Transactional(readOnly = true)
public class DistrictService {

	// 원천 자치구 코드(SIGNGU_CD)는 숫자 5자리다.
	private static final Pattern DISTRICT_CODE_FORMAT = Pattern.compile("^[0-9]{5}$");
	private static final String DISTRICT_CODE_FIELD = "districtCode";
	private static final String REASON_INVALID_FORMAT = "INVALID_FORMAT";
	private static final String REASON_NOT_FOUND = "NOT_FOUND";

	private final DistrictRepository districtRepository;
	private final AdministrativeDongRepository administrativeDongRepository;

	public DistrictService(DistrictRepository districtRepository, AdministrativeDongRepository administrativeDongRepository) {
		this.districtRepository = districtRepository;
		this.administrativeDongRepository = administrativeDongRepository;
	}

	// 서울 자치구 목록을 코드순으로 돌려준다.
	public List<DistrictItem> getDistricts() {
		return districtRepository.findAllByOrderByDistrictCodeAsc().stream()
				.map(DistrictItem::from)
				.toList();
	}

	// 자치구에 속한 행정동 목록을 이름순으로 돌려준다. 형식이 틀린 코드는 400, 없는 자치구는 404다.
	public List<AdministrativeDongItem> getAdministrativeDongs(String districtCode) {
		if (districtCode == null || !DISTRICT_CODE_FORMAT.matcher(districtCode).matches()) {
			throw new ApiException(ErrorCode.INVALID_REGION_CODE,
					List.of(new ErrorDetail(DISTRICT_CODE_FIELD, REASON_INVALID_FORMAT)));
		}
		// 동이 0개인 정상 자치구(빈 목록)와 없는 자치구(404)를 구분하려면 자치구 존재를 먼저 확인해야 한다.
		if (!districtRepository.existsById(districtCode)) {
			throw new ApiException(ErrorCode.REGION_NOT_FOUND,
					List.of(new ErrorDetail(DISTRICT_CODE_FIELD, REASON_NOT_FOUND)));
		}
		return administrativeDongRepository.findAllByDistrictDistrictCodeOrderByDongNameAscDongCodeAsc(districtCode).stream()
				.map(AdministrativeDongItem::from)
				.toList();
	}

}
