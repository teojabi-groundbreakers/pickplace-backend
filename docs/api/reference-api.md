# 기준정보 조회 API

지역·업종 선택 화면에서 쓰는 목록 API다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 상태: 자치구·행정동 목록 구현. 지원 업종 목록은 후속 작업에서 이 문서에 추가한다.
> 기준: 팀 API 명세 초안(데이터 계약 초안 v0.1). 데이터는 [기준정보 수동 적재](../data/reference-import.md)로 넣은 값을 그대로 읽는다.

API 문서 화면(Swagger UI)은 서버 실행 후 `http://localhost:8080/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`에서 확인한다.

## 1. 자치구 목록 — `GET /api/districts`

서울 자치구 전체를 **자치구 코드순**으로 돌려준다. 요청 파라미터는 없다.

### 정상 응답 — 200

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

| 필드 | 형식 | 설명 |
|---|---|---|
| `districtCode` | 문자열, 숫자 5자리 | 원천 자치구 코드(`SIGNGU_CD`). 숫자가 아니라 문자열로 다룬다 |
| `districtName` | 문자열 | 자치구 이름(`SIGNGU_NM`) |

- 기준정보를 적재한 DB에서는 25개가 나온다.
- 정렬은 코드순이다. 코드는 바뀌지 않으므로 호출할 때마다 같은 순서다.
- `data_version`, `collected_at` 같은 DB 관리용 컬럼은 응답에 넣지 않는다.

### 자치구가 없을 때 — 200

기준정보를 아직 적재하지 않은 DB에서는 오류가 아니라 빈 목록을 돌려준다.

```json
{ "data": { "items": [] }, "meta": { "requestId": "..." } }
```

### DB 장애 — 503

DB에 연결하거나 조회하지 못하면 빈 목록으로 위장하지 않고 503을 돌려준다. DB 주소·SQL은 응답에 넣지 않는다.

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

### 프론트엔드 참고

FE 연동 초안은 지역·업종을 `GET /catalog` 하나로 받는 구조라 이 API와 경로·필드 이름이 다르다. 이 API는 팀 API 명세 초안(A2)을 따랐으며, FE와의 형식 통일은 명세 검토 때 정리한다.

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
