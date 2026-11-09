package com.pickplace.backend.businesstype;

// 업종 목록 한 항목이다. 코드는 소진공 소분류 코드, 이름은 승인 분류본의 화면 표시명이다.
public record BusinessTypeItem(String businessTypeCode, String businessTypeName, boolean supported) {
}
