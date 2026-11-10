# FE 인계 · 현재 지원 API 안내

확인일: 2026-11-10 · 코드 기준: develop `9d955c9` / main `b1002dd` · T15 작업 브랜치의 내부 판정 구현은 별도

[노션 최신 개발 문서 안내](https://app.notion.com/p/40a2114f823a8133b662fb5aa49b457c) · [지역·업종 정식 명세](https://app.notion.com/p/4122114f823a81988d0ee0c9bf88c5bb)

> **develop에는 HTTP API 8개가 구현되어 있다.** 전국 지역 3개·지원 업종 1개, 상태 2개, 임시 유지 중인 서울 목록 2개다. 현황·추이·지도·AI 공개 API는 아직 구현되지 않았다. T13/T14의 원본 검증·DB 적재 CLI가 생겨도 FE에서 호출할 HTTP API가 자동으로 생기는 것은 아니다.

T15의 DEFAULT 조회 기준·지표 판정은 내부 재사용 기능이다. 신규 공개 URL은 없으며 [T15 구현·T17/18 인계](../data/summary-availability.md)를 참고한다. FE는 현황 API가 구현·배포되기 전까지 설계 경로를 지원 API로 간주하지 않는다.

## 지원 API

| 기능 | Method | 전체 경로 | 입력 | develop | main |
| --- | --- | --- | --- | --- | --- |
| 서버 생존 | GET | `/api/health` | 없음 | 가능 | 가능 |
| DB 연결 준비 | GET | `/api/health/ready` | 없음 | 가능 | 가능 |
| 전국 시·도 | GET | `/api/regions/provinces` | 없음 | 가능 | 미반영 |
| 시·도별 화면 그룹 | GET | `/api/regions/groups` | provinceCode H10 필수 | 가능 | 미반영 |
| 그룹별 읍·면·행정동 | GET | `/api/regions/localities` | groupCode H10 필수 | 가능 | 미반영 |
| 지원 업종 | GET | `/api/business-types` | 없음 | **소진공 코드** | 이전 서울 CS 계약 |
| 서울 자치구 · 임시 유지 | GET | `/api/districts` | 없음 | 가능 | 가능 |
| 자치구별 서울 행정동 · 임시 유지 | GET | `/api/districts/{districtCode}/administrative-dongs` | districtCode 경로 값 | 가능 | 가능 |

전국 FE는 `develop`의 지역·소진공 업종 계약을 사용한다. 기존 서울 목록으로 조용히 대체하거나 서울 CS 코드와 소진공 코드를 임의로 변환하지 않는다. 위 표는 코드 구현 기준이며 공유 서버 배포·연동 완료를 뜻하지 않는다.

**공통:** 요청 본문·로그인/토큰/FE API 키는 없다. 그룹·읍면동 목록에는 위 표의 필수 쿼리가 있다. DB 연결 실패는 503, 미게시와 정상 빈 목록은 구분한다. 이미 실행 중인 서버의 생존 응답은 DB와 독립적이다.

## 연결 전에 확인

| 확인 항목 | 현재 기준 |
| --- | --- |
| 로컬 BE 예시 | `http://localhost:8080` |
| FE API 기준 URL | `/api` — 함수에는 `/regions/provinces`처럼 전달 |
| 공유 서버 주소·배포·DB 적재 | 미확인 — BE 담당자 확인 필요 |
| 성공 / 오류 | `data + meta` / `error + meta` |
| 정상 빈 목록 / 미게시 / DB 장애 | `200 + items: []` / `503 REFERENCE_DATA_NOT_READY` / `503 DATA_SERVICE_UNAVAILABLE` |
| 검토한 FE 초안 어댑터 | `/catalog`와 최상위 오류 code 사용 — 현재 적용 상태는 FE 확인 필요 |

## API 상세

필요한 API만 펼치면 요청·응답·호출 예시를 같은 페이지에서 확인할 수 있다.

<details>
<summary>전국 지역 선택 · provinces → groups → localities</summary>

| 순서 | 요청 | 다음 요청에 쓸 값 |
| --- | --- | --- |
| 1 | `GET /api/regions/provinces` | 선택한 items[].regionCode → provinceCode |
| 2 | `GET /api/regions/groups?provinceCode=1100000000` | 선택한 items[].regionCode → groupCode |
| 3 | `GET /api/regions/localities?groupCode=1111000000` | 선택한 LOCALITY의 regionCode → 후속 현황 조회 지역 |

H10은 ASCII 숫자 10자리 **문자열**이다. 법정동10·서울 상권8·자치구5와 혼용하지 않는다. 숫자 변환, trim, `00` 추가로 새 코드를 만들지 않는다. groups는 화면 선택 그룹이며 모든 법적 시군구를 그대로 노출하는 목록이 아니다.

아래는 구조 설명용 발췌 예시다. 실제 전체 배열이나 실서버 응답 기록이 아니다.

```json
{
  "data": { "items": [
    { "regionCode": "1111000000", "regionName": "종로구", "selectionLevel": "GROUP", "parentCode": "1100000000", "autoSelect": false }
  ] },
  "meta": { "requestId": "example-region", "codeSystem": "MOIS_H10", "sourceReferenceOn": null, "sourceFileVersion": "20261026" }
}
```

| 항목 | FE 처리 |
| --- | --- |
| regionCode / regionName | 실제 코드 / 표시명. 이름으로 코드를 추정하지 않음 |
| selectionLevel / parentCode | PROVINCE·GROUP·LOCALITY / 화면 부모 코드. 시·도 parentCode는 null |
| autoSelect | true=자동 선택 권고, false=권고 없음. 적용·복원·수동 선택 정책은 FE 책임 |
| meta | requestId 외 지역 출처 필드가 추가됨. sourceReferenceOn은 근거가 없으면 null |
| 정렬 | 시·도 코드순, 하위 목록 이름순→코드순. 서버 순서를 유지 |

- 세종은 provinceCode `3600000000` → 실제 그룹 `3611000000`이다. 권고를 적용할 경우 그 그룹 값으로 localities를 요청한다. 시·도 코드를 groupCode로 재사용하지 않는다.
- 부모 변경 시 하위 선택과 현황을 비우고, 이전 요청을 취소하거나 선택 키를 대조해 늦게 도착한 결과를 버린다.
- 아직 게시 전이면 503, 유효한 부모의 정상 하위 목록 0개는 200 빈 배열이다. 존재하지 않거나 다른 역할의 부모는 404다.
- 미정의 query·중복 query는 400이다. 필수 값·H10 형식은 DB 접근 전에 검사한다. 첫 MVP에서는 regionVersion·selectionRegionVersion을 보내지 않는다.
- 성공·오류 응답 모두 `Cache-Control: no-store`다.

```cmd
curl -i http://localhost:8080/api/regions/provinces
curl -i "http://localhost:8080/api/regions/groups?provinceCode=1100000000"
curl -i "http://localhost:8080/api/regions/localities?groupCode=1111000000"
```

</details>

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
<summary>GET /api/districts/{districtCode}/administrative-dongs · 행정동 목록</summary>

- districtCode는 숫자 5자리 문자열이며 자치구 목록의 값을 그대로 사용한다.
- 정렬: dongName → dongCode 오름차순.
- 형식 오류는 DB 연결 전에 400 INVALID_REGION_CODE, 미등록 자치구는 404 REGION_NOT_FOUND다.
- 등록된 자치구의 동이 없으면 200 + 빈 배열이며 DB 장애는 503이다.
- LEGACY도 포함한다. 선택 허용·경고 정책은 FE 공동 검토 대상이다.

| 응답 필드 | 형식 | 의미 |
| --- | --- | --- |
| dongCode | string · 숫자 8자리 | 원천 행정동 코드 |
| dongName | string | 원천 행정동 이름 |
| districtCode | string · 숫자 5자리 | 저장된 소속 자치구 |
| officialCodeStatus | string | 공식 코드 대응 상태 |
| regionVersion | string | 지역 기준정보 버전 |

응답 형식 예시이며 목록 일부만 표시했다.

```json
{
  "data": {
    "items": [
      {
        "dongCode": "11110515",
        "dongName": "청운효자동",
        "districtCode": "11110",
        "officialCodeStatus": "CURRENT",
        "regionVersion": "seoul-reference-20262-mois-20261012-v1"
      }
    ]
  },
  "meta": { "requestId": "example-dongs" }
}
```

officialDongCode와 지도 중심 좌표는 현재 응답에 없다. FE가 dongCode에 00을 붙이거나 집계값으로 좌표를 생성하지 않는다.

```cmd
curl -i http://localhost:8080/api/districts/11110/administrative-dongs
```

</details>

<details>
<summary>GET /api/business-types · 소진공 지원 소분류</summary>

- 요청 파라미터·본문 없음. `supported=false`를 포함해 무엇이든 query를 붙이면 400 `INVALID_REQUEST`다.
- 현재 지역 게시본에 연결된 승인 분류의 SMALL·지원 승인 업종만 반환한다. 최신 번호·다른 분류·서울 목록으로 대체하지 않는다.
- 정렬은 display_order → industry_code다. businessTypeName은 승인 display_name이며 현재 합의는 소진공 원천명 그대로다.
- meta는 requestId만 가진다. 전체 소분류 저장과 서비스 지원 목록을 구분하며 3개 코드·개수를 FE에 하드코딩하지 않는다.

```json
{
  "data": {
    "items": [
      { "businessTypeCode": "I21201", "businessTypeName": "카페", "supported": true },
      { "businessTypeCode": "I21007", "businessTypeName": "김밥/만두/분식", "supported": true },
      { "businessTypeCode": "I21001", "businessTypeName": "빵/도넛", "supported": true }
    ]
  },
  "meta": { "requestId": "example-business-types" }
}
```

| 상황 | HTTP·code | FE 처리 |
| --- | --- | --- |
| 승인된 분류에서 정상 지원 업종 0개 | 200 items=[] | 선택·분석 진행을 막고 정상 빈 상태 안내 |
| 지역 게시 포인터 없음 | 503 REFERENCE_DATA_NOT_READY | 자료 준비 전 안내 |
| 게시된 자료의 분류 연결 오류·미승인 | 500 INTERNAL_ERROR | 서버 오류 안내·requestId로 제보 |
| DB 연결·조회 실패 | 503 DATA_SERVICE_UNAVAILABLE | 빈 목록과 구분해 장애 안내 |
| 미정의 query | 400 INVALID_REQUEST | 요청 수정. details는 query/INVALID_VALUE |

반환값을 숫자로 변환하거나 `CS...` 정규식으로 검증하지 않는다. 저장된 이전 CS 선택은 유효한 소진공 항목으로 다시 선택해야 하며 임의 일대일 대응을 만들지 않는다. 새 게시본의 지원 목록을 다시 읽고 기존 선택이 남아 있는지 확인한다.

```cmd
curl -i http://localhost:8080/api/business-types
```

성공·오류 모두 `Cache-Control: no-store`다. 원본 API 키나 AI 내부 인증키를 FE 요청에 넣지 않는다.

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
| 400 | INVALID_REGION_CODE | 자치구 코드를 숫자 5자리 문자열로 전달 |
| 404 | REGION_NOT_FOUND | 형식은 맞지만 등록되지 않은 자치구 |
| 404 | NOT_FOUND | 없는 URL. 실행 브랜치·기준 URL·요청 경로 확인 |
| 405 | METHOD_NOT_ALLOWED | GET 경로에 POST 등을 전송. Allow 헤더로 허용 메서드 확인 |
| 500 | INTERNAL_ERROR | 서버 오류, 요청 ID와 함께 제보 |
| 503 | DATA_SERVICE_UNAVAILABLE | DB 장애, 빈 목록과 구분해서 재시도 안내 |
| 503 | REFERENCE_DATA_NOT_READY | 전국 기준정보 정상 미게시. 업종 연결 오류 500과 구분 |
| 404 | REGION_NOT_FOUND | 선택 가능한 부모 없음·다른 역할. 상위 선택부터 확인 |
| 프레임워크가 판정한 기타 4xx | INVALID_REQUEST | 해당 HTTP 상태 보존, 요청 형식 확인 |
| 연결 실패·브라우저 CORS·FE 타임아웃 | 서버 code를 받지 못할 수 있음 | 네트워크·출처·프록시·시간 제한 확인 |

INVALID_QUARTER·BUSINESS_TYPE_NOT_SUPPORTED 같은 코드가 서버 enum이나 초안에 있어도 해당 업무 API가 현재 구현됐다는 뜻은 아니다. 미구현 API를 테스트해 나온 404를 통계 자료 없음으로 해석하지 않는다.

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
| `/districts/11110/administrative-dongs` | `/api/districts/11110/administrative-dongs` |
| `/business-types` | `/api/business-types` |

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
| 3 | `/regions/provinces` → `/regions/groups` → `/regions/localities` 연결. 공통 래퍼·H10·data.items 검증 |
| 4 | regionCode/regionName을 화면 내부 Option으로 변환. business-types는 소진공 코드·표시명 사용 |
| 5 | 빈 결과·503·404·타임아웃을 각 상태로 표시 |
| 6 | 부모 변경 시 하위 선택/현황 해제·이전 요청 취소. autoSelect 권고 정책 적용 |
| 7 | 현황·추이·지도·AI는 구현 안내 전까지 별도 Mock 상태로 관리 |

기존 requestJson은 body 전체를 isCatalog/isAnalysis로 검증하고 오류의 최상위 code를 읽는다. 새 API에는 공통 응답을 읽는 어댑터가 필요하다. 기존 분석 API까지 일괄 변경하는 것은 후속 계약과 함께 진행한다.

### 공동 검수

- [ ] 실행 서버가 develop의 전국 지역·소진공 업종 전환을 포함한다.
- [ ] /api 접두사가 중복되지 않고 FE 요청이 실제 BE로 전달된다.
- [ ] health는 200, ready는 정상 DB에서 200이다.
- [ ] 전국 지역의 실제 H10·화면 부모·서버 정렬을 유지한다.
- [ ] 소진공 코드·원천 표시명·정렬을 유지하고 저장된 CS 선택을 임의 변환하지 않는다.
- [ ] 정상 빈 배열·미게시503·DB장애503·연결오류500·네트워크 오류를 구분한다.
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
| GET /api/regions/{regionCode}/summary?businessTypeCode=... | 승인 DEFAULT 현황 명세, HTTP 구현은 후속 |
| 추이·개별 시설/지도 경계·AI 분석 | 별도 데이터·명세·구현이 필요한 후속 범위 |
| 공개 LEGACY·과거 월 선택 | 첫 MVP 제외. 내부 이력·MONTH 포인터와 구분 |

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
| 원격 develop | `4192eb7` | 상태2 + 전국 지역3 + 소진공 업종1 + 임시 서울 목록2. T13 포함 |
| 원격 main | `b1002dd` | 실수 병합 취소로 기존 v0.2.0 파일 상태 복원. 전국 전환 미포함 |

브랜치 병합과 실제 서버 배포는 다르다. FE가 접속할 서버의 실행 커밋과 실제 기준정보 게시 상태를 BE 담당자에게 확인한다.

2026-11-10에 develop 통합 상태의 `clean check bootJar`, 전체 362건 통과를 확인했다. 이는 공유 서버 배포·FE 통합 검수 결과가 아니다. T14의 추가 검증은 해당 적재 문서에 따로 기록하며, 적재 CLI를 공개 HTTP API로 표시하지 않는다.

### 관련 자료·갱신 원칙

- [공통 응답·상태 API](common-response.md)
- [자치구 API 구현 상세](reference-api.md)
- [행정동 조회 PR #10](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/10)
- [지원 업종 조회 PR #11](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/11)
- [FE·BE 통합 계약 초안](fe-be-contract.md)
- [현재 현황 API 정식 명세](https://app.notion.com/p/40a2114f823a81a89dc5d3df4530398b)
- [전국 지역 API 구현](region-api.md)
- [소진공 업종 전환 PR #24](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/24)
- [서버 실행·DB 적재 안내](../getting-started.md)
- [공통 처리 PR #6](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/6)
- [자치구 조회 PR #9](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/9)

새 API가 develop에 병합되면 해당 경로·필드·예시·검수 근거를 지원 목록에 추가하고, main 반영 여부와 실제 배포 상태는 별도로 갱신한다. 예정 API를 구현 전에 현재 지원 표로 옮기지 않는다.

</details>
