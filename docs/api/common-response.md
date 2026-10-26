# 공통 응답 형식과 서버 상태 API

PickPlace 백엔드의 모든 `/api` 응답이 따르는 형식이다. 기준은 팀 API 명세 초안(데이터 계약 초안 v0.1)이다.

> 상태: BE 구현 완료 / FE 검토 전. 아래 "추가안(확정 전)" 항목은 명세 초안에 없던 것으로, 담당자 확인이 필요하다.

## 1. 성공 응답

```json
{
  "data": { "status": "UP" },
  "meta": { "requestId": "3f2a9c1e-8b4d-4e2f-9a1b-7c6d5e4f3a2b" }
}
```

- `data`: API마다 내용이 다르다. 목록 API는 `data.items` 배열을 쓰고, 결과가 없으면 `"items": []`이다.
- `meta.requestId`: 요청 추적용 ID. 아래 3절 참고.

## 2. 오류 응답

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다.",
    "details": [{ "field": "businessTypeCode", "reason": "REQUIRED" }]
  },
  "meta": { "requestId": "3f2a9c1e-8b4d-4e2f-9a1b-7c6d5e4f3a2b" }
}
```

- `code`: FE가 분기할 때 쓰는 고정 코드.
- `message`: 화면에 그대로 보여줄 수 있는 한국어 문구.
- `details`: 어떤 입력이 왜 잘못됐는지. 해당 사항이 없으면 `[]`(null 아님).
- 응답에는 SQL, 스택트레이스, DB 주소, 비밀값, 사용자가 보낸 원래 값을 넣지 않는다. 원인은 서버 로그에서 `requestId`로 찾는다.

### 오류 코드

| HTTP | code | 의미 | 상태 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 필수값 누락, 타입 불일치, JSON 형식 오류 등 일반 입력 오류 | **추가안(확정 전)** |
| 400 | `INVALID_QUARTER` | 분기 형식 오류 (`YYYYQ`, Q는 1~4) | 명세 초안 |
| 400 | `INVALID_REGION_CODE` | 지역 코드 형식 오류 | 명세 초안 |
| 404 | `NOT_FOUND` | 존재하지 않는 주소 | **추가안(확정 전)** |
| 404 | `REGION_NOT_FOUND` | 형식은 맞지만 등록되지 않은 지역 코드 | 명세 초안 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 | **추가안(확정 전)** |
| 422 | `BUSINESS_TYPE_NOT_SUPPORTED` | 등록됐지만 초기 지원 3업종이 아님 | 명세 초안 |
| 422 | `QUARTER_NOT_SUPPORTED` | 지원 기간 밖의 분기 | 명세 초안 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 | 명세 초안 |
| 503 | `DATA_SERVICE_UNAVAILABLE` | DB 연결·조회 실패 (빈 결과로 위장하지 않음) | 명세 초안 |

그 밖에 Spring이 4xx로 판단한 요청 오류(예: 415 지원하지 않는 Content-Type)는 해당 HTTP 상태와 `INVALID_REQUEST` 코드로 응답한다.

### details.reason 값

| reason | 의미 |
|---|---|
| `REQUIRED` | 필수 파라미터가 없음 |
| `INVALID_FORMAT` | 타입이 맞지 않음 (예: 숫자 자리에 문자) |
| `INVALID_VALUE` | 검증 규칙 위반 (예: 자릿수·패턴 불일치) |

## 3. 요청 ID (`X-Request-Id`)

- 서버는 모든 응답에 `X-Request-Id` 헤더를 붙이고, 같은 값을 `meta.requestId`에도 넣는다.
- 클라이언트가 `X-Request-Id` 헤더를 보내면 그 값을 그대로 쓴다. 단, 영문·숫자·`-`·`_`로 된 64자 이하만 받아들이고, 그 외에는 서버가 UUID를 새로 만든다.
- 서버는 요청마다 다음 형식의 로그를 한 줄 남긴다. 쿼리스트링·요청 본문·헤더는 기록하지 않는다.

```text
requestId=3f2a9c1e-... method=GET path=/api/health status=200 durationMs=3
```

오류가 나면 사용자에게 `requestId`를 받아 서버 로그에서 같은 값을 검색하면 된다.

## 4. A1 서버 상태 API

| 경로 | 의미 | DB 확인 | 정상 | 실패 |
|---|---|---|---|---|
| `GET /api/health` | 생존: 서버가 요청을 받을 수 있는가 | 안 함 | 200 | (서버가 꺼지면 응답 없음) |
| `GET /api/health/ready` | 준비: DB까지 연결돼 실제 조회가 가능한가 | `SELECT 1` | 200 | 503 `DATA_SERVICE_UNAVAILABLE` |

`/api/health/ready`는 생존과 준비를 구분하기 위해 정한 경로로, 명세 초안에 없는 **추가안(확정 전)**이다.

정상 응답(두 경로 공통):

```json
{ "data": { "status": "UP" }, "meta": { "requestId": "..." } }
```

DB 장애 시 `/api/health/ready`:

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

DB가 멈춰도 `/api/health`는 200이다. 서버 프로세스 자체는 살아 있기 때문이다.

### `/actuator/health`와의 차이

- `/actuator/health`: Spring Boot 기본 점검 주소. 개발·운영 점검용으로 그대로 둔다.
- `/api/health`, `/api/health/ready`: FE와 팀이 쓰는 공개 API. 공통 응답 형식과 `requestId`를 따르고, DB 등 내부 상세를 응답에 담지 않는다.

### 알려진 한계

DB 서버가 완전히 꺼져 있으면, 커넥션 풀(HikariCP)이 연결을 기다리는 시간(기본 30초)만큼 `/api/health/ready` 응답이 늦어질 수 있다. 대기 시간을 줄이려면 공용 설정을 바꿔야 하므로 별도로 논의한다.
