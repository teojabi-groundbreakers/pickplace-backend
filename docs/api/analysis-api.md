# 지역·업종 분석 API (통계 + AI)

선택한 읍·면·동과 소진공 업종의 **기본 통계**와 **AI 분석 결과**를 함께 돌려준다. AI를 호출하지 못했거나 AI가 실패해도 기본 통계는 그대로 준다. 응답·오류 형식은 [공통 응답 형식](common-response.md)을 따른다.

> 상태: **BE 제안 · 구현.** 공개 분석 계약은 아직 확정되지 않았다. 지표별 기준 분기와 적합도 단독 호출은 협의(GitHub 이슈 #36) 후 별도로 반영한다.
>
> 기준: 기본 통계는 [현황 API](summary-api.md)와 같은 판정을 쓴다. AI 내부 통신·검증·설정은 [Spring–FastAPI 분석 연동](ai-integration-issue17.md)을 따른다.

## 요청

```
POST /api/ai/analyses
Content-Type: application/json

{"regionCode":"1111051500","industryCode":"I21201"}
```

| 입력 | 규칙 |
|---|---|
| `regionCode` | 숫자 10자리 H10. 현재 게시된 선택 목록의 읍·면·동이어야 한다 |
| `industryCode` | 소진공 소분류 코드(대문자 1자 + 숫자 5자리). 지원 업종 목록 API가 돌려준 코드를 쓴다 |

## 정상 응답 — 200

아래 숫자·시각은 구조를 설명하는 **가상값**이다.

```json
{
  "data": {
    "request": { "regionCode": "1111051500", "industryCode": "I21201" },
    "asOf": "2026-11-16",
    "basePeriod": "2026Q3",
    "statistics": {
      "version": "national-summary-202609-r1",
      "referenceMonth": "202609",
      "publishedAt": "2026-11-09T05:00:00Z",
      "metrics": {
        "registeredPopulation": {
          "value": 10000, "unit": "PERSON", "status": "AVAILABLE", "reasonCode": null,
          "source": { "datasetCode": "POPULATION_MONTH", "referenceOn": "2026-09-30", "snapshotLabel": null, "collectedAt": "2026-11-09T03:52:31Z" }
        },
        "listedStoreCount": {
          "value": null, "unit": "STORE", "status": "UNAVAILABLE", "reasonCode": "COUNT_INCOMPLETE",
          "source": { "datasetCode": "STORE_SNAPSHOT", "referenceOn": "2026-09-30", "snapshotLabel": "20260930", "collectedAt": "2026-11-09T03:52:31Z" }
        }
      }
    },
    "ai": { "state": "SKIPPED", "reason": "mapping_unverified", "transportStatus": null, "result": null }
  },
  "meta": { "requestId": "..." }
}
```

### statistics — 기본 통계
- `metrics`의 키·필드·상태·사유는 [현황 API](summary-api.md)의 `data.metrics`와 **같다**. 같은 지역·업종이면 값도 같다.
- `referenceMonth`·`publishedAt`은 현황 API의 `meta.referenceMonth`·`meta.publishedAt`과 같다(서버가 고른 기본 현황 묶음).
- 통계는 AI 상태와 관계없이 채운다. 지표가 결측·보류면 그 지표만 `value=null`과 사유다.

### ai — AI 분석
| `state` | 의미 | `result` |
|---|---|---|
| `AVAILABLE` | AI가 응답했고 범위·형식 검증을 통과함. 그 안의 성장·위험도·적합도는 각각 `available`/`unavailable`일 수 있다 | 검증된 AI 결과 |
| `SKIPPED` | AI를 호출하지 않음(코드 대응·자료 승인·이력 확인 불가 등) | `null` |
| `FAILED` | 호출했지만 결과를 쓸 수 없음(인증·입력 거부·서버 장애·시간 초과·응답 오류 등) | `null` |

- `SKIPPED`·`FAILED`에서 `result`는 항상 `null`이다. 실패를 점수 0이나 빈 결과로 바꾸지 않는다.
- `reason`은 원인 코드다. 지금 나올 수 있는 값:
  - 호출 전 보류(`SKIPPED`): `mapping_unverified`, `mapping_scope_mismatch`, `mapping_not_available`, `insufficient_history`, `missing_base_period`, `missing_sales`, `history_scope_mismatch`, `mapping_period_unverified`, `source_not_approved`, `source_approval_time_unverified`, `history_publication_unverified`, `sales_unit_unverified`, `invalid_reviewed_history`, `history_out_of_range`, `ai_call_forbidden_in_transaction`
  - 호출 후 실패(`FAILED`): `ai_disabled`, `ai_authentication_failed`, `ai_request_rejected`, `ai_timeout`, `ai_server_unavailable`, `ai_request_interrupted`, `ai_transport_failure`, `ai_unexpected_status`, `ai_response_content_type`, `ai_response_invalid`, `ai_response_too_large`, `invalid_ai_response`, `ai_request_failed`
- `transportStatus`는 실제 AI 호출 결과가 있을 때만 채운다: `DISABLED`, `AVAILABLE`, `AUTH_FAILURE`, `INVALID_AI_REQUEST`, `UPSTREAM_UNAVAILABLE`, `TIMEOUT`, `INVALID_RESPONSE`.
- `state`·`transportStatus` 값은 공개 응답에서 따로 정한 목록이다. 내부 상태 이름이 바뀌어도 응답 값이 함께 바뀌지 않는다.
- 현재는 승인된 지역·업종 코드 대응과 분기 매출 이력이 없어 실제 운영 데이터로는 항상 `SKIPPED` / `mapping_unverified`다.

## 오류

| HTTP·`error.code` | 상황 |
|---|---|
| 400 `INVALID_REGION_CODE` | 지역 코드 없음·형식 오류 |
| 400 `INVALID_REQUEST` | 본문 없음, 업종 코드 없음·형식 오류 |
| 404 `REGION_NOT_FOUND` / `BUSINESS_TYPE_NOT_FOUND` | 현재 선택 목록에 없는 지역, 현재 분류에 없는 업종 |
| 422 `BUSINESS_TYPE_NOT_SUPPORTED` | 등록됐지만 지원하지 않는 업종 |
| 503 `REFERENCE_DATA_NOT_READY` / `SUMMARY_DATA_NOT_READY` | 지역 기준정보·기본 현황 묶음 미게시 |
| 503 `DATA_SERVICE_UNAVAILABLE` | DB 연결·조회 실패 |
| 500 `INTERNAL_ERROR` | 게시 자료의 참조 무결성 오류 등 |

- **AI만 실패하면 오류가 아니라 200**이다. 통계를 유지하고 `ai.state=FAILED`로 알린다.
- **통계를 읽을 수 없으면 AI 대체 응답으로 숨기지 않는다.** 현황 미게시·DB 장애는 위 503이다.
- 입력 형식 오류는 DB를 읽기 전에 검사한다. DB 장애 중에도 잘못된 요청은 400이다.
- 성공·오류 응답 모두 `Cache-Control: no-store`이다.

## 내부 동작

1. Controller가 입력 형식을 검사하고, 서버 시계(한국 날짜)로 `asOf`와 직전 완료 분기 `basePeriod`를 정한다.
2. 읽기 전용 트랜잭션(`SummaryContextService.withContext`) 하나에서 선택 기준·기본 현황 묶음을 고정하고, 같은 시점에 현황 API와 같은 판정(`SummaryService.decide`)으로 두 지표를 계산한다.
3. 트랜잭션이 끝난 뒤에만 코드 대응·이력을 검증하고 AI(`POST /predict`)를 호출한다. 호출 중에는 DB 연결을 잡고 있지 않는다.
4. AI 결과와 관계없이 2번의 통계를 응답에 담는다.

## 확인 방법

```cmd
curl -i -X POST http://localhost:8080/api/ai/analyses -H "Content-Type: application/json" -d "{\"regionCode\":\"1111051500\",\"industryCode\":\"I21201\"}"
```

기본 현황 묶음을 게시하지 않은 DB에서는 503 `SUMMARY_DATA_NOT_READY`가 정상이다.
