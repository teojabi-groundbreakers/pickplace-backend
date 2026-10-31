package com.pickplace.backend.region;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DistrictService {

	private final DistrictRepository districtRepository;

	public DistrictService(DistrictRepository districtRepository) {
		this.districtRepository = districtRepository;
	}

	// 서울 자치구 목록을 코드순으로 돌려준다.
	public List<DistrictItem> getDistricts() {
		return districtRepository.findAllByOrderByDistrictCodeAsc().stream()
				.map(DistrictItem::from)
				.toList();
	}

}
