# 전국 지역 목록 API

시·도 → 그룹 → 읍·면·동 순서로 지역을 고르는 화면에서 쓰는 목록 API다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따르며, 계약은 노션 "전국 지역 선택 · API 계약 v0.3"을 기준으로 한다.

> 상태: 시·도·그룹·읍면동 목록 구현. 과거(말소) 지역 조회는 경로가 정해지지 않아 미구현이다.
> 데이터: [전국 기준정보 적재·승인·게시](../data/national-reference-import.md)로 **게시된** 버전만 읽는다. 적재·승인만 하고 게시하지 않은 버전은 보이지 않는다.

| 경로 | 필수 입력 | 내용 | 정렬 |
|---|---|---|---|
| `GET /api/regions/provinces` | 없음 | 시·도 목록 | 코드순 |
| `GET /api/regions/groups?provinceCode={H10}` | `provinceCode` | 시·도에 속한 화면 그룹 | 이름순, 같은 이름은 코드순 |
| `GET /api/regions/localities?groupCode={H10}` | `groupCode` | 그룹에 속한 읍·면·동 | 이름순, 같은 이름은 코드순 |

- 그룹은 법적 시·군·구 전체가 아니라 화면의 중간 단계다. 일반 시 아래 구는 `수원시 장안구`처럼 하나의 그룹으로 보이고, `수원시` 자체는 보이지 않는다.
- 기존 서울 전용 목록(`/api/districts`, `/api/districts/{districtCode}/administrative-dongs`)은 FE가 이 API로 옮긴 뒤 폐기한다. 그때까지는 그대로 동작한다.

## 1. 정상 응답 — 200

현재 게시본(`national-202609-release-v1`)의 `GET /api/regions/groups?provinceCode=3600000000` 응답이다.

```json
{
  "data": {
    "items": [
      { "regionCode": "3611000000", "regionName": "세종특별자치시", "selectionLevel": "GROUP", "parentCode": "3600000000", "autoSelect": false }
    ]
  },
  "meta": {
    "requestId": "3f2a9c1e-...",
    "codeSystem": "MOIS_H10",
    "sourceReferenceOn": null,
    "sourceFileVersion": "20261026"
  }
}
```

| `items` 필드 | 형식 | 설명 |
|---|---|---|
| `regionCode` | 문자열, 숫자 10자리 | 공식 행정기관 코드(H10). 숫자가 아니라 문자열로 다룬다 |
| `regionName` | 문자열 | 화면 표시명. 공식 원본 이름과 다를 수 있다(예: `수원시 장안구`) |
| `selectionLevel` | `PROVINCE` / `GROUP` / `LOCALITY` | 화면 선택 단계. 법적 행정구역 유형이 아니다 |
| `parentCode` | 문자열 또는 `null` | 화면 선택 부모의 코드. 시·도는 `null`이다. 공식 행정 계층의 부모와 다를 수 있다 |
| `autoSelect` | 불리언 | BE의 자동 선택 권고. 게시된 기준정보 값을 그대로 내보내며 API에서 특정 코드를 정하지 않는다 |

| `meta` 필드 | 설명 |
|---|---|
| `requestId` | 응답 헤더 `X-Request-Id`와 같은 값 |
| `codeSystem` | 항상 `MOIS_H10`. 모든 코드가 행정안전부 행정기관 코드라는 뜻 |
| `sourceReferenceOn` | 공식 자료 게시글의 기준일(`YYYY-MM-DD`). 게시본에 근거 값이 없으면 `null`이다(현재 게시본은 `null`). 근거 없는 날짜를 채우거나 파일 버전을 날짜로 바꾸지 않는다 |
| `sourceFileVersion` | 공식 첨부 파일 버전 문자열. 날짜처럼 보여도 날짜로 해석하지 않는다 |

- 출처 meta는 설명용이다. FE가 다음 요청에 다시 보내는 값이 아니다.
- 공개 선택 버전(`regionVersion`)은 계약에서 보류됐으므로 응답에 넣지 않고 요청으로도 받지 않는다.
- 목록에는 현행이고, 날짜 품질이 정상이며, 검토가 끝난 화면 선택 대상만 나온다. 출장소·도시 묶음·말소 지역·검토 전 지역은 나오지 않는다.
- 공식 지역은 있지만 통계가 없는 지역도 목록에 나온다. 통계 제공 여부는 현황 API에서 따로 판단한다.
- 하위 지역이 없는 정상 부모는 `200` + `items: []`다.

### `autoSelect` 해석

| 값 | 의미 |
|---|---|
| `true` | 추가 클릭 없이 이 항목을 적용하도록 권고한다 |
| `false` | 별도 권고가 없다. 자동 선택을 금지한다는 뜻이 아니다 |

- 실제로 항목을 선택하고 다음 목록을 요청하는 것은 FE가 처리한다.
- 세종 그룹(`3611000000`)의 설계 권고는 `true`다. 현재 게시본에는 `false`로 저장돼 있으며, 새 기준정보 릴리스 반영과 FE 동작 검수는 후속 작업이다.

## 2. 입력 규칙

입력 검사는 DB 조회 전에 하므로, DB 장애 중에도 잘못된 입력은 503이 아니라 400이다.

| 요청 | 응답 | `details` |
|---|---|---|
| 필수 코드 없음 | 400 `INVALID_REQUEST` | `provinceCode` 또는 `groupCode` / `REQUIRED` |
| 코드가 ASCII 숫자 10자리가 아님(자릿수·문자·앞뒤 공백·빈 값·전각 숫자) | 400 `INVALID_REGION_CODE` | 해당 필드 / `INVALID_FORMAT` |
| 같은 파라미터를 두 번 보냄(값이 같아도) | 400 `INVALID_REQUEST` | 해당 필드 / `INVALID_VALUE` |
| 정해지지 않은 파라미터(`regionVersion` 포함) | 400 `INVALID_REQUEST` | `query` / `INVALID_VALUE` |

- 서버는 공백 제거·숫자 변환·`00` 붙이기 같은 보정을 하지 않는다.
- 정해지지 않은 파라미터는 보낸 이름을 응답에 되돌려주지 않고 `query`로만 알린다.
- 이 규칙은 새 세 경로에만 적용한다. 기존 API의 처리 방식은 바꾸지 않았다.

## 3. 오류

처리 순서는 입력 검사 → 게시 버전 확인 → 부모 확인 → 하위 목록 조회다.

| HTTP | `code` | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` / `INVALID_REGION_CODE` | 위 입력 규칙 위반 |
| 404 | `REGION_NOT_FOUND` | 부모 코드가 현재 게시본에서 요청한 단계로 보이지 않음 (없는 코드, 시·도 자리에 그룹 코드, 말소·출장소·도시 묶음 등) |
| 503 | `REFERENCE_DATA_NOT_READY` | 전국 기준정보가 아직 게시되지 않음 |
| 503 | `DATA_SERVICE_UNAVAILABLE` | DB 연결·조회 실패 |
| 500 | `INTERNAL_ERROR` | 게시본의 내부 정합성 오류 (예: 출처 파일 기록 없음) |

```json
{
  "error": {
    "code": "REGION_NOT_FOUND",
    "message": "해당 지역을 찾을 수 없습니다.",
    "details": [{ "field": "groupCode", "reason": "NOT_FOUND" }]
  },
  "meta": { "requestId": "3f2a9c1e-..." }
}
```

```json
{
  "error": {
    "code": "REFERENCE_DATA_NOT_READY",
    "message": "지역 기준정보가 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.",
    "details": []
  },
  "meta": { "requestId": "3f2a9c1e-..." }
}
```

## 4. 서버 내부 동작

- 한 요청은 게시 정보를 처음에 한 번만 읽고, 그 버전 하나로 끝까지 조회한다. 요청 도중 게시가 바뀌어도 한 응답 안에서 두 버전이 섞이지 않는다.
- SQL은 시·도 2번(게시 정보 + 목록), 하위 목록 3번(게시 정보 + 부모 확인 + 목록)이다.
- 하위 목록은 공식 행정 계층의 부모(`hierarchy_parent_code`)가 아닌 화면 선택 부모(`selection_parent_code`)로 찾는다.
- 이름 정렬은 MySQL 컬럼의 정렬 규칙(`utf8mb4_0900_ai_ci`)을 따른다. FE는 받은 순서를 그대로 쓴다.
- 성공·오류 응답 모두 `Cache-Control: no-store`를 붙여, 게시본이 바뀐 뒤 브라우저가 예전 목록을 다시 쓰지 않게 한다.

## 5. 로컬 확인 (Windows CMD)

```cmd
curl -i http://localhost:8080/api/regions/provinces
curl -i "http://localhost:8080/api/regions/groups?provinceCode=1100000000"
curl -i "http://localhost:8080/api/regions/localities?groupCode=1111000000"
```

전국 기준정보를 게시하기 전에는 세 경로 모두 503 `REFERENCE_DATA_NOT_READY`가 정상이다. 적재·승인·게시 방법은 `data/reference/national-202609-release-v1/README.md`를 따른다.
