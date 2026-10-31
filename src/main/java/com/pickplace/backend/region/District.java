package com.pickplace.backend.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

// 기준정보는 별도 수동 적재로만 바뀌므로 조회 API에서 실수로 수정되지 않게 읽기 전용으로 둔다.
@Entity
@Immutable
@Table(name = "district")
public class District {

	@Id
	@Column(name = "district_code")
	private String districtCode;

	@Column(name = "district_name")
	private String districtName;

	protected District() {
	}

	// 자치구 코드(숫자 5자리)를 돌려준다.
	public String getDistrictCode() {
		return districtCode;
	}

	// 자치구 이름을 돌려준다.
	public String getDistrictName() {
		return districtName;
	}

}
