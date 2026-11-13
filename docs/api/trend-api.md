# 지역 상세 통계 분기별 추이 API

선택한 읍·면·동의 상세 통계를 분기별로 돌려주고, 값이 있는 지표에는 **전 분기·전년 동기 대비 증감**을 함께 준다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 상태: **BE 제안 · 구현.** 추이 공개 계약은 아직 따로 확정되지 않았다. 경로·입력·응답 모양은 이 문서의 제안이며 검토 결과에 따라 바뀔 수 있다.
>
> 데이터 기준: V7 `regional_statistic_*`와 게시 읽기 경로 `published_regional_statistic`([서울 행정동 상세 통계 적재](../data/seoul-detail-import.md), 전국 업소 분기 집계 `tools/prepare_national_store_quarters.py`). 증감 계산은 `trend/QuarterlyChangeCalculator`를 그대로 쓴다. 전국 현황 API(`/summary`)와 기본(DEFAULT) 묶음은 사용하지 않고 바꾸지도 않는다.

## 요청

```
GET /api/regions/{regionCode}/trends?datasetCode={자료}&{업종 파라미터}&fromQuarter={YYYYQ}&toQuarter={YYYYQ}
```

| 입력 | 위치 | 규칙 |
|---|---|---|
| `regionCode` | 경로 | 숫자 10자리 H10. 현재 게시된 선택 목록의 **읍·면·동(LOCALITY)** 이어야 한다 |
| `datasetCode` | 쿼리, 필수 | 아래 자료 표의 값 |
| `businessTypeCode` | 쿼리 | **소진공 소분류 코드**(대문자 1자 + 숫자 5자리, 예: `I21201`). `LISTED_STORE`에서만 필수 |
| `industryCode` | 쿼리 | **서울 상권 업종 코드**(`CS` + 숫자 6자리, 예: `CS100010`). `STORE`·`SALES`에서만 필수 |
| `fromQuarter` | 쿼리, 필수 | 시작 분기 `YYYYQ`(Q는 1~4, 예: `20262`) |
| `toQuarter` | 쿼리, 필수 | 종료 분기. 시작 이상이어야 하며 시작·종료 포함 **최대 8분기** |

| `datasetCode` | 자료 | 원천 | 지역 범위 | 업종 파라미터 |
|---|---|---|---|---|
| `LISTED_STORE` | 소진공 수록 업소 수 분기 집계 | `SEMAS_STORE` | 전국(두 분기 공통 수록 지역) | `businessTypeCode` |
| `STORE` | 점포·개폐업 | `SEOUL_COMMERCIAL` | 서울 | `industryCode` |
| `SALES` | 추정매출 | `SEOUL_COMMERCIAL` | 서울 | `industryCode` |
| `WORKER` | 직장인구 | `SEOUL_COMMERCIAL` | 서울 | 없음 |
| `FACILITY` | 시설 | `SEOUL_COMMERCIAL` | 서울 | 없음 |
| `FLOW` | 길단위인구 | `SEOUL_COMMERCIAL` | 서울 | 없음 |

- 업종 체계마다 파라미터 이름이 다르다. 자료에 맞지 않는 업종 파라미터를 보내면 400이다. 서울 CS 업종과 소진공 업종을 서버가 서로 바꿔 찾지 않는다(대응이 확정되지 않았다).
- `businessTypeCode`는 업종 목록 API와 같은 기준으로 확인한다. 현재 승인 분류에 없으면 404, 지원하지 않으면 422다.
- `industryCode`는 공개 업종 목록이 따로 없어 형식만 검사한다. 원천에 없는 CS 코드는 분기마다 `SOURCE_ROW_ABSENT`로 나온다.
- 앞뒤 공백 제거, 소문자 → 대문자 변환, 자릿수 보정은 하지 않는다.
- 최대 8분기는 현재 게시 범위(최근 2년)에 맞춘 제한이다. 게시 범위 밖의 분기를 요청해도 오류가 아니라 그 분기가 `NOT_PUBLISHED`로 나온다.

## 정상 응답 — 200

아래 숫자는 구조를 설명하는 **가상값**이다. 실제 지표 목록과 값은 게시된 배치에서 나온다.

```json
{
  "data": {
    "region": { "regionCode": "1111051500", "regionName": "청운효자동" },
    "dataset": {
      "sourceSystem": "SEMAS_STORE",
      "datasetCode": "LISTED_STORE",
      "industryCode": null,
      "businessTypeCode": "I21201",
      "businessTypeName": "카페",
      "periodType": "QUARTER"
    },
    "metrics": [
      { "metricCode": "listed_store_count", "unit": "COUNT", "definitionStatus": "VERIFIED" }
    ],
    "periods": [
      {
        "quarter": "20262",
        "status": "AVAILABLE",
        "reasonCode": null,
        "values": {
          "listed_store_count": {
            "value": 10,
            "status": "AVAILABLE",
            "reasonCode": null,
            "changes": {
              "previousQuarter": { "absoluteChange": null, "changeRatePercent": null, "status": "BASELINE_UNAVAILABLE" },
              "sameQuarterLastYear": { "absoluteChange": null, "changeRatePercent": null, "status": "BASELINE_UNAVAILABLE" }
            }
          }
        }
      },
      {
        "quarter": "20263",
        "status": "AVAILABLE",
        "reasonCode": null,
        "values": {
          "listed_store_count": {
            "value": 12,
            "status": "AVAILABLE",
            "reasonCode": null,
            "changes": {
              "previousQuarter": { "absoluteChange": 2, "changeRatePercent": 20.00, "status": "AVAILABLE" },
              "sameQuarterLastYear": { "absoluteChange": null, "changeRatePercent": null, "status": "BASELINE_UNAVAILABLE" }
            }
          }
        }
      }
    ]
  },
  "meta": { "requestId": "..." }
}
```

| 필드 | 설명 |
|---|---|
| `dataset.industryCode` | 서울 CS 업종. `STORE`·`SALES`가 아니면 `null` |
| `dataset.businessTypeCode`·`businessTypeName` | 소진공 업종과 현재 승인 분류의 표시명. `LISTED_STORE`가 아니면 `null` |
| `metrics` | 범위 안 게시 배치의 지표 정의. 지표 코드는 **원천 필드 이름 그대로**이고, 게시된 분기가 하나도 없으면 `[]` |
| `metrics[].unit` | `COUNT`, `PERSON`, `FACILITY`, `KRW`, `PERCENT`, `SOURCE_COUNT` 등 적재 때 고정한 단위 |
| `metrics[].definitionStatus` | `VERIFIED`(정의 확인) 또는 `SOURCE_ONLY`(원천 값만 보존, 집계 정의 미확인) |
| `periods` | 요청 범위의 **모든 분기**를 시간순으로 담는다(연도를 넘으면 `20244` 다음 `20251`) |
| `periods[].values` | 분기가 `AVAILABLE`일 때만 지표 코드별 값이고, 그 밖에는 `null` |
| `values.*.value` | 숫자(실제 0 포함) 또는 `null`. 정수는 정수, 비율 같은 소수는 원천 값 그대로 |
| `values.*.changes` | 전 분기(`previousQuarter`)·전년 동기(`sameQuarterLastYear`) 대비 증감 |

- 값이 `null`인 키도 지우지 않는다.
- `SOURCE_ONLY` 지표(서울 매출, 길단위인구, 원천 비율)는 숨기지 않고 값과 `UNVERIFIED` 상태를 함께 준다. 화면에서 확정된 수치처럼 표시하지 않는다. 매출을 월평균·분기 합계로, 길단위인구를 실제 방문자 수로 단정하지 않는다.
- 값이 없는 분기를 앞뒤 분기와 선으로 잇거나 0으로 채우지 않는다.
- 서울 점포 수(`STORE`)와 소진공 수록 업소 수(`LISTED_STORE`)는 다른 지표다. 서로 더하거나 바꿔 쓰지 않는다.

### 증감

| `changes.*.status` | `absoluteChange` | `changeRatePercent` | 의미 |
|---|---|---|---|
| `AVAILABLE` | 숫자 | 숫자(소수 둘째 자리, 반올림) | 비교 가능 |
| `ZERO_BASELINE` | 숫자 | `null` | 기준값이 0이라 증감률을 만들지 않음(무한대·100%로 바꾸지 않는다) |
| `CURRENT_MISSING` | `null` | `null` | 이 분기 값이 비어 있음 |
| `CURRENT_UNAVAILABLE` | `null` | `null` | 이 분기 값이 정의 미확인(`UNVERIFIED`)이라 증감을 계산하지 않음 |
| `BASELINE_MISSING` | `null` | `null` | 비교 기준 분기가 게시됐지만 이 지역(·업종) 값이 없음 |
| `BASELINE_UNAVAILABLE` | `null` | `null` | 비교 기준 분기가 미게시이거나 보류·정의 미확인 |
| `NOT_COMPARABLE` | `null` | `null` | 두 분기의 원천·업종·지역 경계(대응 릴리스)·지표 정의·단위 중 하나가 다름 |

- 증감률(%) = (이번 값 − 기준값) ÷ 기준값 × 100.
- 범위 첫 분기도 비교할 수 있도록 서버가 요청 범위보다 4분기 앞까지 읽는다. 그 분기들은 비교 기준으로만 쓰고 응답 `periods`에는 넣지 않는다.
- 같은 분기의 원천 증감 지표(예: 원천 개·폐업률)가 있어도 다시 계산하지 않는다. 증감은 같은 지표의 분기 간 차이만 계산한다.

### 분기 상태와 사유

| status | reasonCode | 의미 |
|---|---|---|
| `AVAILABLE` | `null` | 게시된 정상 행이 있음. 지표마다 다시 상태가 있다 |
| `NO_DATA` | `SOURCE_REGION_ABSENT` | 그 분기 원천이 이 지역을 수록하지 않음(예: 서울 자료의 서울 밖 지역, 전국 업소의 공통 수록 범위 밖) |
| `NO_DATA` | `SOURCE_ROW_ABSENT` | 원천 수록 지역이지만 이 지역(·업종) 행이 없음(예: 직장인구가 없는 동) |
| `NO_DATA` | `SOURCE_VALUE_MISSING` | 행은 있지만 모든 값이 비어 있음 |
| `UNAVAILABLE` | `NOT_PUBLISHED` | 그 분기가 아직 게시되지 않음. 원천에 자료가 없다는 뜻이 아니다 |
| `UNAVAILABLE` | `MAPPING_UNRESOLVED` | 원천 지역 → H10 대응이 확정되지 않아 보류 |
| `UNAVAILABLE` | `BOUNDARY_REVIEW_PENDING` | 그 분기의 행정구역 경계 검토가 끝나지 않아 보류 |
| `UNAVAILABLE` | `BOUNDARY_MISMATCH` | 원천 분기와 지역 경계가 맞지 않거나, 대응표를 만든 기준정보에서 이 지역이 현행이 아님 |
| `UNAVAILABLE` | `QUALITY_CHECK_FAILED` | 숫자 오류·중복 충돌 등 품질 검증 실패로 제외 |

보류 사유가 여러 개면 `MAPPING_UNRESOLVED` → `BOUNDARY_REVIEW_PENDING` → `BOUNDARY_MISMATCH` → `QUALITY_CHECK_FAILED` → `SOURCE_VALUE_MISSING` 순서의 첫 사유를 준다.

### 지표 상태

| status | reasonCode | 의미 |
|---|---|---|
| `AVAILABLE` | `null` | 정의가 확인된 값 |
| `UNVERIFIED` | `SOURCE_DEFINITION_UNREVIEWED` | 값은 있지만 원천 집계 정의를 아직 확인하지 않음 |
| `NO_DATA` | `SOURCE_VALUE_MISSING` | 원천 값이 비어 있음 |
| `NO_DATA` | `SOURCE_FIELD_ABSENT` | 범위 안 다른 분기에는 있는 지표가 이 분기 배치 정의에는 없음 |

## 오류

| HTTP·`error.code` | 상황 | `details` |
|---|---|---|
| 400 `INVALID_REQUEST` | 정해지지 않은 파라미터 | `query` / `INVALID_VALUE` |
| 400 `INVALID_REQUEST` | 같은 파라미터를 두 번 보냄(값이 같아도) | 해당 이름 / `INVALID_VALUE` |
| 400 `INVALID_REGION_CODE` | 지역 코드가 숫자 10자리가 아님 | `regionCode` / `INVALID_FORMAT` |
| 400 `INVALID_REQUEST` | `datasetCode` 없음·빈 값 / 정해진 값이 아님 | `datasetCode` / `REQUIRED`·`INVALID_VALUE` |
| 400 `INVALID_REQUEST` | 자료에 맞지 않는 업종 파라미터를 보냄 | 그 파라미터 / `INVALID_VALUE` |
| 400 `INVALID_REQUEST` | 필요한 업종 파라미터 없음 / 형식 오류 | `businessTypeCode`·`industryCode` / `REQUIRED`·`INVALID_FORMAT` |
| 400 `INVALID_REQUEST` | 시작·종료 분기 없음 | `fromQuarter`·`toQuarter` / `REQUIRED` |
| 400 `INVALID_QUARTER` | 분기 형식 오류 | `fromQuarter`·`toQuarter` / `INVALID_FORMAT` |
| 400 `INVALID_QUARTER` | 종료가 시작보다 앞이거나 8분기 초과 | `toQuarter` / `INVALID_VALUE` |
| 404 `REGION_NOT_FOUND` | 현재 선택 목록에 없음, 읍·면·동이 아님, 말소 지역 | `regionCode` / `NOT_FOUND` |
| 404 `BUSINESS_TYPE_NOT_FOUND` | 현재 승인 분류에 없는 소분류(`LISTED_STORE`) | `businessTypeCode` / `NOT_FOUND` |
| 422 `BUSINESS_TYPE_NOT_SUPPORTED` | 등록됐지만 지원하지 않는 소분류(`LISTED_STORE`) | `businessTypeCode` / `NOT_SUPPORTED` |
| 503 `REFERENCE_DATA_NOT_READY` | 지역 기준정보 미게시 | `[]` |
| 503 `DATA_SERVICE_UNAVAILABLE` | DB 연결·조회 실패 | `[]` |
| 500 `INTERNAL_ERROR` | 게시 자료의 정합성 오류(승인 안 된 배치 게시, 업종 체계 불일치, 요청 범위 안에서 분기마다 다른 지표 정의, 한 지역에 정상 행이 여럿) | `[]` |
| 405 `METHOD_NOT_ALLOWED` | GET 이외 메서드(`Allow` 헤더 유지) | `[]` |

- 입력 오류는 미정의·중복 파라미터 → 지역 형식 → 자료 → 업종 → 시작 분기 → 종료 분기 → 범위 순서로 하나만 알린다. 이 검사는 DB를 읽기 전에 하므로 DB 장애 중에도 잘못된 요청은 400이다.
- 지역이 목록에 있는데 통계가 없다고 404로 바꾸지 않는다. 그런 경우는 200과 분기별 `NO_DATA`다.
- 범위 안 분기가 하나도 게시되지 않아도 503이 아니라 200이고, 모든 분기가 `NOT_PUBLISHED`다.
- 범위 앞 비교 기준 분기의 지표 정의가 달라도 오류가 아니라 그 증감만 `NOT_COMPARABLE`이다.
- 성공·오류 응답 모두 `Cache-Control: no-store`이다.

## 내부 동작

1. Controller가 정해지지 않은·중복 파라미터와 입력 형식·범위를 검사한다(DB 접근 전).
2. 읽기 전용·REPEATABLE_READ 트랜잭션 하나를 열고, 지역 목록 API와 같은 규칙으로 현행 읍·면·동인지 확인한다. `LISTED_STORE`는 업종 목록 API와 같은 규칙으로 업종도 확인한다.
3. 같은 트랜잭션에서 요청 범위보다 4분기 앞부터 종료 분기까지 세 가지를 읽는다. 배치 번호는 고정하지 않고 매 요청 분기별 게시 포인터에서 찾는다.
   - 분기별 게시 포인터와 그 배치의 상태·업종 체계·대응 릴리스·지표 정의, 그리고 대응표에 이 지역이 있는지
   - `published_regional_statistic` 뷰에서 이 지역·업종의 정상 행
   - 같은 게시 배치에서 이 지역·업종 행이 보류·오류로 빠졌는지와 그 사유
4. 대응표를 만든 기준정보 릴리스가 지금 선택 릴리스와 다르면, 그 릴리스에서도 이 지역이 현행인지 확인한다. 아니면 `BOUNDARY_MISMATCH`다(현황 API와 같은 기준).
5. 분기마다 상태를 정하고, 요청 분기의 값이 있는 지표에 증감을 계산해 응답으로 바꾼다.

조회 도중 같은 분기가 새 배치로 다시 게시돼도, 한 응답은 처음 읽은 시점의 포인터와 값만 쓴다. 요청마다 외부 수집·전체 원본 행 스캔·AI 호출을 하지 않는다.

## 확인 방법

```cmd
curl -i "http://localhost:8080/api/regions/1111051500/trends?datasetCode=LISTED_STORE&businessTypeCode=I21201&fromQuarter=20262&toQuarter=20263"
curl -i "http://localhost:8080/api/regions/1111051500/trends?datasetCode=WORKER&fromQuarter=20243&toQuarter=20262"
curl -i "http://localhost:8080/api/regions/1111051500/trends?datasetCode=STORE&industryCode=CS100010&fromQuarter=20243&toQuarter=20262"
```

상세 통계를 게시하지 않은 DB에서는 200과 모든 분기 `NOT_PUBLISHED`가 정상이다. 적재·승인·게시 절차는 [서울 행정동 상세 통계 적재](../data/seoul-detail-import.md)를 따른다.
