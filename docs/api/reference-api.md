# 기준정보 조회 API

지역·업종 선택 화면에서 쓰는 목록 API다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 상태: 자치구 목록 구현. 행정동·지원 업종 목록은 후속 작업에서 이 문서에 추가한다.
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
