# 지역·업종 현황 API

선택한 읍·면·동과 소진공 업종의 **주민등록 인구·수록 업소 수**를 돌려준다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 기준: 현황 API 명세 v0.4(노션). 조회 기준 고정·지표 판정 규칙은 [현황 조회 기준·지표 가용성 판정](../data/summary-availability.md), 저장 구조는 [V5 통계 테이블](../database/national-statistics-schema.md)을 따른다.
>
> 상태: 구현. 실제 배포 서버·FE 연동 검수는 별도다.

## 요청

```
GET /api/regions/{regionCode}/summary?businessTypeCode={소진공 소분류 코드}
```

| 입력 | 위치 | 규칙 |
|---|---|---|
| `regionCode` | 경로 | 숫자 10자리 H10. 현재 게시된 선택 목록의 **읍·면·동(LOCALITY)** 이어야 한다 |
| `businessTypeCode` | 쿼리, 필수 | 대문자 1자 + 숫자 5자리 소진공 소분류 코드(예: `I21201`). 지원 업종 목록 API가 돌려준 코드를 쓴다 |

- 월·분기·버전(`month`, `quarter`, `regionVersion`, `selectionRegionVersion`) 입력은 **받지 않는다**. 보내면 정해지지 않은 파라미터라 400이다.
- 어느 달 자료를 쓸지는 서버가 정한다. 운영자가 명시적으로 고른 기본(DEFAULT) 현황 묶음을 쓰며, 가장 최근 월이나 가장 최근 적재본으로 추측하지 않는다.
- 앞뒤 공백 제거, 소문자 → 대문자 변환, 자릿수 보정, 서울 CS 코드 변환은 하지 않는다.

## 정상 응답 — 200

아래 숫자·시각은 구조를 설명하는 **가상값**이다(명세 v0.4 예시). 실제 값은 게시된 묶음에서 나온다.

```json
{
  "data": {
    "region": { "regionCode": "1111051500", "regionName": "청운효자동" },
    "businessType": { "businessTypeCode": "I21201", "businessTypeName": "카페" },
    "metrics": {
      "registeredPopulation": {
        "value": 10000,
        "unit": "PERSON",
        "status": "AVAILABLE",
        "reasonCode": null,
        "source": {
          "datasetCode": "POPULATION_MONTH",
          "referenceOn": "2026-09-30",
          "snapshotLabel": null,
          "collectedAt": "2026-11-09T03:52:31Z"
        }
      },
      "listedStoreCount": {
        "value": null,
        "unit": "STORE",
        "status": "UNAVAILABLE",
        "reasonCode": "COUNT_INCOMPLETE",
        "source": {
          "datasetCode": "STORE_SNAPSHOT",
          "referenceOn": "2026-09-30",
          "snapshotLabel": "20260930",
          "collectedAt": "2026-11-09T03:52:31Z"
        }
      }
    }
  },
  "meta": { "requestId": "...", "referenceMonth": "202609", "publishedAt": "2026-11-09T05:00:00Z" }
}
```

| 필드 | 설명 |
|---|---|
| `region.regionName` | 현재 선택 목록의 표시명 |
| `businessType.businessTypeName` | 현재 승인 분류본의 표시명 |
| `metrics.*.value` | `AVAILABLE`이면 0 이상의 정수(실제 0 포함), 그 밖에는 `null` |
| `metrics.*.unit` | 인구 `PERSON`, 업소 `STORE` |
| `metrics.*.status` | `AVAILABLE` / `NO_DATA` / `UNAVAILABLE` |
| `metrics.*.reasonCode` | `AVAILABLE`이면 `null`, 그 밖에는 아래 사유 |
| `source.referenceOn` | 원천·배포본 기준일. 업소는 배포본 기준일이며 모든 업소의 당일 조사일이 아니다 |
| `source.snapshotLabel` | 인구 `null`, 업소는 배포본 라벨 |
| `source.collectedAt` | 원본을 실제로 받은 시각(UTC) |
| `meta.referenceMonth` | 서버가 고른 기본 묶음의 월. 사용자가 고른 월이 아니다 |
| `meta.publishedAt` | 그 기본 묶음을 게시한 시각(UTC) |

- 값이 `null`인 키도 지우지 않는다. 결측·보류 지표에도 `source`는 그대로 들어간다.
- 전체 상태를 따로 계산하지 않는다. 지표마다 `status`만 본다.
- 숫자를 "실시간 현황"으로 표시하지 않는다. 기준월·기준일을 함께 보여준다.

### 지표 상태와 사유

| status | reasonCode | 의미 |
|---|---|---|
| `AVAILABLE` | `null` | 공개 가능한 값. 업소 0은 유효한 수록 범위에서 그 업종만 없을 때다 |
| `NO_DATA` | `SOURCE_REGION_ABSENT` | 원천 자료에 이 지역이 수록되지 않음(업소 수록 범위가 없으면 0이 아니다) |
| `NO_DATA` | `SOURCE_ROW_ABSENT` | 원천 지역은 있지만 자료 행이 없음 |
| `NO_DATA` | `SOURCE_VALUE_MISSING` | 자료 행은 있지만 값이 비어 있음 |
| `UNAVAILABLE` | `SOURCE_SUPPRESSED` | 원천이 비공개 처리한 값 |
| `UNAVAILABLE` | `CLASSIFICATION_MISMATCH` | 현재 선택 업종 분류와 현황 묶음의 분류가 달라 같은 업종인지 확인할 수 없음(업소만) |
| `UNAVAILABLE` | `MAPPING_UNRESOLVED` | 원천 지역 → H10 대응이 확정되지 않음 |
| `UNAVAILABLE` | `BOUNDARY_REVIEW_PENDING` | 기준 시점의 행정구역 경계 검토가 끝나지 않음 |
| `UNAVAILABLE` | `BOUNDARY_MISMATCH` | 원천·공개 지역의 시점 경계가 맞지 않음 |
| `UNAVAILABLE` | `COUNT_INCOMPLETE` | 업소 수에 영향을 주는 보류 행이 있어 숫자를 확정할 수 없음 |
| `UNAVAILABLE` | `QUALITY_CHECK_FAILED` | 숫자·합계·수록 범위 등 품질 검증 실패 |

여러 원인이 겹치면 `SOURCE_SUPPRESSED` → `CLASSIFICATION_MISMATCH` → `MAPPING_UNRESOLVED` → `BOUNDARY_REVIEW_PENDING` → `BOUNDARY_MISMATCH` → `COUNT_INCOMPLETE` → `QUALITY_CHECK_FAILED` → 원천 결측 순서의 첫 사유를 준다. 한 지표가 보류여도 다른 지표는 따로 판정한다.

## 오류

| HTTP·`error.code` | 상황 | `details` |
|---|---|---|
| 400 `INVALID_REQUEST` | 정해지지 않은 파라미터 | `query` / `INVALID_VALUE` |
| 400 `INVALID_REQUEST` | `businessTypeCode`를 두 번 보냄(값이 같아도) | `businessTypeCode` / `INVALID_VALUE` |
| 400 `INVALID_REQUEST` | `businessTypeCode` 없음·빈 값 | `businessTypeCode` / `REQUIRED` |
| 400 `INVALID_REGION_CODE` | 지역 코드가 숫자 10자리가 아님 | `regionCode` / `INVALID_FORMAT` |
| 400 `INVALID_REQUEST` | 업종 코드 형식 오류(공백·소문자 포함) | `businessTypeCode` / `INVALID_FORMAT` |
| 404 `REGION_NOT_FOUND` | 현재 선택 목록에 없음, 읍·면·동이 아님(시·도·그룹), 말소 지역 | `regionCode` / `NOT_FOUND` |
| 404 `BUSINESS_TYPE_NOT_FOUND` | 현재 승인 분류에 없는 소분류 | `businessTypeCode` / `NOT_FOUND` |
| 422 `BUSINESS_TYPE_NOT_SUPPORTED` | 등록됐지만 지원하지 않는 업종 | `businessTypeCode` / `NOT_SUPPORTED` |
| 503 `REFERENCE_DATA_NOT_READY` | 지역 기준정보 미게시 | `[]` |
| 503 `SUMMARY_DATA_NOT_READY` | 기본 현황 묶음 미게시 | `[]` |
| 503 `DATA_SERVICE_UNAVAILABLE` | DB 연결·조회 실패 | `[]` |
| 500 `INTERNAL_ERROR` | 게시된 자료의 참조 무결성 오류 등 | `[]` |
| 405 `METHOD_NOT_ALLOWED` | GET 이외 메서드(`Allow` 헤더 유지) | `[]` |

- 입력 오류는 미정의·중복 파라미터 → 업종 누락 → 지역 형식 → 업종 형식 순서로 하나만 알린다. 이 검사는 DB를 읽기 전에 하므로 DB 장애 중에도 잘못된 요청은 400이다.
- 지역이 목록에 있는데 통계가 없다고 404로 바꾸지 않는다. 그런 경우는 200과 지표별 `NO_DATA`다.
- 기본 묶음 미게시나 DB 장애를 200 빈 지표·서울 자료로 대신하지 않는다.
- 성공·오류 응답 모두 `Cache-Control: no-store`이며 `Retry-After`는 보내지 않는다.

## 내부 동작

1. Controller가 정해지지 않은·중복 파라미터와 코드 형식을 검사한다(DB 접근 전).
2. `SummaryContextService.withContext`가 읽기 전용 스냅샷 트랜잭션을 열고 지역 선택 게시본·연결 분류·현행 읍면동·업종 지원·DEFAULT 묶음·원본 출처를 한 번에 고정한다.
3. 같은 트랜잭션(콜백) 안에서 `SummaryEvidenceReader`가 고정된 배치 번호로 판정 근거를 읽는다. 게시 포인터를 다시 읽지 않는다.
   - 인구: `resident_population_month`의 행 상태·값·시점 상태, 원천 지역 기록(`source_region_reference`), 대응표 검토 상태, 집계 영향 품질 문제
   - 업소: 대응표에서 출발한 `store_region_coverage`(수록 범위·시점 상태), 지역 전체·지역×업종 품질 문제, `store_industry_count`(채택 수·보류)
   - 선택 기준 릴리스와 현황 묶음의 대응 릴리스가 다르면, 대응 릴리스에서도 현행 노드인지 확인한다. 아니면 `BOUNDARY_MISMATCH`다.
4. `MetricAvailabilityPolicy`가 근거를 값·상태·사유로 판정하고, Controller가 위 JSON으로 바꾼다.

요청마다 외부 수집·전체 원본 행 스캔·AI 호출을 하지 않는다.

## 확인 방법

```cmd
curl -i "http://localhost:8080/api/regions/1111051500/summary?businessTypeCode=I21201"
```

기본 현황 묶음을 게시하지 않은 DB에서는 503 `SUMMARY_DATA_NOT_READY`가 정상이다. 묶음 적재·게시 절차는 [전국 통계 적재·집계·게시](../data/national-statistics-import.md)를 따른다.
