package com.pickplace.backend.businesstype;

public record BusinessTypeItem(String businessTypeCode, String businessTypeName, boolean supported) {

	// 업종 Entity를 응답용 항목으로 바꾼다. Entity를 응답에 직접 내보내지 않기 위해서다.
	public static BusinessTypeItem from(BusinessType businessType) {
		return new BusinessTypeItem(businessType.getBusinessTypeCode(), businessType.getDisplayName(),
				businessType.isSupported());
	}

}
