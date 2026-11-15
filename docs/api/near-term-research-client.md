# 서울 2026년 단기 모델 연구용 Spring 호출기

`NearTermResearchClient`는 AI 프로젝트의 별도 FastAPI `POST /research/near-term/predict`와 통신하는 **내부 연구용** 호출기다. 기존 `AiPredictClient`의 기본 `/predict`, 공개 `POST /api/ai/analyses`, FE 실제 분석 화면과 연결하지 않았다. 호출기 활성화나 HTTP 성공은 사용자 지역 코드 대응·입력 진위·모델 적합성 승인을 뜻하지 않는다.

## 요청과 결과

- 요청은 서울 원천 행정동 8자리 `seoul-adstrd`, 원본 `CS` 업종, 기준 `2026Q2`, 대상 `2026Q3` 또는 `2026Q4`, 동일 지역·업종의 `2025Q2`~`2026Q2` 5개 분기를 요구한다. H10이나 소진공 `I` 업종을 자르거나 추정해 바꾸지 않는다.
- `NearTermResearchRequest`는 코드·기간·결측·원천 공개 가능 날짜의 **형식과 내부 일관성**을 확인한다. `source_kind=official` 문자열만으로 공식 원본·사용 권리·과거 공개 시점이 검증되었다고 판단하지 않는다.
- 정상 HTTP 200의 연구 API 응답은 `NearTermResearchResult.transportStatus=RETURNED`에 담긴다. 모델의 `status=available`과 `status=unavailable`은 별개이며, 후자의 `estimated_yoy_growth=null` 및 `reason`을 그대로 보존한다. 경고, 모델 SHA-256, 대상·비교 분기, `retrospective_test`의 후향 오차도 유지한다. `input_values_verified=false`와 `region_scope_verified=false`를 요구하며 두 값이 참으로 오면 응답을 거부한다.
- 인증 실패·입력 거부·제한 시간 초과·AI 장애·응답 오류는 원본 본문이나 키를 내보내지 않는 분리된 상태와 사유로 반환한다. 리디렉션을 따르지 않고 응답을 1 MB로 제한한다. 미정의 디버그 필드는 반환하지 않는다.

## 환경변수

호출기는 기본 비활성이다. 켜더라도 공개 분석 API의 `mapping_unverified` 보류 동작은 바뀌지 않는다.

| 이름 | 역할 | 기본값 |
| --- | --- | --- |
| `PICKPLACE_NEAR_TERM_RESEARCH_ENABLED` | 내부 연구 호출기 활성화 | `false` |
| `PICKPLACE_NEAR_TERM_RESEARCH_BASE_URL` | 별도 근단기 FastAPI 루트 URL | 없음 |
| `PICKPLACE_NEAR_TERM_RESEARCH_API_KEY` | AI의 `PICKPLACE_NEAR_TERM_API_KEY`와 같은 내부 키 | 없음 |
| `PICKPLACE_NEAR_TERM_RESEARCH_CONNECT_TIMEOUT_MS` | 연결 제한 시간 | `1000` |
| `PICKPLACE_NEAR_TERM_RESEARCH_REQUEST_TIMEOUT_MS` | 전체 응답 제한 시간 | `5000` |

AI 모델 경로와 자료 파일은 BE 환경변수가 아니라 AI 서버에서 별도로 설정한다. 연구 모델은 수정 스냅샷 기반이며 2026년 실제 예측 오차는 아직 없다. 2026년 3분기는 종료된 기간의 미관측 사후 추정이고 4분기는 진행 중 기간의 추정이다. 후향 오차는 미래 신뢰구간이 아니다.

## 테스트

`NearTermResearchClientTests`는 가상 자료·로컬 모의 서버로 기본 비활성, 요청 범위, 성공/부분 결과, 오류·시간 초과와 응답 검증을 확인한다. `NearTermResearchLiveIntegrationTests`는 명시적 opt-in이며 실제 AI 프로젝트의 처리된 서울 CSV를 **읽기만** 하고 `scripts.make_near_term_request`로 임시 5분기 요청을 생성한다. FastAPI의 최신 모델 응답을 해당 산출물의 예측 CSV 및 모델 지문과 대조하고, 비교 분기 매출이 없을 때 `null`·사유가 유지되는지도 확인한다. 생성된 임시 요청은 테스트 후 삭제한다.

라이브 테스트는 `PICKPLACE_TEST_NEAR_TERM_URL`이 설정된 경우에만 실행된다. 추가로 테스트 전용 `PICKPLACE_TEST_NEAR_TERM_KEY`, `PICKPLACE_TEST_NEAR_TERM_AI_ROOT`, `PICKPLACE_TEST_NEAR_TERM_HISTORICAL_CSV`, `PICKPLACE_TEST_NEAR_TERM_MODEL_DIR`, `PICKPLACE_TEST_NEAR_TERM_REGION_CODE`, `PICKPLACE_TEST_NEAR_TERM_INDUSTRY_CODE`, `PICKPLACE_TEST_NEAR_TERM_EXPECTED_SHA256`를 로컬 환경에 설정한다. 키나 실제 자료를 Git에 넣지 않는다. 실제 BE 게시 DB·FE 사용자 선택과의 전 구간 검증은 승인된 코드 대응·매출 이력 연결 이후의 별도 작업이다.

2026-11-13 검증에서 단위 테스트 7건과 최신 서울 모델의 opt-in HTTP 통합 테스트 1건이 통과했다. 실제 서울 집계 CSV에서 만든 Q3·Q4 요청에 대한 Java↔FastAPI 결과가 최신 예측 CSV·모델 지문과 일치했고, 비교 분기 매출이 없는 요청은 HTTP 성공 안에서 성장률 `null`과 `missing_or_nonpositive_comparison_sales`를 유지했다. 이 검증은 모델의 미래 정확도나 FE 사용자 코드 대응을 증명하지 않는다.
