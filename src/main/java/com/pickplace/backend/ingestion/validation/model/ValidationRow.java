package com.pickplace.backend.ingestion.validation.model;

import java.util.List;

// 원본 위치를 남겨 정제한 값과 문제를 다시 추적할 수 있게 한다.
public record ValidationRow(Position position, String sourceKey, String sourceRegionCode,
        String regionCode, String industryCode, String disposition, String temporalStatus,
        Population population, Store store, List<Issue> issues) {
    // rowNumber는 CSV 헤더를 제외한 논리 레코드 번호다. 따옴표 안의 줄바꿈은 새 행으로 세지 않는다.
    public record Position(int artifactNo, String member, long rowNumber) {}
    // AVAILABLE 외의 수치·연령은 null로 둔다. 누락이나 오류를 실제 인구 0과 구분하기 위해서다.
    public record Population(String rowStatus, Long total, Long male, Long female,
            List<Long> maleAges, List<Long> femaleAges) {}
    // 법정동은 참고 정보다. 서비스의 행정동 H10이나 원천 행정동8을 대신하지 않는다.
    public record Store(String name, String largeCode, String middleCode, String rawSmallCode,
            String legalDongCode, String address, Double longitude, Double latitude, String locationStatus) {}
    // affectsCount와 impactScope는 T14가 어느 집계를 보류할지 정할 때 사용한다.
    public record Issue(String code, String field, String rawValue, String severity,
            boolean affectsCount, String impactScope, String affectedRegion, String affectedIndustry) {}

    public ValidationRow withDuplicate(boolean conflict) {
        // 동일 복사본은 중복 회계로만 남긴다. 원본 위치와 기존 검사 결과는 추적할 수 있게 유지한다.
        if (!conflict) return new ValidationRow(position, sourceKey, sourceRegionCode, regionCode, industryCode,
                "DUPLICATE", temporalStatus, population, store, issues);
        // 내용 충돌은 먼저 나온 행도 신뢰할 수 없다. 해당 키 전체를 보류하고 원문에서 재확인한다.
        var updated = new java.util.ArrayList<>(issues);
        updated.add(impact("DUPLICATE_ID_CONFLICT", "sourceKey", sourceKey, regionCode, industryCode));
        return new ValidationRow(position, sourceKey, sourceRegionCode, regionCode, industryCode,
                "HOLD", temporalStatus, null, store, List.copyOf(updated));
    }

    public static Issue impact(String code, String field, String raw, String region, String industry) {
        // 업종을 모르면 지역 전체, 지역도 모르면 새 게시 전체를 막도록 영향 범위를 넓힌다.
        String scope = region == null ? "GLOBAL" : industry == null ? "REGION" : "REGION_INDUSTRY";
        return new Issue(code, field, raw, "HOLD", true, scope, region, region == null ? null : industry);
    }
}
