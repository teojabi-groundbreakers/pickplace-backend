# T15 현황 조회 기준·지표 가용성 판정

현재 선택 기준과 승인 DEFAULT 묶음을 고정하고 정상값·결측·품질 보류를 구분한다. **HTTP API와 인구·업소 집계 조회 SQL은 T17·T18에서 연결한다.**

기준: [현황 API 명세 v0.4](https://app.notion.com/p/40a2114f823a81a89dc5d3df4530398b) · [T15 작업](https://app.notion.com/p/3f42114f823a8159a7d7ff64e6d6caf2).

## 구성 요소

| 클래스 | 책임 | 후속 사용 |
| --- | --- | --- |
| SummaryContextService | 형식 검사·읽기 트랜잭션·선택/DEFAULT/출처 검증 | withContext(regionCode, businessTypeCode, callback) |
| SummaryContextRepository | 현행 LOCALITY·소분류·DEFAULT·원본 출처 조회 | 사실 테이블 스캔 없음 |
| SummaryReadContext | 고정한 선택/현황 ID·월·게시 시각·원천 출처 | T17 SQL의 배치·분류·매핑 기준 |
| MetricEvidence | 원천 수록·행·대응/경계·집계 영향의 판정 입력 | T17이 실제 근거로 조립 |
| MetricAvailabilityPolicy | 공개 사유 우선순위·0/null 판정 | population / stores |
| MetricDecision | 값·상태·사유의 불변 결과 | T18 지표 DTO로 변환 |

신규 오류는 BUSINESS_TYPE_NOT_FOUND(404), SUMMARY_DATA_NOT_READY(503)다. 기존 공통 오류 처리로 응답할 수 있으나 신규 공개 경로를 추가하지 않는다. 개별 API의 OpenAPI 응답 명세는 T18에서 작성한다.

## 조회 순서

1. 업종 필수값 → H10 형식 → 소진공 코드 형식을 DB 연결 전에 검사한다. trim·숫자화·서울 코드 변환을 하지 않는다.
2. REQUIRES_NEW / REPEATABLE_READ / readOnly 트랜잭션을 연다.
3. NATIONWIDE_SELECTION의 승인 릴리스·연결 분류와 현행 LOCALITY·등록/지원 소분류를 검증한다.
4. DEFAULT 승인 묶음의 월·매핑·성공 배치·분류와 원본 해시·수집 시각 연결을 검증한다.
5. 콜백 안에서 고정한 배치의 사실/집계를 읽고 판정한다.

```java
return contexts.withContext(regionCode, businessTypeCode, context -> {
    // T17의 실제 SQL을 이 콜백 안에서 호출한다.
    // 인구: context.population().batchId() + H10
    // 업소: context.stores().batchId() + H10 + context.summary().classificationId() + 업종
    // evidence는 대응·경계·coverage·집계 영향까지 확인한 결과다.
    return policy.stores(context, evidence);
});
```

**콜백 종료 후의 SQL에는 같은 스냅샷 보장이 없다.** 콜백 안의 후속 조회는 같은 DataSource/트랜잭션을 사용한다. 별도 REQUIRES_NEW·비동기 SQL·외부 수집·AI 호출을 섞지 않는다. 조회에는 FOR UPDATE나 게시 mutex를 사용하지 않는다.

MAX월·최신 적재·지역별 자료가 있는 월로 기본값을 추정하지 않는다. MONTH 정정이 DEFAULT를 자동 변경하지 않으며 202609도 코드에 고정하지 않는다.

## 오류와 결측

| 상황 | 처리 |
| --- | --- |
| 선택 기준정보 미게시 | 503 REFERENCE_DATA_NOT_READY |
| 현행 LOCALITY 아님 / 소분류 미등록 | 404 REGION_NOT_FOUND / BUSINESS_TYPE_NOT_FOUND |
| 소분류 등록됐지만 지원 false | 422 BUSINESS_TYPE_NOT_SUPPORTED |
| 정상 선택 후 DEFAULT 없음 | 503 SUMMARY_DATA_NOT_READY |
| 게시된 분류·배치·원본 연결 오류 | 500 INTERNAL_ERROR |
| DB 연결·조회 실패 | 503 DATA_SERVICE_UNAVAILABLE |
| 승인 DEFAULT 있으나 지표 결측·보류 | 지표별 null·사유, 정상 지표 유지 |

현재 분류와 현황 분류가 다르면 업소만 CLASSIFICATION_MISMATCH다. 인구는 독립적으로 판정한다. 수집일·적재일·게시일을 원천 기준일로 대체하지 않는다.

## 상태·우선순위

| 조건 | status | value·reasonCode |
| --- | --- | --- |
| 안전한 비음수 정수, 실제 0 포함 | AVAILABLE | 숫자 / null |
| 실제 원천의 비공개 | UNAVAILABLE | null / SOURCE_SUPPRESSED |
| 분류 불일치·대응 미확정·경계 검토/불일치 | UNAVAILABLE | null / 해당 사유 |
| 집계 영향 있음·집계 HOLD | UNAVAILABLE | null / COUNT_INCOMPLETE |
| 숫자/품질 검증 실패 | UNAVAILABLE | null / QUALITY_CHECK_FAILED |
| 원천 지역·행·값 부재 | NO_DATA | null / SOURCE_REGION_ABSENT·SOURCE_ROW_ABSENT·SOURCE_VALUE_MISSING |

우선순위는 비공개 → 분류 불일치 → 대응 미확정 → 경계 검토 → 경계 불일치 → 집계 불완전 → 품질 실패 → 원천 결측이다. 여러 사유의 원문은 DB 이력에 보존한다.

- 97행 채택 + 집계 영향 3행 보류는 공개 97이 아닌 null이다.
- 업소 0은 같은 분류·유효한 coverage·완료 배포본·집계 영향 없음이 확인된 경우만 허용한다.
- 원천 지역 수록 범위가 없으면 업소 행이 없어도 0을 만들지 않는다.
- 무영향 좌표 경고 등을 countAffected에 넣지 않는다.
- 공개 숫자는 0~9,007,199,254,740,991 범위다.

## T17·T18 인계

| 작업 | 남은 구현 |
| --- | --- |
| T17 | 고정 batchId로 인구·집계·coverage·관련 품질 이슈 조회 |
| T17 | 원천→H10 대응과 공개 지역의 시점 호환을 검토해 RegionState 조립 |
| T17 | 인구 sourceRegionPresent를 고정 원천 버전으로 확인. 원천 지역 부재와 행 부재 구분 |
| T17 | 업소 집계 행이 없어도 REGION·REGION_INDUSTRY 영향 확인. coverageValid는 실제 근거로 판정 |
| T18 | 미정의/중복 query 검사 → withContext → JSON·단위·출처·meta 매핑, OpenAPI·HTTP 테스트 |
| T19 | 실제 FE의 선택 변경·늦은 응답·0/null·준비/장애·출처 표시 검수 |

RegionState.VALID를 상수로 넣거나 H10 문자열 일치만으로 경계 검토를 생략하지 않는다. 정책 입력에는 실제 조회 근거가 필요하다. 공개 월/LEGACY 조회·독립 월 선택 API·AI 가능 여부 판정은 이번 범위가 아니다.

## 검증

정책 단위 테스트와 T14의 작은 원본을 실제 적재한 MySQL 통합 테스트를 사용한다. 다른 연결의 선택/DEFAULT 게시 중에도 콜백의 후속 SQL이 최초 스냅샷을 유지하는지 검사한다. 개발 DB는 변경하지 않는다. 결과는 [검증 기록](summary-availability-validation.json)에 보관한다.
