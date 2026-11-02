# FE 인계 · 현재 지원 API 안내

확인일: 2026-11-01 · 코드 기준: develop `3a96caa` / main `0de61ea`

[Notion에서 보기](https://app.notion.com/p/40b2114f823a815d8ffffa100c0a8ed0)

> **서비스 API 3개 구현 · 행정동/업종/현황/AI는 미구현.** 아래 ‘가능’은 코드 구현 기준이며 공유 서버 배포·연동 완료를 뜻하지 않는다.

## 지원 API

| 기능 | Method | 전체 경로 | 정상 응답 위치 | develop 코드 | main 코드 |
| --- | --- | --- | --- | --- | --- |
| 서버 생존 | GET | `/api/health` | `data.status` → UP | 가능 | 가능 |
| DB 준비 상태 | GET | `/api/health/ready` | `data.status` → UP | 가능 | 가능 |
| 서울 자치구 목록 | GET | `/api/districts` | `data.items` → 코드·이름 | 가능 | 미반영 |

**공통:** 요청 파라미터·본문 없음 · 로그인/토큰/FE API 키 불필요 · 정상 200. 준비 상태·자치구의 DB 장애는 503이며, 이미 기동한 서버의 생존 확인은 DB와 독립적이다. 향후 인증 계약은 별도이며 공공데이터/AI 내부 인증키를 FE에서 보내지 않는다.

## 연결 전에 확인

| 확인 항목 | 현재 기준 |
| --- | --- |
| 로컬 BE 예시 | `http://localhost:8080` |
| FE API 기준 URL | `/api` — 함수에는 `/districts`처럼 전달 |
| 공유 서버 주소·배포·DB 적재 | 미확인 — BE 담당자 확인 필요 |
| 성공 / 오류 | `data + meta` / `error + meta` |
| 정상 빈 목록 / DB 장애 | `200 + items: []` / `503` |
| 현재 FE 어댑터 | `/catalog`와 최상위 오류 code 사용 중 — 변경 필요 |

## API 상세

필요한 API만 펼치면 요청·응답·호출 예시를 같은 페이지에서 확인할 수 있다.

<details>
<summary>GET /api/districts · 서울 자치구 목록</summary>

- 요청 파라미터·본문 없음.
- 정렬: districtCode 오름차순.
- 기준정보 전체 적재 시 25개. 미적재·부분 적재 상태에서는 결과가 달라질 수 있으므로 FE 검증에 25개를 필수 조건으로 넣지 않는다.

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

위는 배열 길이를 줄인 형식 예시다.

| 필드 | 자료형 | 의미 |
| --- | --- | --- |
| districtCode | string, 숫자 5자리 | 원천 자치구 코드. 숫자로 변환하지 않음 |
| districtName | string | 표시 이름 |

정상 조회 결과가 없으면 **200 + 빈 배열**이다.

```json
{
  "data": { "items": [] },
  "meta": { "requestId": "example-empty-districts" }
}
```

빈 배열은 DB 장애가 아니다. 해당 서버의 기준정보 적재 여부를 확인한다. API가 서버 실행 시 자동으로 기준정보를 채워주는 것은 아니다. 좌표·행정동·업종·통계는 이 응답에 없다. DB 장애는 503이며 빈 배열로 바꾸지 않는다.

```cmd
curl -i http://localhost:8080/api/districts
```

</details>

<details>
<summary>GET /api/health · 서버 생존</summary>

- 요청 파라미터·본문 없음.
- 정상: 200, data.status는 UP.
- DB를 조회하지 않는다. 이미 실행 중인 서버에서 DB가 중단돼도 생존 응답은 정상일 수 있다.
- DB가 완전히 꺼진 상태에서 서버 최초 기동이 성공한다는 뜻은 아니다. 현재 서버 기동에는 DB/Flyway 초기화가 필요하다.

```cmd
curl -i http://localhost:8080/api/health
```

정상 응답은 아래 ‘공통 응답’의 성공 예시와 같다. `/health` 성공만으로 자치구 조회 준비가 됐다고 판정하지 않는다.

</details>

<details>
<summary>GET /api/health/ready · DB 준비 상태</summary>

- 요청 파라미터·본문 없음.
- DB에 SELECT 1을 실행한다. 정상 200 응답은 아래 ‘공통 응답’의 성공 예시와 같다.
- DB 연결·조회 실패는 503 DATA_SERVICE_UNAVAILABLE, 오류 응답은 아래 ‘공통 응답’과 같다.
- 기준정보가 25개 적재됐는지까지 확인하는 API는 아니다.
- 쿼리 타임아웃은 2초지만 커넥션 풀 연결 대기와는 다르다. DB 중단 시 기본 연결 대기 약 30초가 추가로 걸릴 수 있다. FE의 현재 30초 제한에서는 서버 오류 코드보다 TIMEOUT이 먼저 보일 수 있다.

```cmd
curl -i http://localhost:8080/api/health/ready
```

</details>

## 연동 가이드

<details>
<summary>공통 응답 · 오류 · 요청 ID</summary>

### 성공

```json
{
  "data": { "status": "UP" },
  "meta": { "requestId": "example-health" }
}
```

### 오류

```json
{
  "error": {
    "code": "DATA_SERVICE_UNAVAILABLE",
    "message": "현재 데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    "details": []
  },
  "meta": { "requestId": "example-db-error" }
}
```

| 항목 | FE 사용 규칙 |
| --- | --- |
| `data` | 성공 데이터. 전체 JSON을 기존 Catalog나 Analysis로 바로 검증하지 않음 |
| `error.code` | HTTP 상태와 함께 오류 분기에 사용. 최상위 body.code가 아님 |
| `error.message` | 사용자용 문구. FE 기본 안내 문구 정책과 구분 |
| `error.details` | 배열. 상세 항목은 field/reason. 해당 사항 없으면 [] |
| `meta.requestId` | 요청 추적 ID. 오류 제보 시 전달 |
| 응답 헤더 `X-Request-Id` | meta.requestId와 같은 값 |

클라이언트의 X-Request-Id 전송은 선택 사항이다. 영문·숫자·`-`·`_` 1~64자일 때만 유지하며, 그 외에는 서버가 새 ID를 발급한다. 고정된 예시 ID를 운영 요청 전체에 재사용하지 않는다.

### HTTP 오류별 처리

| HTTP·상황 | code | 처리 |
| --- | --- | --- |
| 404 | NOT_FOUND | 없는 URL. 실행 브랜치·기준 URL·요청 경로 확인 |
| 405 | METHOD_NOT_ALLOWED | GET 경로에 POST 등을 전송. Allow 헤더로 허용 메서드 확인 |
| 500 | INTERNAL_ERROR | 서버 오류, 요청 ID와 함께 제보 |
| 503 | DATA_SERVICE_UNAVAILABLE | DB 장애, 빈 목록과 구분해서 재시도 안내 |
| 프레임워크가 판정한 기타 4xx | INVALID_REQUEST | 해당 HTTP 상태 보존, 요청 형식 확인 |
| 연결 실패·브라우저 CORS·FE 타임아웃 | 서버 code를 받지 못할 수 있음 | 네트워크·출처·프록시·시간 제한 확인 |

INVALID_REGION_CODE·INVALID_QUARTER·BUSINESS_TYPE_NOT_SUPPORTED 같은 코드가 서버 enum이나 초안에 있어도 해당 업무 API가 현재 구현됐다는 뜻은 아니다. 미구현 API를 테스트해 나온 404를 통계 자료 없음으로 해석하지 않는다.

</details>

<details>
<summary>접속 설정 · 프록시/CORS · URL 조합</summary>

로컬 BE 실행 예시 origin은 `http://localhost:8080`이다. 공유 개발 서버·운영 서버의 실제 주소는 별도 전달이 필요하다.

FE의 현재 기본 기준 URL은 `/api`이고 요청 함수가 그 뒤에 경로를 붙인다.

```dotenv
VITE_API_BASE_URL=/api
VITE_DEMO_MODE=false
```

| 요청 함수에 전달 | 최종 요청 URL 예시 |
| --- | --- |
| `/health` | `/api/health` |
| `/health/ready` | `/api/health/ready` |
| `/districts` | `/api/districts` |

- `/api` 기준 URL에 `/api/districts`를 다시 붙이지 않는다.
- 상대 URL `/api`는 FE 출처를 기준으로 한다. 개발 프록시가 없다면 자동으로 localhost:8080으로 연결되는 것이 아니다.
- 별도 출처를 사용하면 예를 들어 `http://localhost:8080/api`를 기준 URL로 설정하되 CORS 또는 개발 프록시 설정을 함께 확인한다. 현재 BE 코드에는 FE 출처를 위한 명시적 CORS 설정이 없다.
- FE가 X-Request-Id 응답 헤더를 별도 출처에서 읽으려면 노출 헤더 설정이 필요하다. 본문의 meta.requestId도 사용할 수 있다.
- 현재 FE의 getCatalog()는 `/catalog`를 호출한다. 위 환경변수만 바꿔도 자치구 목록으로 전환되는 것은 아니다. 아래 ‘FE 연동 순서’의 어댑터 변경이 필요하다.

</details>

<details>
<summary>FE 연동 순서 · 검수 체크리스트</summary>

| 순서 | 할 일 |
| --- | --- |
| 1 | develop 반영 서버의 origin·실행 커밋·프록시/CORS 확인 |
| 2 | `/health`와 `/health/ready`로 서버·DB 준비 상태 구분 |
| 3 | `/districts`를 새 목록 요청으로 추가. 공통 래퍼와 data.items 검증 |
| 4 | districtCode/districtName을 화면 내부 Option의 code/name으로 변환 가능 |
| 5 | 빈 결과·503·404·타임아웃을 각 상태로 표시 |
| 6 | 행정동·업종·현황·AI는 구현 안내 전까지 별도 Mock 상태로 관리 |

기존 requestJson은 body 전체를 isCatalog/isAnalysis로 검증하고 오류의 최상위 code를 읽는다. 새 API에는 공통 응답을 읽는 어댑터가 필요하다. 기존 분석 API까지 일괄 변경하는 것은 후속 계약과 함께 진행한다.

### 공동 검수

- [ ] 실행 서버가 develop의 자치구 구현을 포함한다.
- [ ] /api 접두사가 중복되지 않고 FE 요청이 실제 BE로 전달된다.
- [ ] health는 200, ready는 정상 DB에서 200이다.
- [ ] districts의 data.items에서 코드·이름을 읽고 코드순을 유지한다.
- [ ] 정상 빈 배열과 503·네트워크 오류를 구분한다.
- [ ] error.code와 meta.requestId를 읽는다.
- [ ] 목록 실패를 데모 성공 결과로 대체하지 않는다.
- [ ] 미구현 API를 운영 연결 완료로 표시하지 않는다.

현재 공유 서버에서 위 공동 검수를 수행한 기록은 없다. 이 문서는 코드 기반 인계 안내이며 FE 통합 테스트 완료 확인서가 아니다.

</details>

<details>
<summary>아직 호출하지 않는 API · 전체 예정 목록</summary>

| 경로·기능 | 상태 |
| --- | --- |
| GET /api/catalog | 기존 FE 초안. BE 미구현, 개별 목록 전환 제안 |
| GET /api/districts/{districtCode}/administrative-dongs | 명세 초안, 미구현 |
| GET /api/business-types | 명세 초안, 미구현 |
| GET /api/administrative-dongs/{dongCode}/data-availability | 분리 필요 여부 검토 중, 미구현 |
| GET /api/administrative-dongs/{dongCode}/summary | 현황 명세 초안, 미구현 |
| GET /api/administrative-dongs/{dongCode}/trends | 후속 계획, 미구현 |
| GET /api/administrative-dongs/{dongCode}/map | 후속 계획, 미구현 |
| POST /api/analyses | FE·AI 협의 전, 미구현 |
| GET /api/regions/{H10}/boundary | FE 준비 초안, BE 미구현·후순위 보류 |

Mock으로 화면 개발을 진행할 수는 있지만 실서버 지원으로 표시하지 않는다. 특히 자치구만 받아 기존 Catalog 전체를 조립하거나 빈 업종·행정동을 임의 생성하지 않는다.

</details>

<details>
<summary>Swagger · Actuator · 개발 확인 주소</summary>

| Method | 경로 | 용도 | 응답·주의 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Spring Boot 기본 상태 확인 | `/api` 공통 래퍼와 다름. 정상 대표 예시는 status=UP |
| GET | `/swagger-ui.html` | Swagger UI 진입 | develop에서 제공, UI 페이지로 리다이렉트 |
| GET | `/v3/api-docs` | OpenAPI JSON | develop에서 제공, data/meta 래퍼 없음 |

위 주소는 `/api` 뒤에 덧붙이지 않는다. Swagger 자동 명세에는 현재 자치구의 503 오류 응답이 빠져 있으므로 오류 처리는 이 문서와 공통 응답 문서를 함께 본다. 운영 환경의 문서 공개 설정은 후속 작업이다.

</details>

<details>
<summary>버전·검토 범위 · 근거 문서</summary>

| 기준 | 확인한 커밋 | 지원 범위 |
| --- | --- | --- |
| 원격 develop | `3a96caa75036c460feab97e4a6ab7433c15661c7` | 상태 API 2개 + 자치구 목록 + Swagger |
| 원격 main | `0de61ea65d6ce0f8fb465e196f4131c11f33609e` | 상태 API 2개. 자치구 목록·Swagger는 미포함 |

자치구 조회를 연동하려면 위 develop 코드를 반영한 서버가 필요하다. 브랜치 병합과 실제 서버 배포는 다르므로, FE가 접속할 서버의 실행 커밋을 BE 담당자에게 확인한다. 확인일 이후 변경되면 이 표도 갱신한다.

이번 문서 작성에서는 원격 코드와 기존 테스트를 대조했다. 실제 공유 서버의 배포 URL·실행 상태·개발 DB 적재 상태는 확인하지 않았으며, 전체 테스트를 새로 실행한 결과가 아니다.

### 관련 자료·갱신 원칙

- [공통 응답·상태 API](common-response.md)
- [자치구 API 구현 상세](reference-api.md)
- [FE·BE 통합 계약 초안](fe-be-contract.md)
- [분기별 현황 계약 초안](quarterly-summary-contract.md)
- [서버 실행·DB 적재 안내](../getting-started.md)
- [공통 처리 PR #6](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/6)
- [자치구 조회 PR #9](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/9)

새 API가 develop에 병합되면 해당 경로·필드·예시·검수 근거를 지원 목록에 추가하고, main 반영 여부와 실제 배포 상태는 별도로 갱신한다. 예정 API를 구현 전에 현재 지원 표로 옮기지 않는다.

</details>
