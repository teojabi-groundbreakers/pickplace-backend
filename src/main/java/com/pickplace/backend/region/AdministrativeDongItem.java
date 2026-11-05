package com.pickplace.backend.region;

public record AdministrativeDongItem(
		String dongCode,
		String dongName,
		String districtCode,
		String officialCodeStatus,
		String regionVersion) {

	// 행정동 Entity를 응답용 항목으로 바꾼다. Entity를 응답에 직접 내보내지 않기 위해서다.
	public static AdministrativeDongItem from(AdministrativeDong dong) {
		return new AdministrativeDongItem(dong.getDongCode(), dong.getDongName(), dong.getDistrict().getDistrictCode(),
				dong.getOfficialCodeStatus(), dong.getRegionVersion());
	}

}
