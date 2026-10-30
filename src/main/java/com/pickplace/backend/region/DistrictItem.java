package com.pickplace.backend.region;

public record DistrictItem(String districtCode, String districtName) {

	// 자치구 Entity를 응답용 항목으로 바꾼다. Entity를 응답에 직접 내보내지 않기 위해서다.
	public static DistrictItem from(District district) {
		return new DistrictItem(district.getDistrictCode(), district.getDistrictName());
	}

}
