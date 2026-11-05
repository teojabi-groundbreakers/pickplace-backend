package com.pickplace.backend.businesstype;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessTypeRepository extends JpaRepository<BusinessType, String> {

	// 지원 업종만 표시 순서대로(순서가 같으면 코드순) 조회한다.
	List<BusinessType> findAllBySupportedTrueOrderByDisplayOrderAscBusinessTypeCodeAsc();

}
