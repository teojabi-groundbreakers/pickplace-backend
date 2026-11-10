package com.pickplace.backend.ingestion.validation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import static com.pickplace.backend.ingestion.validation.model.ValidationRow.*;

/**
 * 원본 한 행의 수치·코드·시점을 검사하고 정제 결과를 만든다.
 * 행 간 중복은 PartitionedRowProcessor에서 처리하며, 여기서는 DB에 접근하지 않는다.
 */
public final class NationalRowValidator {
    // API에서 JavaScript 숫자로 전달해도 정밀도를 잃지 않는 정수 상한이다.
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final ValidationReference reference;
    private final NationalDownloadSource source;
    private final String sourceVersion;

    public NationalRowValidator(ValidationReference reference, NationalDownloadSource source) {
        this.reference = reference;
        this.source = source;
        this.sourceVersion = reference.sourceVersion(source);
    }

    public ValidationRow validate(List<String> cells, Position position) {
        // 컬럼 위치는 T12 카탈로그의 고정 헤더 순서를 따른다. 구조가 다르면 값을 추정하지 않는다.
        if (cells.size() != source.headers().size()) throw new IllegalArgumentException("CSV 열 수 불일치");
        return source.dataset().equals("POPULATION_MONTH") ? population(cells, position) : store(cells, position);
    }

    private ValidationRow population(List<String> cells, Position position) {
        String code = cells.get(0);
        var resolved = reference.resolve(source.sourceSystem(), code, sourceVersion, LocalDate.parse(source.referenceOn()));
        var issues = new ArrayList<Issue>();
        if (!code.matches("[0-9]{10}")) issues.add(impact("INVALID_REGION_CODE", "행정기관코드", code, null, null));
        if (resolved.reason() != null) issues.add(impact(resolved.reason(), "행정기관코드", code, resolved.regionCode(), null));
        if (!source.referenceOn().equals(cells.get(1)))
            issues.add(impact("REFERENCE_DATE_MISMATCH", "기준연월", cells.get(1), resolved.regionCode(), null));
        var values = new ArrayList<Long>();
        boolean missing = false, suppressed = false, invalid = false;
        // 앞의 5개 지역·기준일 컬럼을 제외한 총/남/녀와 연령 222개를 빠짐없이 검사한다.
        for (int i = 5; i < cells.size(); i++) {
            String raw = cells.get(i).trim();
            try { values.add(number(raw)); }
            catch (IllegalArgumentException exception) {
                // 빈값·비공개·잘못된 숫자는 원인이 다르다. 원문과 사유를 남기고 0으로 바꾸지 않는다.
                values.add(null);
                String reason = raw.isEmpty() ? "SOURCE_VALUE_MISSING" : raw.equals("*") ? "SOURCE_SUPPRESSED" : "INVALID_NUMBER";
                missing |= raw.isEmpty(); suppressed |= raw.equals("*"); invalid |= !raw.isEmpty() && !raw.equals("*");
                issues.add(impact(reason, source.headers().get(i), cells.get(i), resolved.regionCode(), null));
            }
        }
        // 헤더 순서가 고정되어 있어 남녀 111개씩을 나눠 검사한다.
        if (!missing && !suppressed && !invalid) {
            long total = values.get(0), male = values.get(1), female = values.get(2);
            var males = values.subList(3, 114); var females = values.subList(114, 225);
            long maleSum = males.stream().mapToLong(Long::longValue).sum();
            long femaleSum = females.stream().mapToLong(Long::longValue).sum();
            if (total != male + female || male != maleSum || female != femaleSum) {
                invalid = true;
                issues.add(impact("POPULATION_SUM_MISMATCH", "계/남자/여자/연령", null, resolved.regionCode(), null));
            }
        }
        // 문제가 섞여 있으면 오류→비공개→누락 순으로 대표 상태를 정하되 개별 사유는 모두 보존한다.
        String status = invalid ? "INVALID" : suppressed ? "SUPPRESSED" : missing ? "NO_DATA" : "AVAILABLE";
        Population data = status.equals("AVAILABLE")
                ? new Population(status, values.get(0), values.get(1), values.get(2),
                    List.copyOf(values.subList(3, 114)), List.copyOf(values.subList(114, 225)))
                : new Population(status, null, null, null, null, null);
        // 수치가 정상이어도 코드·시점 문제가 있으면 행은 HOLD다. AVAILABLE만 보고 공개하면 안 된다.
        return new ValidationRow(position, code, code, resolved.regionCode(), null,
                issues.isEmpty() ? "ACCEPTED" : "HOLD", resolved.temporalStatus(), data, null, List.copyOf(issues));
    }

    private ValidationRow store(List<String> c, Position position) {
        String id = c.get(0), sourceCode = c.get(15), large = c.get(3), middle = c.get(5), small = c.get(7);
        var resolved = reference.resolve(source.sourceSystem(), sourceCode, sourceVersion, LocalDate.parse(source.referenceOn()));
        boolean industryValid = reference.validIndustry(large, middle, small);
        // 업종 계층을 확정하지 못하면 소분류를 추정하지 않는다. 영향 범위도 지역 전체로 넓어진다.
        String industry = industryValid ? small : null;
        var issues = new ArrayList<Issue>();
        if (resolved.reason() != null) issues.add(impact(resolved.reason(), "행정동코드", sourceCode, resolved.regionCode(), industry));
        if (!sourceCode.matches("[0-9]{8}")) issues.add(impact("INVALID_REGION_CODE", "행정동코드", sourceCode, null, null));
        if (!industryValid) issues.add(impact("CLASSIFICATION_MISMATCH", "업종 대/중/소", large + "/" + middle + "/" + small, resolved.regionCode(), null));
        boolean validId = id.matches("[A-Za-z0-9]{1,40}");
        if (!validId) issues.add(impact("INVALID_STORE_ID", "상가업소번호", id, resolved.regionCode(), industry));
        // 법정동·좌표는 부가정보다. 이 값만 잘못됐다고 유효한 업소 수까지 줄이지 않는다.
        String legal = c.get(17);
        if (!legal.matches("[0-9]{10}")) {
            issues.add(warning("INVALID_LEGAL_DONG_CODE", "법정동코드", legal)); legal = null;
        }
        Double longitude = coordinate(c.get(37), -180, 180), latitude = coordinate(c.get(38), -90, 90);
        String locationStatus = "VALID";
        if (longitude == null || latitude == null) {
            // 한쪽 좌표만 남으면 지도에서 잘못 해석할 수 있으므로 둘을 함께 비운다.
            locationStatus = c.get(37).isBlank() && c.get(38).isBlank() ? "MISSING" : "INVALID";
            longitude = null; latitude = null;
            issues.add(warning("LOCATION_" + locationStatus, "경도/위도", c.get(37) + "/" + c.get(38)));
        }
        // 개수에 영향을 주는 문제만 HOLD로 판단한다. 저장할 수 없는 업소 ID는 REJECTED로 격리한다.
        boolean hold = issues.stream().anyMatch(Issue::affectsCount);
        var data = new Store(bounded(c.get(1), 300, "상호명", issues), bounded(large, 100, "상권업종대분류코드", issues),
                bounded(middle, 100, "상권업종중분류코드", issues), bounded(small, 100, "상권업종소분류코드", issues),
                legal, bounded(c.get(31), 500, "도로명주소", issues), longitude, latitude, locationStatus);
        return new ValidationRow(position, id, sourceCode, resolved.regionCode(), industry,
                !validId ? "REJECTED" : hold ? "HOLD" : "ACCEPTED", resolved.temporalStatus(), null, data, List.copyOf(issues));
    }

    static long number(String raw) {
        // 정상적인 천 단위 쉼표만 허용한다. 소수·음수·지수 표기·잘못된 쉼표는 자동 보정하지 않는다.
        if (!raw.matches("[0-9]+|[0-9]{1,3}(,[0-9]{3})+")) throw new IllegalArgumentException("비음수 정수가 아닙니다.");
        try {
            long value = Long.parseLong(raw.replace(",", ""));
            if (value > MAX_SAFE_INTEGER) throw new IllegalArgumentException("안전 정수 범위 초과");
            return value;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("정수 범위 초과", exception); }
    }

    private static Double coordinate(String raw, int min, int max) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= min && value <= max ? value : null;
        } catch (NumberFormatException exception) { return null; }
    }

    private static Issue warning(String code, String field, String raw) {
        return new Issue(code, field, raw, "WARNING", false, "NONE", null, null);
    }

    private static String bounded(String raw, int limit, String field, List<Issue> issues) {
        // 저장 길이를 넘는 부가값은 임의로 잘라 다른 값으로 만들지 않고 원문 경고와 함께 비운다.
        if (raw.codePointCount(0, raw.length()) <= limit) return raw;
        issues.add(warning("OPTIONAL_VALUE_TOO_LONG", field, raw));
        return null;
    }
}
