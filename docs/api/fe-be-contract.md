# FE·BE 통합 API 명세 v0.2

작성일: 2026-10-31 · 상태: **BE 통합 제안 / FE 공동 검토 전**

구현 상태 확인: 2026-11-05 · PR #9·#10·#11 반영 기준

[Notion 검토 페이지](https://app.notion.com/p/40a2114f823a8133b662fb5aa49b457c)에도 같은 v0.2 초안을 게시했다. 두 문서 변경 시 버전과 결정 기록을 함께 맞춘다.

> 이번 문서는 목록 API와 공통 통신 규칙의 통합 초안이다. 공통 응답·자치구·행정동·지원 업종 조회는 구현됐다. 행정동의 추가 제안 필드와 FE 어댑터 전환·공동 검수는 별도로 남아 있다. 문서 작성이나 기존 API 병합을 FE 승인·연동 완료로 간주하지 않는다.

> 읽는 순서: 구현 상태와 남은 결정을 먼저 확인하고 필요한 API만 펼친다. 실제 호출 방법은 [현재 지원 API 안내](frontend-api-guide.md)를 참고한다.

## 2. API 목록과 구현 상태

| Method | 전체 경로 | 목적 | 현재 상태 |
| --- | --- | --- | --- |
| GET | `/api/health` | 서버 생존 확인 | 구현 |
| GET | `/api/health/ready` | DB 준비 상태 확인 | 구현 |
| GET | `/api/districts` | 서울 자치구 목록 | 구현, PR #9 |
| GET | `/api/districts/{districtCode}/administrative-dongs` | 자치구에 속한 원천 행정동 목록 | 기본 5필드 구현, PR #10. officialDongCode 추가는 제안 |
| GET | `/api/business-types` | 서비스 지원 업종 목록 | 구현, PR #11 |

- API 기준 URL을 `/api`로 설정한 FE에서는 `/districts`처럼 접두사를 제외한 경로를 전달한다. `/api/api/districts`가 되지 않게 한다.
- 기존 FE 초안의 `/catalog`를 추가로 구현하거나 기존 경로로 자동 우회하지 않는 안이다. FE의 개별 조회 전환에 대한 공동 확인이 필요하다.
- 목록은 페이징하지 않는다. 전국 서비스·대규모 검색은 이번 범위가 아니다.

## 11. 결정 기록과 남은 승인

| 항목 | 이번 문서의 안 | 상태 |
| --- | --- | --- |
| 목록 통신 형식 | 기존 BE 개별 경로·data.items·공통 meta 유지 | BE 제안, FE 확인 필요 |
| 자치구 형식 | PR #9 구현 유지 | BE 구현 완료, FE 연결 미검수 |
| 행정동·업종 필드/정렬 | 기본 필드·정렬은 PR #10·#11 구현 | officialDongCode 추가 제안·FE 확인 필요 |
| H10·LEGACY | 공식 대응 필드 제공, 지도 수용·선택 정책 별도 결정 | 미확정 |
| 목록 검색 범위 | 선택 자치구 내 검색 우선 | FE 전체 검색 요구 확인 필요 |
| 단계 구분 | 첫 목록 연결과 현황·AI·좌표 계약 분리 | 개발 범위 제안 |

- [x] FE·BE 원문·코드 대조
- [x] 목록·공통 통신·오류·빈 목록·FE 변경·검수 초안 작성
- [ ] FE 담당자 검토 및 결정 기록
- [x] 행정동 기본 5필드·정렬 및 지원 업종 API 구현
- [ ] officialDongCode 추가 제안 검토 및 FE 어댑터 적용
- [ ] OpenAPI·Mock·실서버 계약 검수

v0.1의 A2~A4와 공통 응답 규칙을 통합하고, 공식 대응 코드 필드·정렬·전환 작업을 제안했다. A5/A6·분기·결측·AI 규칙을 무단 확정하지 않는다. 승인 기록이 없으므로 T02 전체를 완료로 표시하지 않는다.

## API별 계약

<a id="4-자치구-목록"></a>

<details>
<summary>4. 자치구 목록</summary>

`GET /api/districts` · 요청 파라미터 없음

```json
{
  "data": {
    "items": [
      { "districtCode": "11110", "districtName": "종로구" },
      { "districtCode": "11140", "districtName": "중구" }
    ]
  },
  "meta": { "requestId": "example-districts" }
}
```

| 필드 | 자료형 | 필수 | 의미 |
| --- | --- | --- | --- |
| `districtCode` | string, 숫자 5자리 | O | 원천 자치구 코드 |
| `districtName` | string | O | 자치구 표시 이름 |

- 정렬: `districtCode` 오름차순.
- 스냅샷 전체 적재 시 25개. 예시는 길이를 줄인 일부 항목이며 완전한 목록이 아니다.
- 미적재 등으로 행이 없으면 200 + `items: []`. FE는 빈 상태를 표시한다.
- 원천 버전·적재 시각·Entity 관리 컬럼을 항목에 직접 노출하지 않는다.
- 기존 구현의 정확한 동작은 [기준정보 조회 API](reference-api.md)를 함께 참고한다.

</details>

<a id="5-행정동-목록--구현-전-제안"></a>

<details>
<summary>5. 행정동 목록 — 기본 구현·추가 필드 제안</summary>

`GET /api/districts/{districtCode}/administrative-dongs`

현재 구현은 dongCode·dongName·districtCode·officialCodeStatus·regionVersion 5필드다. 아래 JSON과 표에는 **아직 응답하지 않는 officialDongCode 추가 제안**이 포함돼 있다. 실제 호출 계약은 [현재 지원 API 안내](frontend-api-guide.md)와 [기준정보 API](reference-api.md)를 따른다.

| Path | 자료형 | 검증 |
| --- | --- | --- |
| `districtCode` | string | 숫자 5자리, 등록된 부모 자치구 |

```json
{
  "data": {
    "items": [
      {
        "dongCode": "11110515",
        "dongName": "청운효자동",
        "districtCode": "11110",
        "officialDongCode": "1111051500",
        "officialCodeStatus": "CURRENT",
        "regionVersion": "seoul-reference-20262-mois-20261012-v1"
      }
    ]
  },
  "meta": { "requestId": "example-dongs" }
}
```

| 필드 | 자료형 | 필수 | 의미 |
| --- | --- | --- | --- |
| `dongCode` | string, 숫자 8자리 | O | 통계 원천 행정동 코드. 목록 선택의 식별자 |
| `dongName` | string | O | 원천 행정동 이름 |
| `districtCode` | string, 숫자 5자리 | O | 소속 자치구. 요청한 코드와 일치 |
| `officialDongCode` | string, 숫자 10자리 또는 null | O | 검증한 공식 코드 대응. 미확인이면 null |
| `officialCodeStatus` | CURRENT / LEGACY / UNVERIFIED | O | 공식 코드 대응 확인 상태 |
| `regionVersion` | string | O | 코드 대응 자료의 버전 |

- 구현 정렬: `dongName`, `dongCode` 오름차순. 이름 정렬은 DB의 한글 정렬 규칙을 따르고 같은 이름은 코드로 구분한다.
- 등록된 자치구에 동이 없으면 200 + 빈 배열. 부모 자치구가 미등록이면 404 `REGION_NOT_FOUND`.
- 원천 스냅샷의 425개 동을 보존한다. 현행 목록으로 과거 통계를 임의 재배분하지 않는다.
- `officialDongCode`는 T02 v0.1에 없던 대응 식별자 추가 제안이며 현재 API 응답에는 없다. 추가가 확정되면 DB의 `official_dong_code`에서 읽고 FE가 `dongCode + '00'`으로 생성하지 않는다.
- CURRENT는 공식 대응 확인 상태이며 모든 기간의 경계 일치를 보증하지 않는다. LEGACY 선택 허용 여부는 FE 공동 검토 사항이다. 상태를 누락하거나 현행 코드처럼 표시하지 않는다.
- 목록에는 중심 좌표를 약속하지 않는다. 기존 FE `Neighborhood.center` 필수 조건은 선택 목록 모델에서 분리해야 한다. 지도 이동에 필요한 좌표 계약은 별도 작업이다.

</details>

<a id="6-지원-업종-목록--구현-전-제안"></a>

<details>
<summary>6. 지원 업종 목록 — 구현</summary>

`GET /api/business-types` · 요청 파라미터 없음

```json
{
  "data": {
    "items": [
      { "businessTypeCode": "CS100010", "businessTypeName": "커피·음료", "supported": true },
      { "businessTypeCode": "CS100008", "businessTypeName": "분식", "supported": true },
      { "businessTypeCode": "CS100005", "businessTypeName": "제과점", "supported": true }
    ]
  },
  "meta": { "requestId": "example-business-types" }
}
```

| 필드 | 자료형 | 필수 | 의미 |
| --- | --- | --- | --- |
| `businessTypeCode` | string, `CS` + 숫자 6자리 | O | 원천 업종 코드 |
| `businessTypeName` | string | O | DB의 화면 표시 이름 `display_name` |
| `supported` | boolean | O | 이 목록은 true인 지원 업종만 반환 |

- 지원 여부는 DB의 `is_supported`로 판단하고 코드 3개를 API 로직에 하드코딩하지 않는다.
- 구현 정렬: `display_order`, `business_type_code` 오름차순. 동률 순서를 코드로 고정한다.
- 초기 스냅샷은 위 3개를 활성화한다. 전체 100업종 저장과 서비스 지원 범위는 다르다.
- 지원 업종이 없으면 200 + 빈 배열. FE는 분석/조회 진행을 막고 안내하며 데모 목록으로 대체하지 않는다.
- 대분류 계층은 이번 응답에 추가하지 않는다. FE의 `categories` 구조를 유지하려면 화면용 그룹임을 구분하고 공식 원천 분류인 것처럼 만들지 않는다.

</details>

<a id="3-공통-통신-규칙"></a>

<details>
<summary>3. 공통 통신 규칙</summary>

| 항목 | 기준 |
| --- | --- |
| 인코딩·본문 | UTF-8 JSON, 정상 목록의 `Content-Type`은 `application/json` |
| 코드 | 선행 0과 원문을 보존하는 문자열. FE가 숫자로 변환하거나 공백을 제거해 다른 코드로 보내지 않음 |
| 필수·선택 필드 | 표의 필수 필드는 빈 문자열·누락 불가. nullable로 표시한 필드는 키를 유지하고 `null` 사용 |
| 추가 필드 | FE는 사용하지 않는 추가 필드를 허용하되 알려진 필수 필드의 형식은 검증 |
| 응답 일관성 | 성공은 `data + meta`, 오류는 `error + meta`. 실패를 HTTP 200의 빈 목록으로 감추지 않음 |
| 요청 추적 | `X-Request-Id` 응답 헤더와 `meta.requestId`는 동일한 비어 있지 않은 문자열 |
| 클라이언트 요청 ID | 선택 사항. 현 구현은 영문·숫자·`-`·`_` 1~64자만 유지하고 그 외에는 서버에서 새 값 발급 |
| 오류 표시 | 분기는 HTTP 상태와 `error.code`로 판단. 사용자용 기본 문구를 FE에서 제공하고 안전한 서버 메시지 표시 여부는 FE 정책으로 정함 |
| 내부 정보 | SQL·DB 주소·스택트레이스·인증값을 응답에 포함하지 않음 |

### 3.1 성공·빈 목록

```json
{
  "data": { "items": [] },
  "meta": { "requestId": "example-empty" }
}
```

정상 조회 대상이 존재하지만 결과가 없으면 **200 + 빈 배열**이다. `null`, 필드 생략, 204로 바꾸지 않는다. 목록은 정렬된 배열이며 같은 코드가 중복되지 않는다.

### 3.2 오류

```json
{
  "error": {
    "code": "DATA_SERVICE_UNAVAILABLE",
    "message": "현재 데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    "details": []
  },
  "meta": { "requestId": "example-unavailable" }
}
```

`error.code`, `error.message`, `error.details`, `meta.requestId`는 필수다. `details`는 항상 배열이며 각 항목은 `field`, `reason` 문자열이다. 오류 코드와 사유는 고정 식별자로 처리하고 메시지 문구로 분기하지 않는다. 목록 빈 결과와 DB 장애를 동일하게 표시하지 않는다.

</details>

<a id="7-목록-api-오류상태-표"></a>

<details>
<summary>7. 목록 API 오류·상태 표</summary>

| HTTP | code | 적용 상황 | FE 처리 |
| --- | --- | --- | --- |
| 200 | 없음 | 정상 목록 또는 빈 결과 | 목록 또는 빈 상태 |
| 400 | INVALID_REGION_CODE | A3 자치구 코드가 숫자 5자리 형식이 아님 | 선택값 확인, 입력 수정 |
| 400 | INVALID_REQUEST | 그 밖의 잘못된 요청 형식 | 오류 안내 |
| 404 | REGION_NOT_FOUND | 형식은 맞지만 부모 자치구 미등록 | 선택 상태 재확인 |
| 404 | NOT_FOUND | 없는 URL | 연동 경로 확인 |
| 405 | METHOD_NOT_ALLOWED | 미지원 HTTP 메서드 | 개발 오류로 처리, Allow 헤더 보존 |
| 500 | INTERNAL_ERROR | 예상하지 못한 서버 오류·반환값 검증 실패 | 오류 안내·추적 ID |
| 503 | DATA_SERVICE_UNAVAILABLE | DB 연결·조회 실패 | 오류·명시적 재시도 |

- A3의 400/404 동작은 구현 시 검증할 계약이다. 현재 A2 구현에서 해당 오류가 발생한다는 뜻이 아니다.
- Spring이 판정한 그 밖의 4xx는 해당 상태와 `INVALID_REQUEST`로 전달한다. 이미 제공된 안내 헤더를 보존한다. 415의 Accept 보존은 405 Allow와 같은 필수 표준 요건이라는 뜻은 아니다.
- 입력 오류의 `details.reason`은 REQUIRED / INVALID_FORMAT / INVALID_VALUE를 사용한다. 메시지나 원본 입력값을 사유 코드에 넣지 않는다.
- 통계의 NO_DATA·NOT_LOADED, AI의 입력 부족·모델 장애는 이 목록 오류 표에 합치지 않는다. 이후 계약에서 별도로 정의한다.

### 7.1 잘못된 부모 코드 / 400 — 제안

```json
{
  "error": {
    "code": "INVALID_REGION_CODE",
    "message": "지역 코드 형식이 올바르지 않습니다.",
    "details": [{ "field": "districtCode", "reason": "INVALID_FORMAT" }]
  },
  "meta": { "requestId": "example-invalid-region" }
}
```

### 7.2 미등록 부모 / 404 — 제안

```json
{
  "error": {
    "code": "REGION_NOT_FOUND",
    "message": "해당 지역을 찾을 수 없습니다.",
    "details": []
  },
  "meta": { "requestId": "example-region-not-found" }
}
```

</details>

<a id="8-fe-변경-작업"></a>

<details>
<summary>8. FE 변경 작업</summary>

FE의 현재 문서는 초안이지만 호출·런타임 검증 코드는 이미 있다. HTTP 경로 교체만으로 연동 완료가 되지 않는다.

| 대상 | 변경 내용 |
| --- | --- |
| `src/lib/api.ts` | `/catalog` 호출을 개별 목록 조회로 전환. 성공 시 공통 응답 검증 후 data를 반환. 오류는 error.code·meta.requestId 추출 |
| `src/types/analysis.ts` | 통신용 DistrictItem·DongItem·BusinessTypeItem·공통 응답 타입 분리. 화면 내부 Option의 code/name 사용은 유지 가능 |
| `src/lib/validation.ts` | 공통 응답과 API별 items 검증. 빈 배열 허용. 목록 항목에서 중심 좌표를 필수로 요구하지 않음 |
| 목록 상태 | 자치구·업종은 최초 진입 시 독립 조회. 동은 자치구 선택 후 조회. 로딩·빈 결과·실패 상태를 구분 |
| 지역 변경 | 부모 변경 시 이전 동 선택 초기화. 이전 동 조회 취소 또는 응답 순서 검사로 늦은 응답 무시 |
| 코드 변환 | 응답 원문 식별자를 보존. code/name으로 이름을 바꾸는 화면 어댑터와 공식 코드 매핑을 구분 |
| 실패 처리 | 실패를 빈 목록·데모 데이터로 대체하지 않음. 서버 코드·네트워크·타임아웃·취소를 구분 |

초기 검색은 선택한 자치구의 로드된 동을 대상으로 하는 안이다. 기존 FE의 서울 전체 동 검색을 첫 단계부터 유지할지는 공동 확인이 필요하다. 유지한다면 자치구별 동 목록의 제한된 병렬 선조회·세션 내 재사용 비용을 검토하고, 이 변경 없이 전체 검색이 유지된다고 가정하지 않는다.

목록 통신 규칙을 분석 응답에 자동 적용하지 않는다. 기존 `isAnalysis`의 월별 데이터·점수 필수 조건과 분석 부족 상태는 후속 계약에 맞춰 별도로 변경한다.

</details>

<a id="9-연결-환경관측-규칙"></a>

<details>
<summary>9. 연결 환경·관측 규칙</summary>

- FE가 같은 출처의 `/api` 프록시를 쓰는 방식을 개발 기본안으로 둔다. 별도 출처는 허용 origin과 CORS 정책을 별도 설정한다.
- `X-Request-Id`를 FE가 읽을 때 별도 출처에서는 응답 노출 헤더 설정을 확인한다. FE가 해당 요청 헤더를 전송한다면 요청 허용 헤더·사전 요청도 검수한다. 실제 CORS 설정 구현은 이번 문서 작업에 포함하지 않는다.
- API 문서에는 200·해당 입력 오류·500·503과 공통 오류 스키마를 명시하는 안이다. 현재 OpenAPI의 자치구 503 누락은 별도 구현 작업으로 남긴다.
- FE의 현재 제한은 30초이고 DB 연결 풀 기본 대기도 약 30초다. FE가 503 전에 타임아웃될 수 있으므로 응답 시간 보장으로 기록하지 않는다. 서버 DB·조회 제한과 FE 제한을 설정 작업에서 함께 결정한다.
- 인증·운영 Swagger 공개 범위·비밀 환경변수·실제 서버 URL은 문서 예제로 확정하지 않는다.

</details>

<a id="10-공동-검수-조건"></a>

<details>
<summary>10. 공동 검수 조건</summary>

| ID | 검수 시나리오 | 기대 결과 |
| --- | --- | --- |
| C01 | 자치구 정상 조회 | 코드순, 코드·이름, 공통 응답·요청 ID 일치 |
| C02 | 행정동·업종 정상 조회 | 명세 필드·자료형·정렬 일치, 부모 코드 일치 |
| C03 | 정상 빈 목록 | HTTP 200, items: [], FE 빈 상태 |
| C04 | 부모 코드 형식 오류·미등록 | 400 INVALID_REGION_CODE / 404 REGION_NOT_FOUND 구분 |
| C05 | DB 장애 | 503 공통 오류, 내부 정보 미노출, FE가 빈 목록으로 위장하지 않음 |
| C06 | 자치구 빠른 변경 | 늦은 동 응답이 새 선택을 덮어쓰지 않음 |
| C07 | 잘못된 성공 JSON | 누락·잘못된 자료형을 FE가 형식 오류로 처리 |
| C08 | 업종·지역 식별자 | 서버 문자열 유지, 데모 코드·임의 변환 미사용 |
| C09 | OpenAPI와 Mock/실서버 | 경로·성공 DTO·빈 배열·오류 스키마·HTTP 상태 대조 |
| C10 | 미지원 운영 범위 | 중심 좌표·H10 자동 매핑·AI 결과가 준비됐다고 표시하지 않음 |

문법 검사·테스트 통과·FE 공동 승인은 서로 다르다. API별 검수 결과를 남기고 구현 전 항목을 완료로 체크하지 않는다.

</details>

<a id="1-적용-범위"></a>

<details>
<summary>1. 적용 범위</summary>

첫 연결 흐름은 **서울 자치구 선택 → 행정동 선택 → 지원 업종 선택**이다. 기존 BE 개별 목록 API를 유지하고 FE 통신 어댑터를 맞추는 안을 제안한다.

| 범위 | 이번 명세에서 정하는 내용 |
| --- | --- |
| 공개 경로 | 자치구·행정동·업종 목록과 기본 URL |
| JSON | 필드명·자료형·공통 성공/오류 형식 |
| 목록 상태 | 빈 결과·입력 오류·DB 장애 구분 |
| FE 전환 | 기존 카탈로그 호출·타입·검증·검색의 변경 범위 |
| 검수 | Mock·OpenAPI·실서버 응답 대조 기준 |

분기별 현황의 상세 DTO·상태 판정, H10 지도 선택 연계, AI 성공 결과, 개별 장소·경계 지도 API는 별도 계약 작업이다. 기존 T02 v0.1의 현황 규칙은 검토 이력으로 유지하며 이번 문서가 확정하거나 삭제하지 않는다.

현황의 후속 제안은 [분기별 기본 현황 API·표시 규칙 v0.1](quarterly-summary-contract.md)에 정리했다. 목록 계약과 구분하며 FE 공동 검토 전이다.

</details>

<a id="12-근거-문서"></a>

<details>
<summary>12. 근거 문서</summary>

- [T02 · FE 연동 API 명세 초안](https://app.notion.com/p/3f42114f823a8143acb4fc2f33187116)
- [백엔드 개발 실행 계획](https://app.notion.com/p/3f42114f823a813b9adfff5db7c6171c)
- [T03 · AI 계약 초안](https://app.notion.com/p/3f42114f823a8112bebcd967ad5925b0)
- [FE API 초안](https://github.com/teojabi-groundbreakers/pickplace-frontend/blob/8075e112a9e75127bb74890b41206109b60e3ff6/docs/API.md)
- [FE 인계 문서](https://github.com/teojabi-groundbreakers/pickplace-frontend/blob/8075e112a9e75127bb74890b41206109b60e3ff6/docs/BACKEND_HANDOFF.md)
- [FE 호출 코드](https://github.com/teojabi-groundbreakers/pickplace-frontend/blob/8075e112a9e75127bb74890b41206109b60e3ff6/src/lib/api.ts)
- [FE 응답 검증](https://github.com/teojabi-groundbreakers/pickplace-frontend/blob/8075e112a9e75127bb74890b41206109b60e3ff6/src/lib/validation.ts)
- [BE 공통 응답](common-response.md)
- [BE 기준정보 조회](reference-api.md)
- [BE 기준정보 DB 설계](../database/reference-schema-design.md)

</details>
