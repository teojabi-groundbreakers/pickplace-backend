package com.pickplace.backend.businesstype;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

// 기준정보는 별도 수동 적재로만 바뀌므로 조회 API에서 실수로 수정되지 않게 읽기 전용으로 둔다.
@Entity
@Immutable
@Table(name = "business_type")
public class BusinessType {

	@Id
	@Column(name = "business_type_code")
	private String businessTypeCode;

	// 화면에는 원천 업종명이 아니라 팀이 정한 표시명을 쓴다(예: 원천 "커피-음료" → 표시 "커피·음료").
	@Column(name = "display_name")
	private String displayName;

	@Column(name = "is_supported")
	private boolean supported;

	@Column(name = "display_order")
	private int displayOrder;

	protected BusinessType() {
	}

	// 서비스 업종 코드(CS + 숫자 6자리)를 돌려준다.
	public String getBusinessTypeCode() {
		return businessTypeCode;
	}

	// 화면 표시명을 돌려준다.
	public String getDisplayName() {
		return displayName;
	}

	// 화면에서 선택할 수 있는 지원 업종인지 돌려준다.
	public boolean isSupported() {
		return supported;
	}

	// 화면 표시 순서를 돌려준다.
	public int getDisplayOrder() {
		return displayOrder;
	}

}
