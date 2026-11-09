# 기준정보 조회 API

지역·업종 선택 화면에서 쓰는 목록 API다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 전국 지역 선택은 [전국 지역 목록 API](region-api.md)(`/api/regions/*`)를 쓴다. 아래 1·2절의 서울 전용 경로는 FE가 전국 API로 옮긴 뒤 폐기한다.
>
> 상태: 자치구·행정동·지원 업종 목록 구현.
> 기준: 1·2절은 팀 API 명세 초안(데이터 계약 초안 v0.1)이며 [기준정보 수동 적재](../data/reference-import.md)로 넣은 서울 값을 읽는다. 3절 업종 목록은 전국 기준정보 게시본에 연결된 소진공 업종 분류를 읽는다([전국 목록 API 개발 인계](nationwide-reference-api-handoff.md) 3절).

API 문서 화면(Swagger UI)은 서버 실행 후 `http://localhost:8080/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`에서 확인한다.

## 1. 자치구 목록 — `GET /api/districts`

서울 자치구 전체를 **자치구 코드순**으로 돌려준다. 요청 파라미터는 없다.

### 정상 응답 — 200

<details>
<summary>응답 JSON 예시 1</summary>

```json
{
  "data": {
    "items": [
      { "districtCode": "11110", "districtName": "종로구" },
      { "districtCode": "11140", "districtName": "중구" }
    ]
  },
  "meta": { "requestId": "3f2a9c1e-8b4d-4e2f-9a1b-7c6d5e4f3a2b" }
}
```

</details>

| 필드 | 형식 | 설명 |
|---|---|---|
| `districtCode` | 문자열, 숫자 5자리 | 원천 자치구 코드(`SIGNGU_CD`). 숫자가 아니라 문자열로 다룬다 |
| `districtName` | 문자열 | 자치구 이름(`SIGNGU_NM`) |

- 기준정보를 적재한 DB에서는 25개가 나온다.
- 정렬은 코드순이다. 코드는 바뀌지 않으므로 호출할 때마다 같은 순서다.
- `data_version`, `collected_at` 같은 DB 관리용 컬럼은 응답에 넣지 않는다.

### 자치구가 없을 때 — 200

기준정보를 아직 적재하지 않은 DB에서는 오류가 아니라 빈 목록을 돌려준다.

<details>
<summary>응답 JSON 예시 2</summary>

```json
{ "data": { "items": [] }, "meta": { "requestId": "..." } }
```

</details>

### DB 장애 — 503

DB에 연결하거나 조회하지 못하면 빈 목록으로 위장하지 않고 503을 돌려준다. DB 주소·SQL은 응답에 넣지 않는다.

<details>
<summary>응답 JSON 예시 3</summary>

```json
{
  "error": {
    "code": "DATA_SERVICE_UNAVAILABLE",
    "message": "현재 데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    "details": []
  },
  "meta": { "requestId": "..." }
}
```

</details>

### 프론트엔드 참고

FE 연동 초안은 기준 URL `/api`에 `/catalog`를 붙여 지역·업종을 한 번에 받으므로 이 API와 경로·필드 이름이 다르다. 개별 조회와 공통 응답으로 전환하는 안은 [FE·BE 통합 API 명세 v0.2](fe-be-contract.md)에 정리했다. FE 공동 검토 전 초안이며 이 문서는 현재 구현된 자치구·행정동·지원 업종 API를 설명한다.

## 2. 자치구별 행정동 목록 — `GET /api/districts/{districtCode}/administrative-dongs`

자치구에 속한 행정동을 **행정동 이름순**(이름이 같으면 코드순)으로 돌려준다.

| 경로 변수 | 형식 | 설명 |
|---|---|---|
| `districtCode` | 숫자 5자리 문자열 | 자치구 목록 API의 `districtCode` 값 |

### 정상 응답 — 200

```json
{
  "data": {
    "items": [
      {
        "dongCode": "11110600",
        "dongName": "가회동",
        "districtCode": "11110",
        "officialCodeStatus": "CURRENT",
        "regionVersion": "seoul-reference-20262-mois-20261012-v1"
      }
    ]
  },
  "meta": { "requestId": "..." }
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `dongCode` | 문자열, 숫자 8자리 | 원천 행정동 코드(`ADSTRD_CD`) |
| `dongName` | 문자열 | 원천 행정동 이름 |
| `districtCode` | 문자열, 숫자 5자리 | 소속 자치구 코드. 행정동 코드 앞자리로 추측하지 않고 저장된 소속 관계를 그대로 쓴다 |
| `officialCodeStatus` | `CURRENT` / `LEGACY` | 행정안전부 2026-10-12 코드표와 대조한 공식 코드 상태 |
| `regionVersion` | 문자열 | 지역 기준정보 버전 |

- 기준정보를 적재한 DB에서는 자치구마다 10~27개(종로구 17개)가 나온다.
- 다른 자치구의 행정동은 섞이지 않는다.
- 공식 코드표 대조 결과와 공식 코드·명칭, 날짜, DB 관리용 컬럼은 응답에 넣지 않는다.

### 말소된 행정동(`LEGACY`)

원천 통계에는 있지만 공식 코드가 말소된 3개 동(용신동·일원2동·상일동)도 목록에 **포함**하고 `officialCodeStatus: "LEGACY"`로 구분한다. 화면에서 숨길지·안내 문구를 붙일지 등 선택 정책은 이 API에서 정하지 않는다. `CURRENT`도 현재 행정 경계와 일치함을 보증하지는 않는다.

### 동이 없는 자치구 — 200

등록된 자치구인데 행정동이 없으면 빈 목록을 돌려준다.

```json
{ "data": { "items": [] }, "meta": { "requestId": "..." } }
```

### 잘못된 자치구 코드 — 400

숫자 5자리가 아니면(4·6자리, 문자 포함, 공백) `INVALID_REGION_CODE`다. 보낸 값은 응답에 되돌려주지 않는다.
형식 검사는 DB 조회 전에 하므로, DB 장애 중에도 잘못된 코드는 503이 아니라 400이다.

```json
{
  "error": {
    "code": "INVALID_REGION_CODE",
    "message": "지역 코드 형식이 올바르지 않습니다.",
    "details": [{ "field": "districtCode", "reason": "INVALID_FORMAT" }]
  },
  "meta": { "requestId": "..." }
}
```

### 없는 자치구 — 404

형식은 맞지만 등록되지 않은 자치구면 `REGION_NOT_FOUND`다. 동이 없는 정상 자치구(200 빈 목록)와 구분한다.

```json
{
  "error": {
    "code": "REGION_NOT_FOUND",
    "message": "해당 지역을 찾을 수 없습니다.",
    "details": [{ "field": "districtCode", "reason": "NOT_FOUND" }]
  },
  "meta": { "requestId": "..." }
}
```

### DB 장애 — 503

자치구 목록과 같이 `DATA_SERVICE_UNAVAILABLE`을 돌려준다.

## 3. 지원 업종 목록 — `GET /api/business-types`

화면에서 선택할 수 있는 **소진공(소상공인시장진흥공단) 소분류 지원 업종만** 표시 순서대로 돌려준다. 요청 파라미터는 없다.

> 서울 업종 테이블(`business_type`, `CS` 코드)은 더 이상 읽지 않는다. 지금은 전국 지역 게시본에 연결된 승인 업종 분류(`national_industry_*`)를 읽는다. `CS` 코드와 소진공 코드는 같은 체계가 아니며, 서로 짝지어 바꾸지 않는다.

### 정상 응답 — 200

아래 값은 2026-11-09 기준 게시 데이터(`data/reference/national-202609-sejong-hint-v1`)의 결과다. 코드·이름·개수는 프로그램에 적혀 있지 않고, 게시된 분류본이 바뀌면 함께 바뀐다.

```json
{
  "data": {
    "items": [
      { "businessTypeCode": "I21201", "businessTypeName": "카페", "supported": true },
      { "businessTypeCode": "I21007", "businessTypeName": "김밥/만두/분식", "supported": true },
      { "businessTypeCode": "I21001", "businessTypeName": "빵/도넛", "supported": true }
    ]
  },
  "meta": { "requestId": "..." }
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `businessTypeCode` | 문자열 | 소진공 소분류 코드(`industry_code`) |
| `businessTypeName` | 문자열 | 승인 분류본의 화면 표시명(`display_name`). 현재 합의는 원천명 그대로다 |
| `supported` | 불리언 | 화면 선택 지원 여부. 지원 업종만 돌려주므로 항상 `true`다 |

`meta`에는 `requestId`만 있다. 지역 목록 API의 출처 정보(`codeSystem` 등)는 넣지 않는다.

### 어떤 업종을 돌려주는가 (설계 정책)

1. 지역 게시 포인터(`region_publication`, `NATIONWIDE_SELECTION`)가 가리키는 승인 릴리스를 한 번 읽는다.
2. 그 릴리스의 검증 보고서(`validation_report`)에 적힌 `classificationId`로 업종 분류본을 찾고, 승인(`APPROVED`) 상태인지 확인한다. 업종에는 따로 게시 포인터가 없다.
3. 같은 분류본에서 소분류(`SMALL`)이면서 `is_supported = TRUE`, `support_release_status = 'APPROVED'`인 업종만 돌려준다.
4. 정렬은 `display_order` 순이고, 순서가 같으면 업종 코드순이다.

- 번호가 가장 큰 분류본, 가장 최근에 수집한 분류본, 여러 승인 분류본의 합집합은 쓰지 않는다. 게시본에 연결된 분류본 하나만 쓴다.
- 분류본에는 대·중·소분류 전체가 저장돼 있지만 지원 소분류만 노출한다.
- 결과를 따로 저장(캐시)해 두지 않는다. 새 릴리스를 게시하면 다음 요청부터 그 분류본의 업종이 나온다.
- 원천 업종명(`industry_name`), 상위 분류, `display_order` 같은 관리용 컬럼은 응답에 넣지 않는다.
- 성공·오류 응답 모두 `Cache-Control: no-store`를 붙인다.

### 지원 업종이 없을 때 — 200

연결된 분류본에 지원 업종이 하나도 없으면 오류가 아니라 빈 목록을 돌려준다. FE는 업종 선택·분석 진행을 막고 안내한다.

```json
{ "data": { "items": [] }, "meta": { "requestId": "..." } }
```

### 오류

| 상황 | HTTP·`error.code` | `details` |
|---|---|---|
| 쿼리 파라미터를 붙임(예: `?supported=false`, `?regionVersion=v1`) | 400 `INVALID_REQUEST` | `[{"field": "query", "reason": "INVALID_VALUE"}]` |
| 지역 기준정보가 아직 게시 전 | 503 `REFERENCE_DATA_NOT_READY` | `[]` |
| DB 연결·조회 실패 | 503 `DATA_SERVICE_UNAVAILABLE` | `[]` |
| 게시본의 업종 연결이 깨짐(`classificationId` 없음·정수 아님, 없는 분류본, 승인 전 분류본) | 500 `INTERNAL_ERROR` | `[]` |

- 파라미터 검사는 DB를 읽기 전에 하므로 DB 장애 중에도 잘못된 요청은 400이다. 붙인 파라미터 이름은 응답에 되돌려주지 않는다.
- 게시 전이나 DB 장애를 빈 목록으로 위장하지 않는다. 서울 업종 목록으로 대신하지도 않는다.
- 업종 연결이 깨진 상태는 승인·게시 명령이 미리 막기 때문에 정상 운영에서는 생기지 않는다. 생기면 다른 분류본으로 대신하지 않고 500으로 알린다.
