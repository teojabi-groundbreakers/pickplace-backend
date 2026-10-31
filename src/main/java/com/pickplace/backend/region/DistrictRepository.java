package com.pickplace.backend.region;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DistrictRepository extends JpaRepository<District, String> {

	// 전체 자치구를 코드순으로 조회한다.
	List<District> findAllByOrderByDistrictCodeAsc();

}
