# PickPlace 지도·업종·AI 연동 개발 현황

확인일 2026-11-23 · 신규 개발자와 FE·AI 담당자용

**현재 개발 DB에서는 전국 소분류 247개를 선택하고 기본 현황을 조회할 수 있다.** 실제 AI 점수 공개는 후순위로 둔다. 분석 요청의 기본 통계와 AI 보류 안내는 유지한다.

## 코드와 환경 상태

| 작업 | 구현 범위 | 확인한 상태 |
| --- | --- | --- |
| T26 지도 위치 | A7에서 읍·면·동 대표 위·경도, 좌표계, 출처 및 누락 사유 반환 | [PR #40](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/40)으로 BE `develop`에 병합. 배포용 좌표 공개 검토와 FE 지도 검수는 별도 |
| 전국 업종 확대 | 승인된 소진공 소분류 247개 선택 및 현황 조회 | 개발 DB 반영. [PR #41](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/41)로 BE `develop`에 병합 |
| T29 AI HTTP 연동 | 기존 Spring → FastAPI 호출기의 오류 분류와 모델 버전 검증 보완 | [PR #35](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/35)의 기본 호출기는 병합됨. 후속 보완은 BE 로컬 `feature/t29-ai-transport`에 있음 |
| T28 지표별 분석 v2 | 승인된 대응 조회, 지표별 분기와 제공 사유, 서울 원천 참고 범위 | BE `feature/t28-ai-history-preparation`에 구현. 개발 DB에 V9 및 청운효자동 부분 대응 6건 반영 |
| FE 참고 분석 | 화면·저장 보고서에 서울 원천 업종 표시, v2 연동 설정 | FE 로컬 `feature/ai-reference-scope`에 구현. FE `develop` 병합·배포 전 |

## 전국 업종과 기본 현황

- 개발 DB의 게시 지역 버전은 `national-202609-sejong-hint-v1`, 업종 분류는 `semas-202609-service-v1`이다. 승인 소분류 247개 중 기존 3개에 244개를 추가 활성화했다.
- `GET /api/business-types`가 활성 소분류 목록을 반환한다. FE 실제 API 모드(`VITE_DEMO_MODE=false`)에서 이 DB를 사용하는 BE에 연결하면 247개를 표시한다.
- `GET /api/regions/{regionCode}/summary?businessTypeCode={code}`는 게시된 기본 현황의 주민등록인구와 해당 소분류 수록 업소 수를 반환한다. 현재 기준월은 2026년 9월이다. 같은 원본에 247개 집계가 있어 재적재 없이 지원 표시만 변경했다.
- 실제 0건은 숫자 `0`으로 표시한다. 결측·품질 보류는 `null`과 사유 코드로 구분한다. 개발 DB에서 청운효자동 백반/한정식 46개, 전/부침개 0개를 확인했다.
- 별도 DB는 대상 게시본을 확인한 후 브랜치의 `expand-industries` 명령을 실행해 같은 업종 범위를 적용한다. [업종 활성화 실행 절차](data/national-reference-import.md#4-게시된-분류의-전체-소분류-활성화)

## 지도 위치

`GET /api/administrative-dongs/{dongCode}/map`에 전국 목록의 H10 코드를 전달한다. 현재 지역 게시본과 일치하는 배치에서 경계 내부 대표점을 읽는다. 좌표가 없으면 HTTP 200과 `UNAVAILABLE` 및 사유를 반환한다.

개발 DB에서 경계 내부 대표점 3,558개와 경계 누락 7개를 확인했다. 공개 검토를 마친 배치를 `PICKPLACE_MAP_PUBLISHED_BATCH_ID`로 지정하면 좌표를 반환한다. 기본 설정에서는 좌표를 제공하지 않는다. 검증된 면적 출처 연결 전에는 `areaSquareMeters`가 `null`이다. [A7 응답과 설정](api/region-api.md#a7-읍면동-지도-위치)

## 분석 API와 서울 참고 분석

| 항목 | 동작 |
| --- | --- |
| 기존 분석 API | `POST /api/ai/analyses`가 기본 통계를 반환하고, 호출 조건을 충족하지 못한 AI는 `SKIPPED` 및 사유를 반환한다. [기본 통계 연결 PR #39](https://github.com/teojabi-groundbreakers/pickplace-backend/pull/39) |
| 지표별 v2 | `POST /api/ai/analyses/v2`가 위험도·적합도·성장 예측의 상태와 기준 분기를 각각 반환한다. 성장에는 예측 대상 분기도 담는다. |
| 기준 분기 | 최근 완료 분기와 그 직전 분기에서 지표별로 선택한다. 더 오래된 값은 전년 동기 비교와 연속 5분기 성장 입력에 사용한다. |
| 참고 분석 표시 | 승인된 부분 대응에 표시할 AI 결과가 있으면 `analysisScope.referenceOnly=true`와 `sourceIndustryName`을 반환한다. 화면과 보고서에 **서울 원천 업종 참고 분석 · 커피 전문점**처럼 실제 분석 범위를 표시한다. 기본 현황은 사용자가 선택한 소진공 업종으로 조회한다. |
| FE 연결 | BE v2가 준비된 환경에서 `VITE_AI_ANALYSIS_V2_ENABLED=true`로 설정한다. 기본값은 `false`이며 기존 분석 경로를 사용한다. |

개발 DB에서 청운효자동의 카페·치킨·미용실·편의점·자동차 세차장·가전제품 수리업 **6개 조합의 부분 대응을 승인**했다. 지표·면적 AI 사용 승인은 각각 0건이라 **현재 247개 업종 모두 실제 AI 점수는 제공하지 않는다.** v2에서 대응 승인 6건은 `insufficient_verified_data`, 대응이 없는 예시인 분식과 제과는 `mapping_unverified`를 반환했고 기본 현황은 유지됐다.

## 검증 기록

| 확인 대상 | 결과 |
| --- | --- |
| T26 BE | 전체 테스트 624건 중 621건 통과, 선택 실행 대상 3건 건너뜀. 실제 DB에서 좌표 반환·경계 누락·배치 미설정 확인 |
| 업종 확대 | BE 관련 테스트 89건 통과. 개발 DB에서 목록 247개, 추가 업종의 현황·분석 응답, 동일 명령 재실행 시 0건 변경 확인 |
| T29 | 관련 테스트 54건 통과 및 백엔드 빌드 성공. 연결 실패·인증·타임아웃·5xx·응답 검증과 POST 중복 호출 방지 확인 |
| T28·FE 참고 분석 | BE 관련 테스트 47건, FE 화면·보고서·v2 요청 테스트 25건 통과. 양쪽 빌드 성공. 별도 FE 테스트에서 실제 API 응답 형태로 247개 옵션 렌더링과 새 업종 코드 전달 확인 |

## 후순위 · 실제 AI 점수 공개

1. 위험도부터 재개한다. [2026Q2 위험도 입력 확인표](examples/seoul-reference-risk-coverage-2026q2.csv)에 소진공 우선 후보 46개의 서울 원천 업종 코드와 입력값이 있는 H10을 담았다.
2. 지역·업종 대응 및 자료별 사용 근거를 검토해 승인한다. 위험도는 기준 분기의 매출·점포·폐업 수와 전년 동기 매출이 필요하다. 적합도는 같은 분기의 점포·상주인구와 유효 면적, 성장 예측은 연속 매출 이력과 모델 지원을 확인한다.
3. 승인 조합으로 FE → Spring → FastAPI를 검증한다. 지표별 기준 분기, 서울 원천 참고 분석 표기, 자료 부족·AI 실패 시 기본 현황 유지를 확인한다.

관련 자료는 [247개 업종 전수 대조표](examples/seoul-industry-crosswalk-202609.csv), [1차 대응 결정 기록](examples/seoul-industry-release-decisions-202609.csv), [BE–AI 상세 연동 기록](api/ai-integration-issue17.md)에 있다.
