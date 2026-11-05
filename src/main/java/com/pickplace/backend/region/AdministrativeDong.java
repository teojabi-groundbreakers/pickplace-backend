package com.pickplace.backend.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

// 기준정보는 별도 수동 적재로만 바뀌므로 조회 API에서 실수로 수정되지 않게 읽기 전용으로 둔다.
@Entity
@Immutable
@Table(name = "administrative_dong")
public class AdministrativeDong {

	@Id
	@Column(name = "dong_code")
	private String dongCode;

	@Column(name = "dong_name")
	private String dongName;

	// 동 목록에는 자치구 코드만 필요하므로 지연 로딩으로 두어 자치구 행을 함께 읽지 않는다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "district_code")
	private District district;

	@Column(name = "official_code_status")
	private String officialCodeStatus;

	@Column(name = "region_version")
	private String regionVersion;

	protected AdministrativeDong() {
	}

	// 행정동 코드(숫자 8자리)를 돌려준다.
	public String getDongCode() {
		return dongCode;
	}

	// 행정동 이름을 돌려준다.
	public String getDongName() {
		return dongName;
	}

	// 이 행정동이 속한 자치구를 돌려준다.
	public District getDistrict() {
		return district;
	}

	// 공식 코드 상태(CURRENT·LEGACY 등)를 돌려준다.
	public String getOfficialCodeStatus() {
		return officialCodeStatus;
	}

	// 지역 자료 버전을 돌려준다.
	public String getRegionVersion() {
		return regionVersion;
	}

}
