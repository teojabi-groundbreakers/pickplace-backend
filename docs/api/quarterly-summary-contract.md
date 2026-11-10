# 분기별 기본 현황 API·표시 규칙 v0.1

작성일: 2026-10-31 · 상태: **BE 제안 / FE 공동 검토 전 / 현황 API 미구현**

[Notion 검토 페이지](https://app.notion.com/p/40a2114f823a81a89dc5d3df4530398b)에도 같은 초안을 게시했다. 변경 시 버전·결정 기록을 함께 맞춘다.

선택한 행정동·업종의 한 분기 기본 통계를 제공하기 위한 계약이다. 데이터 정의서 v0.4와 T02 초안을 대조해 구체화했다. 이 문서 작성은 원천 재수집·통계 DB 적재·API 구현·FE 연동 완료를 의미하지 않는다.

> 핵심 기준: 같은 분기 · 실제 0 보존 · 누락은 null · DB 장애는 503. 현재는 구현 전 제안이다.

## 핵심 지표 한눈에 보기



| 응답 필드 | 화면 이름 | 원천 / 필드 | 고유 단위 | 단위 |
| --- | --- | --- | --- | --- |
| `residentPopulation` | 상주인구 | OA-22183 / TOT_REPOP_CO | 분기 + 행정동 | PERSON / 명 |
| `workerPopulation` | 직장인구 | OA-22184 / TOT_WRC_POPLTN_CO | 분기 + 행정동 | PERSON / 명 |
| `totalStoreCount` | 선택 업종 점포 수 | OA-22172 / SIMILR_INDUTY_STOR_CO | 분기 + 행정동 + 업종 | STORE / 개 |
| `totalFacilityCount` | 집객시설 수 | OA-22169 / VIATR_FCLTY_CO | 분기 + 행정동 | FACILITY / 개 |

<details>
<summary>지표 해석 · 집계 범위 · 저장 시 주의</summary>

- `totalFacilityCount`는 데이터 정의서의 저장 필드 제안 `attractingFacilityCount`와 같은 원천 총값의 공개 API 이름이다. 별개 지표나 세부 시설 합계가 아니다.
- 상주인구·직장인구·시설은 업종 공통값이다. 업종을 바꿔도 같은 동·분기의 값은 유지된다. 업종별 조회 후 이 값을 더해서 동 전체 값을 계산하지 않는다.
- 상주인구와 직장인구의 합을 고유 고객 수로 표시하지 않는다. 유동인구·구매 고객 구성과도 구분한다.
- 전체 점포 수는 일반 점포 `STOR_CO` + 프랜차이즈 `FRC_STOR_CO` 관계를 검증한다. 세 값이 모두 있고 불일치하면 해당 점포 지표는 INVALID로 처리한다. 누락값을 빼기 연산으로 생성하지 않는다.
- 총 집객시설 수를 세부 시설 개수의 단순 합으로 대체하지 않는다. 시설 집계 건수로 실제 위치 마커를 생성하지 않는다.
- 인구·시설의 일부 분기 값 반복은 기존 정의서에 기록된 원천 갱신 방식이다. 동일값만으로 중복 적재·수집 실패로 판정하거나 원천에 없는 분기를 전방 채우지 않는다.
- 분기별 제공값은 모두 3개월 합계를 의미하지 않는다. 네 지표를 월별 값으로 나누거나 8개 분기 값을 합산하지 않는다.

</details>

## 10. 기존 초안 대비 보완과 남은 결정

| 보완 | 내용 |
| --- | --- |
| 지표 이름 연결 | totalFacilityCount와 attractingFacilityCount의 원천 동일성 명시 |
| 상태 세분화 | 항목의 scalar 상태와 전체 PARTIAL을 구분, 혼합 무효 상태의 판정 명시 |
| 분기 미준비 | 기본 분기 부재 사유·null 메타데이터·명시 분기 미적재 규칙 명시 |
| 분기 메타데이터 | A6에 게시·적재·기본·가능 분기 포함. A5 분리 필수화하지 않음 |
| 오류 | 미등록 업종과 등록됐지만 미지원인 업종 구분을 새 코드로 제안 |
| 화면 | 실제 0, 자료 없음, 준비 중, 검증 보류, 장애의 문구·숫자 표시 구분 |

남은 공동 결정은 FE의 A5 사전 조회 필요 여부, LEGACY 선택 정책, 검증된 지원 분기 구간, 새 사유/오류 코드 수용이다. 현재 문서는 BE 제안이며 FE 검토·승인·실서버 연결은 미완료다.

## 상세 규칙

<a id="3-분기-선택-정책"></a>

<details>
<summary>3. 분기 선택 정책</summary>

| 필드 | 정의 |
| --- | --- |
| `requestedQuarter` | 요청 쿼리 quarter 원문. 생략하면 null |
| `appliedQuarter` | 응답 지표에 실제 적용한 분기. 기본 분기도 없으면 null |
| `latestPublishedQuarter` | 핵심 4종의 게시가 확인된 최신 공통 분기. 확인 불가 시 null |
| `latestLoadedQuarter` | 핵심 4종의 적재가 완료된 최신 공통 분기. 품질 검증·공개 승인 여부와는 구분 |
| `defaultQuarter` | 핵심 4종 모두 게시·적재·배치 검증·공개 반영이 완료된 최신 공통 분기 |
| `availableQuarters` | 같은 조건으로 공개 가능한 분기 목록, 내림차순·중복 없음. 빈 구간은 보존 |

`availableQuarters`와 기본 분기는 배치 단위의 공개 가능 여부다. 모든 동·업종의 모든 값이 존재한다는 뜻이 아니다. 개별 누락 때문에 기본 분기를 과거로 바꾸지 않는다. 통계·적재 이력·게시 버전 테이블은 [T11 설계](../database/statistics-schema-design.md)에서 구현했으며, 실제 적재·게시 전환·위 메타데이터 조회 로직은 아직 구현하지 않았다.

### 3.1 요청 처리

| 요청 | 응답 정책 |
| --- | --- |
| quarter 생략, 기본 분기 존재 | 기본 분기 적용 |
| quarter 생략, 기본 분기 없음 | 200, appliedQuarter=null, 전체 NOT_LOADED, 빈 분기 목록 |
| 형식 오류: `20265`, `2026-2` 등 | 400 INVALID_QUARTER |
| 유효 형식이나 지원 기간 밖 | 422 QUARTER_NOT_SUPPORTED |
| 지원 기간 안의 명시 분기 | 그 분기 그대로 조회. 일부/전체 미적재도 항목 상태로 표현 |

- 지원 기간은 마지막 공통 게시 분기를 기준으로 한 최근 8개 분기를 제안한다. 게시 확인 불가 시 검증해 고정한 지원 구간을 사용하고, 이마저 없으면 지원 구간 결정 전이다. 서버 날짜만으로 구간을 생성하지 않는다.
- 지원 구간의 기준과 실제 DB 설정은 구현 전에 기록한다. 예시의 `20243~20262`는 과거 검증 범위이며 현재 최신 제공 범위라는 주장이 아니다.
- `availableQuarters`에 없더라도 지원 구간 안의 명시 요청은 허용한다. 지표별 공개 가능한 배치가 있으면 해당 값만 제공한다. 자동 선택 가능한 분기와 부분 조회 가능한 분기를 구분한다.
- `20254` 다음은 `20261`이다. 단순 문자열 또는 정수 +1로 분기를 계산하지 않는다.
- 같은 응답의 네 지표는 appliedQuarter와 같은 분기를 사용한다. 누락된 직장인구만 직전 분기로 대체하지 않는다.
- 최신 정정본이 실패하면 직전에 공개 승인된 같은 분기의 배치를 유지할 수 있다. 응답에는 실제 사용한 배치의 버전·수집 시각을 넣는다. 실패한 새 배치로 정상 자료를 덮거나 다른 분기를 반환하지 않는다.

### 3.2 A5 가용성 조회의 처리

첫 화면에서는 **A6 현황 응답 meta에 분기 정보를 포함하는 안**을 제안한다. A5 `/data-availability`는 지금 구현 필수로 추가하지 않는다. 현황의 기본 요청으로 분기 선택 항목을 구성한다. FE가 값 조회 전에 독립 분기 선택을 꼭 요구하면 A5 분리 여부를 재검토한다. 기존 계획에서 확정 없이 삭제하지 않는다.

</details>

<a id="4-값항목-상태전체-상태"></a>

<details>
<summary>4. 값·항목 상태·전체 상태</summary>

### 4.1 지표 자료형

네 값은 0 이상의 안전 정수 JSON number 또는 null이다. 정수 최대치는 JavaScript 안전 정수 `9007199254740991`이며, 범위를 넘으면 조용히 반올림하지 않고 품질 오류로 공개를 보류한다. DB의 BIGINT 전체 범위와 공개 JSON 범위는 다르다.

`19.0` 같은 원천 숫자는 소수부가 0일 때만 19로 정규화한다. 음수·정수 항목의 소수·파싱 실패는 INVALID다. 문자열 숫자를 응답하지 않는다. 금액·비율의 DECIMAL 문자열 정책은 후속 지표 계약이며 네 핵심 정수에 적용하지 않는다.

### 4.2 항목 상태

| status | value | reasonCode | 판정 근거 | 화면 표시 |
| --- | --- | --- | --- | --- |
| AVAILABLE | 안전 정수, 0 포함 | null | 공개 가능한 원천 값 존재 | 단위와 숫자 |
| NO_DATA | null | SOURCE_ROW_ABSENT | 수집 완전성 확인 후 해당 키의 원천 행 없음 | 자료 없음 |
| NO_DATA | null | SOURCE_VALUE_MISSING | 원천 행은 있으나 이 값이 결측 | 해당 항목 값 없음 |
| NOT_LOADED | null | NOT_INGESTED | 해당 데이터셋·분기의 공개 가능한 수집/적재가 준비되지 않음 | 준비 중 |
| NOT_LOADED | null | DEFAULT_QUARTER_UNAVAILABLE | 요청 분기 생략, 기본 분기 미준비 | 기본 분기 준비 중 |
| INVALID | null | QUALITY_CHECK_FAILED | 거부 행·숫자 오류·점포 합계 오류 등 추적 가능한 품질 실패 | 검증 문제로 표시 보류 |

- `DEFAULT_QUARTER_UNAVAILABLE`은 기존 초안의 기본 분기 부재를 명확히 하기 위한 새 사유 제안이다. 과거 자료가 전혀 없다는 뜻이 아니다.
- NO_DATA는 원천 수집 성공과 대상 범위를 확인한 경우에만 사용한다. 부분 다운로드·수집 실패로 행을 못 찾은 것을 NO_DATA로 판정하지 않는다.
- 검증 실패로 격리된 원천 키는 INVALID로 추적한다. 정제 테이블에서 행이 없다는 이유만으로 SOURCE_ROW_ABSENT로 바꾸지 않는다.
- DB 조회 실패는 항목 상태가 아니라 HTTP 503이다. 네 값을 null로 감싼 정상 응답으로 반환하지 않는다.
- 사용 가능한 값은 AVAILABLE + 숫자이며, 사용할 수 없는 값은 null이다. 원천 0은 정상 값이고 null을 0으로 대체하지 않는다.

### 4.3 전체 상태 결정

핵심 네 항목만 대상으로 다음 순서를 적용한다.

1. 네 항목이 모두 같은 상태이면 해당 상태: AVAILABLE / NO_DATA / NOT_LOADED / INVALID.
2. 그 밖의 조합은 PARTIAL.

PARTIAL은 상태가 섞였다는 뜻이며 정상 값이 반드시 하나 이상 있다는 뜻은 아니다. 예를 들어 NO_DATA 2개 + NOT_LOADED 2개도 PARTIAL이다. FE는 전체 배지만으로 정상값 유무를 판단하지 않고 각 항목의 상태와 값을 확인한다. 지표 하나의 상태에는 PARTIAL을 사용하지 않는다.

</details>

<a id="5-현황-조회-api--구현-전-제안"></a>

<details>
<summary>5. 현황 조회 API — 구현 전 제안</summary>

`GET /api/administrative-dongs/{dongCode}/summary`

| 위치 | 필드 | 필수 | 자료형·규칙 |
| --- | --- | --- | --- |
| Path | dongCode | O | 원천 8자리 숫자 문자열, 등록된 행정동 |
| Query | businessTypeCode | O | CS + 숫자 6자리, 등록·지원 중인 업종 |
| Query | quarter | X | YYYYQ 5자리, Q는 1~4 |

아래 모든 수치·시각·적재 버전과 분기 메타데이터는 **계약 설명용 가상 값**이다. 실제 통계나 현재 DB 적재 결과가 아니다. 지역·업종 명칭은 고정 기준정보의 예시다.

### 5.1 정상 응답 / 200

```json
{
  "data": {
    "region": {
      "dongCode": "11110515",
      "dongName": "청운효자동",
      "officialDongCode": "1111051500",
      "officialCodeStatus": "CURRENT",
      "regionVersion": "seoul-reference-20262-mois-20261012-v1"
    },
    "businessTypeCode": "CS100010",
    "status": "AVAILABLE",
    "metrics": {
      "residentPopulation": {
        "value": 10000, "unit": "PERSON", "status": "AVAILABLE", "reasonCode": null,
        "quarterCode": "20262", "collectedAt": "2026-10-16T00:00:00Z",
        "sourceDataset": "OA-22183", "dataVersion": "example-resident-v1"
      },
      "workerPopulation": {
        "value": 20000, "unit": "PERSON", "status": "AVAILABLE", "reasonCode": null,
        "quarterCode": "20262", "collectedAt": "2026-10-16T00:00:00Z",
        "sourceDataset": "OA-22184", "dataVersion": "example-worker-v1"
      },
      "totalStoreCount": {
        "value": 50, "unit": "STORE", "status": "AVAILABLE", "reasonCode": null,
        "quarterCode": "20262", "collectedAt": "2026-10-16T00:00:00Z",
        "sourceDataset": "OA-22172", "dataVersion": "example-store-v1"
      },
      "totalFacilityCount": {
        "value": 0, "unit": "FACILITY", "status": "AVAILABLE", "reasonCode": null,
        "quarterCode": "20262", "collectedAt": "2026-10-16T00:00:00Z",
        "sourceDataset": "OA-22169", "dataVersion": "example-facility-v1"
      }
    }
  },
  "meta": {
    "requestId": "example-summary",
    "requestedQuarter": null,
    "appliedQuarter": "20262",
    "latestPublishedQuarter": "20262",
    "latestLoadedQuarter": "20262",
    "defaultQuarter": "20262",
    "availableQuarters": ["20262", "20261"]
  }
}
```

### 5.2 일부 자료 없음 / 200

다음은 5.1의 완전한 응답에서 적용할 **변경 부분**이다. 다른 세 지표와 meta를 삭제하지 않는다. 정상적인 실제 시설 0개도 보존한다.

```json
{
  "data": {
    "status": "PARTIAL",
    "metrics": {
      "workerPopulation": {
        "value": null, "unit": "PERSON", "status": "NO_DATA",
        "reasonCode": "SOURCE_ROW_ABSENT", "quarterCode": "20262",
        "collectedAt": "2026-10-16T00:00:00Z",
        "sourceDataset": "OA-22184", "dataVersion": "example-worker-v1"
      }
    }
  }
}
```

위 JSON은 실제 API의 축약 응답 형식이 아니다. 구현·Mock에는 항상 네 metric 키와 공통 meta가 있는 전체 객체를 사용한다.

### 5.3 기본 분기 없음 / 200

요청 분기를 생략했고 기본 분기가 없을 때의 **meta 전체와 항목별 생성 규칙**이다.

```json
{
  "requestId": "example-no-default",
  "requestedQuarter": null,
  "appliedQuarter": null,
  "latestPublishedQuarter": "20262",
  "latestLoadedQuarter": null,
  "defaultQuarter": null,
  "availableQuarters": []
}
```

region·businessTypeCode는 검증된 선택을 유지한다. 네 metric 모두 value=null, status=NOT_LOADED, reasonCode=DEFAULT_QUARTER_UNAVAILABLE, quarterCode=null, collectedAt=null, dataVersion=null이다. unit·sourceDataset은 고정된 각 지표 값을 유지하고 전체 status는 NOT_LOADED다. 이미 적재된 다른 분기의 자료까지 없다고 안내하지 않는다.

### 5.4 미적재 명시 분기

지원 구간 안에서 사용자가 분기를 명시하면 requestedQuarter와 appliedQuarter를 그 값으로 유지한다. 아직 공개 배치가 없는 항목은 NOT_LOADED + NOT_INGESTED이며 value/collectedAt/dataVersion은 null이다. 해당 항목의 quarterCode는 명시한 분기를 유지한다. 부분 공개된 다른 항목은 같은 분기의 검증된 값을 반환한다. 기본 분기가 더 과거여도 자동 이동하지 않는다.

### 5.5 응답 필드 불변 조건

| 필드 | 규칙 |
| --- | --- |
| region | 목록 계약과 동일한 코드·이름·공식 대응·상태·버전. CURRENT 예시가 LEGACY 선택 승인을 의미하지 않음 |
| businessTypeCode | 등록·지원 검증한 요청 업종 문자열 |
| metrics | 네 키 필수. 값 없는 키도 삭제하지 않음 |
| value·status·reasonCode | 4.2의 조합을 준수 |
| quarterCode | appliedQuarter와 동일. 기본 분기 미준비일 때만 null |
| collectedAt | 응답에 사용한 원천 배치의 실제 수집 완료 UTC ISO-8601. 요청 시각/DB 적재 시각으로 대체하지 않음 |
| dataVersion | 사용한 데이터셋 배치/정정본 식별자. 지역 대응 버전과 구분 |
| sourceDataset·unit | 값이 없더라도 고정된 자료 식별자·단위를 유지 |
| meta | 5.1의 일곱 키 필수. nullable 필드는 null로 유지 |

NO_DATA는 행이 없어도 원천 수집을 완료한 배치의 collectedAt·dataVersion을 제공한다. NOT_LOADED는 공개된 배치가 없어 두 값을 null로 둔다. INVALID는 품질 실패를 확인한 원천 배치가 있다면 해당 수집 시각·버전을 제공하며 내부 오류·원문은 노출하지 않는다.

</details>

<a id="6-http-오류"></a>

<details>
<summary>6. HTTP 오류</summary>

| HTTP | code | 경우 |
| --- | --- | --- |
| 400 | INVALID_REGION_CODE | dongCode 8자리 숫자 형식 오류 |
| 400 | INVALID_REQUEST | businessTypeCode 누락·형식 오류 |
| 400 | INVALID_QUARTER | quarter 형식 또는 Q 범위 오류 |
| 404 | REGION_NOT_FOUND | 형식은 유효하지만 등록되지 않은 원천 동 |
| 404 | BUSINESS_TYPE_NOT_FOUND | 형식은 유효하지만 미등록 업종. 새 오류 코드 제안 |
| 422 | BUSINESS_TYPE_NOT_SUPPORTED | 등록됐지만 서비스 미지원 업종 |
| 422 | QUARTER_NOT_SUPPORTED | 설정된 지원 기간 밖의 분기 |
| 500 | INTERNAL_ERROR | 예상하지 못한 서버 오류 |
| 503 | DATA_SERVICE_UNAVAILABLE | DB 연결·조회 자체 실패 |

공통 오류는 error + meta 형식과 동일한 X-Request-Id를 사용한다. 404·422·500·503을 통계 자료 없음으로 표시하지 않는다. 동시에 여러 입력이 잘못됐다면 형식 검증 후 존재·지원 여부를 검사하되, 여러 필드 오류의 우선순서는 고정 계약으로 약속하지 않는다.

```json
{
  "error": {
    "code": "INVALID_QUARTER",
    "message": "분기는 YYYYQ 형식이며 Q는 1~4여야 합니다.",
    "details": [{ "field": "quarter", "reason": "INVALID_FORMAT" }]
  },
  "meta": { "requestId": "example-invalid-quarter" }
}
```

BUSINESS_TYPE_NOT_FOUND는 현재 ErrorCode enum에 없으며 이번 문서에서 추가 제안만 했다. 구현 시 반영하거나 공동 검토에서 기존 코드로 통합하기로 결정해야 한다. 나머지 오류 식별자가 enum에 존재해도 현황 API 구현이 완료됐다는 뜻은 아니다.

</details>

<a id="7-화면-표시-규칙"></a>

<details>
<summary>7. 화면 표시 규칙</summary>

| 상황 | 표시·동작 |
| --- | --- |
| 정상 지표 | `10,000명`, `50개` 등 정수·단위. 인구에 선택 업종 고객 수라는 설명 금지 |
| 유효한 0 | `0명` / `0개`로 표시. 빈 상태와 구분 |
| NO_DATA | 숫자 대신 `—`, 원천 행 없음/값 없음의 안내 |
| NOT_LOADED | `준비 중`, 자동으로 예전 분기로 이동하지 않음 |
| INVALID | `표시 보류`, 품질 검증 문제를 안내. 잘못된 원본 값 비노출 |
| PARTIAL | 항목별 정상값 유지, 부족·미준비 항목 안내. 정상값이 하나라도 있다고 가정하지 않음 |
| 기본 분기 없음 | 기본 현황 준비 안내. 기본 분기·선택값 미확정 상태를 정상값 0으로 표시하지 않음 |
| 명시한 분기 미적재 | 선택 분기 유지, 준비 상태 안내. availableQuarters에 있는 다른 분기로 사용자가 직접 변경 가능 |
| 4xx/5xx·네트워크·타임아웃 | 정상 통계 영역과 구분한 오류·재시도. 데모 수치나 items: []로 대체하지 않음 |

- 제목은 `청운효자동 · 커피·음료 · 2026년 2분기`처럼 표시하고 실제 적용 분기를 사용한다. 분기 미적용일 때 날짜를 만들어 넣지 않는다.
- 각 카드의 기준 분기와 실제 수집일을 구분한다. 수집일이 최신이어도 통계가 당일 수치라는 뜻은 아니다.
- 출처에는 서울 열린데이터광장 / 서울신용보증재단, 자료명, 기준 분기, 공식 링크를 표시한다. sourceDataset을 검증된 아래 출처 사전에 대응한다.
- 인구·시설은 업종 공통값이라는 설명을 표시한다. 상주인구+직장인구를 고객 합계로 만들지 않는다.
- 연속된 여러 분기의 같은 원천 값은 그대로 표시하되 월별 추이나 실제 성장으로 해석하지 않는다. 초기 현황에는 증감률 배지를 추가하지 않는다.
- 지역·업종·분기 변경 시 이전 요청을 취소하거나 순서를 검사해 오래된 응답이 새 선택을 덮지 않도록 한다. 응답 적용 시 요청 코드와 appliedQuarter를 확인한다.
- LEGACY·미확인 공식 대응의 선택 허용/경고는 기존 목록 계약의 미확정 사항이다. 본 문서로 현재 지역 선택을 승인하거나 원천 통계를 새 경계에 배분하지 않는다.

</details>

<a id="8-구현에-필요한-데이터책임"></a>

<details>
<summary>8. 구현에 필요한 데이터·책임</summary>

| BE | FE |
| --- | --- |
| 네 원천의 고유키 단위 보존 | 통신 DTO와 화면 모델 분리 |
| 배치별 수집 완전성·적재·검증·공개 이력 | 기본 분기·명시 분기와 선택 상태 유지 |
| 정상 행·결측값·원천 행 부재·거부 행 구분 | 항목 status/reasonCode에 따른 표시 |
| 분기별 공개 가능한 배치 선택 | 정수 0·null·오류 구분, 부분 결과 유지 |
| 공통 오류·원천/지역 버전·요청 ID 반환 | 출처·기준 분기·수집일 안내 |

통계 DB에는 분기·동(점포는 업종 추가)의 고유키 외에 원천 버전·수집 시각·품질 상태가 필요하다. 원천 거부 키와 배치 상태를 조회할 근거도 남겨야 한다. 정제 테이블에 행이 없는지만으로 네 상태를 판정하는 설계는 피한다. 테이블명·DDL·Flyway는 다음 DB 설계 작업에서 확정한다.

</details>

<a id="9-검수-시나리오"></a>

<details>
<summary>9. 검수 시나리오</summary>

| ID | 입력·조건 | 기대 결과 |
| --- | --- | --- |
| S01 | 네 지표 유효, 시설 0 | 전체 AVAILABLE, 실제 0 보존 |
| S02 | 수집 성공 후 직장인구 행 없음 | 직장 NO_DATA, 나머지 유지, 전체 PARTIAL |
| S03 | 원천 행의 총값만 결측 | NO_DATA + SOURCE_VALUE_MISSING, 0 대체 금지 |
| S04 | 명시 분기의 일부 원천 미적재 | 같은 분기의 유효값 유지, 미적재 항목 NOT_LOADED |
| S05 | 기본 분기 없음 | appliedQuarter=null, 네 항목 NOT_LOADED + 기본 분기 부재 사유 |
| S06 | 네 원천 수집 성공, 대상 키 모두 없음 | 전체 NO_DATA, 각 배치의 시각·버전 존재 |
| S07 | 점포 전체/일반/프랜차이즈 불일치 | 점포 INVALID, 다른 지표 보존 |
| S08 | NO_DATA와 NOT_LOADED만 혼합 | 전체 PARTIAL, 정상 값 없는 상태도 정확히 안내 |
| S09 | 20254와 20261 | 연도 전환 순서 정확, 분기 간 임의 보간 금지 |
| S10 | 특정 키 누락, 더 과거에는 있음 | 요청/기본 분기 유지, 과거 값 대체 금지 |
| S11 | 게시만 있고 미적재 | 최신 게시 분기를 기본값으로 선택하지 않음 |
| S12 | 소수부 0 / 음수 / 정수 소수 / 안전 범위 초과 | 정상 정수화 또는 품질 보류 구분 |
| S13 | 부분 다운로드·거부 행·정제 행 없음 | 원천 행 없음으로 오판하지 않음 |
| S14 | DB 장애·복구 | 503 공통 오류 후 정상 조회 복구 |
| S15 | 신규 정정 배치 실패 | 같은 분기의 기존 정상 배치 유지, 버전·수집일 정확 |
| S16 | 서로 다른 동·업종·분기 요청 경쟁 | 이전 결과가 최신 선택을 덮지 않음 |
| S17 | 잘못된 코드·분기·미지원 업종 | 400/404/422와 정상 자료 부족 구분 |
| S18 | OpenAPI·Mock·실서버·FE | 네 필수 키·자료형·상태·시각·원천 정보 일치 |

이 표는 검수 계획이다. 이번 문서 작업만으로 S01~S18의 서버·DB·FE 테스트 통과를 주장하지 않는다.

FE Mock 작성에 사용할 [전체 응답 예시 8종](examples/quarterly-summary-responses.json)을 함께 제공한다. HTTP 상태와 body를 묶은 문서용 fixture이며, 실제 API가 fixtures 래퍼를 반환하는 형식은 아니다. 정상·부분 누락·전체 자료 없음·기본 분기 없음·명시 분기 미적재·품질 오류·정상값 없는 혼합·입력 오류를 포함한다.

</details>

<a id="1-첫-현황-화면의-범위"></a>

<details>
<summary>1. 첫 현황 화면의 범위</summary>

**행정동·지원 업종 선택 → 기준 분기 결정 → 상주인구·직장인구·점포·집객시설 표시**를 첫 완료 범위로 둔다.

| 포함 | 후속 검토 |
| --- | --- |
| 핵심 지표 4개, 같은 분기의 값·상태·출처 | 매출·길단위인구·월별 차트 |
| 분기 선택, 누락·미적재·품질 오류 안내 | 전분기 증감률·비교 지역 평균·파생 점수 |
| 원천 지역명·코드 대응 상태·버전 | AI 점수·진단·성공 가능성 |
| 자료명·제공기관·수집 시각 | 개별 점포/시설 마커·행정동 경계 |

통계가 부족해도 사용 가능한 값은 유지한다. 기본 현황 조회에 AI 호출을 요구하지 않는다. 지도에서 얻은 10자리 H 코드의 변환 계약은 이번 8자리 원천 코드 조회와 구분한다.

</details>

<a id="11-출처근거"></a>

<details>
<summary>11. 출처·근거</summary>

- [데이터 정의서 v0.4](https://app.notion.com/p/3fa2114f823a818c9bd8e2664cbcd618): 원천 필드·단위·기존 검수·갱신 방식·공개 보류 근거.
- [T02 계약 초안](https://app.notion.com/p/3f42114f823a8143acb4fc2f33187116): A5/A6, 분기·오류·누락 규칙.
- [목록·공통 통신 계약](fe-be-contract.md): 지역·업종 선택과 응답 구조.
- [상주인구 OA-22183](https://data.seoul.go.kr/dataList/OA-22183/S/1/datasetView.do)
- [직장인구 OA-22184](https://data.seoul.go.kr/dataList/OA-22184/S/1/datasetView.do)
- [점포 OA-22172](https://data.seoul.go.kr/dataList/OA-22172/S/1/datasetView.do)
- [집객시설 OA-22169](https://data.seoul.go.kr/dataList/OA-22169/S/1/datasetView.do)

공식 링크는 기존 정의서의 근거다. 이번 작업에서 원천 전체를 재수집하거나 최신 제공 분기·갱신 정책을 새로 검증하지 않았다. 새 수집·구현 단계에서 제공 조건과 실제 데이터를 다시 확인한다.

</details>
