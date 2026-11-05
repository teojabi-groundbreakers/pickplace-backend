package com.pickplace.backend.region;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdministrativeDongRepository extends JpaRepository<AdministrativeDong, String> {

	// 자치구에 속한 행정동을 이름순(이름이 같으면 코드순)으로 조회한다.
	List<AdministrativeDong> findAllByDistrictDistrictCodeOrderByDongNameAscDongCodeAsc(String districtCode);

}
