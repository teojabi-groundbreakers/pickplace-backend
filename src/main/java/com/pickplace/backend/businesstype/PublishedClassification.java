package com.pickplace.backend.businesstype;

// 지금 게시된 지역 릴리스와, 그 릴리스 검증 보고서에 적힌 업종 분류 번호다.
// classificationId는 보고서에 번호가 없거나 정수가 아니면 null이다(Service가 내부 정합성 오류로 처리한다).
public record PublishedClassification(long releaseId, String releaseStatus, Long classificationId) {
}
