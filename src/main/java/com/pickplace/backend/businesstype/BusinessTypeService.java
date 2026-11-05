package com.pickplace.backend.businesstype;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BusinessTypeService {

	private final BusinessTypeRepository businessTypeRepository;

	public BusinessTypeService(BusinessTypeRepository businessTypeRepository) {
		this.businessTypeRepository = businessTypeRepository;
	}

	// 화면에서 선택할 수 있는 지원 업종 목록을 표시 순서대로 돌려준다.
	// 지원 여부를 바꾸면 다음 요청부터 바로 반영되도록 결과를 따로 저장해 두지 않고 매번 DB를 읽는다.
	public List<BusinessTypeItem> getSupportedBusinessTypes() {
		return businessTypeRepository.findAllBySupportedTrueOrderByDisplayOrderAscBusinessTypeCodeAsc().stream()
				.map(BusinessTypeItem::from)
				.toList();
	}

}
