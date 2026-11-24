# Spring–FastAPI 분석 연동 — GitHub 이슈 #17

이 문서는 PR #35의 초기 연동과 T28의 후속 설계를 기록한다. 최신 개발 DB 상태와 적용 범위는 [지도·업종·AI 연동 개발 현황](../release-integration-status.md)을 참고한다. 이 문서는 [백엔드 이슈 #17](https://github.com/teojabi-groundbreakers/pickplace-backend/issues/17)의 진행 내용과 후속 작업을 설명한다. 기존 DB 스키마와 런타임 설정 파일은 바꾸지 않았고, `.env.example`에 비활성 AI 호출기 설정 예시만 추가했다. 아래 테스트의 관측값은 모두 가상이며 실제 AI 성능이나 창업 판단의 근거가 아니다.

## 추가한 구성

| 구성 | 역할 |
| --- | --- |
| `ai.AiPredictionRequest` | 승인된 원천·지역·업종 및 분기 이력에 대한 `/predict` 입력 계약과 기본 검증 |
| `ai.AiPredictClient` | FastAPI 기본 앱의 `POST /predict` 호출. 기본 비활성, 환경변수 인증·연결 및 요청 제한 시간, 리디렉션 거부, 응답 크기·범위·시점 검증 |
| `ai.AiPredictionResult` | 통신 결과 상태, 성공 시 AI 원본 구조, 실패 사유를 보존 |
| `analysis.AnalysisOrchestrator` | 통계 스냅샷과 승인된 이력 포트, AI 호출 포트를 조합. 사유를 확인할 수 없는 자료는 호출 전에 제외 또는 보류 |
| `analysis.PublishedSummarySnapshotReader` | 기존 게시 선택·요약본을 읽는 실제 Spring 조회 포트. 아직 매출·CS 업종 대응은 없으므로 비어 있는 이력을 반환 |
| `analysis.AnalysisConfiguration` | 조회 포트와 `AiPredictClient::predict`를 조합 로직에 연결하고 한국 시간 시계를 제공 |
| `ai.AiAnalysisController` | `POST /api/ai/analyses`의 입력 형식과 결과 봉투 제공 |

## 공개 분석 API

`POST /api/ai/analyses`는 `application/json` 요청을 받는다. FE는 원본 H10 지역 코드와 게시된 소진공 소분류 업종 코드를 그대로 보낸다.

```json
{"regionCode":"1111051500","industryCode":"I21201"}
```

성공하면 `{data,meta}` 봉투의 `data`에 `request:{regionCode,industryCode}`, `asOf`, `basePeriod`, `statistics:{version,metrics}`, `ai:{state,reason,transportStatus,result}`를 담는다. `asOf`는 서버 시계의 **한국 날짜(Asia/Seoul)**, `basePeriod`는 그 날짜에 **달력상 마지막으로 완료된 분기**다. 실제 자료가 확보·게시된 최신 분기와 같다는 뜻은 아니다. 선택 코드 형식 오류는 400, 게시 기준정보·요약본 미준비는 기존 오류 계약에 따라 503으로 구분한다.

`statistics.version`은 실제 게시 요약 월과 개정 번호에서 만든다. `PublishedSummarySnapshotReader`는 `SummaryContextService.withContext` 안에서 게시본을 검증하고, 같은 읽기 시점에서 현황 API와 같은 판정(`SummaryService.decide`)으로 `registeredPopulation`·`listedStoreCount`를 계산해 불변 스냅샷으로 반환한다. 공개 응답의 `statistics`는 현황 API와 같은 지표 모양(값·단위·상태·사유·출처)과 기준월·게시 시각이며, 자세한 내용은 [지역·업종 분석 API](analysis-api.md)를 따른다. 해당 트랜잭션은 AI 호출 전에 종료된다. 승인된 분기 매출과 서울 CS 업종 대응표가 아직 없으므로 `mapping=null`, `history=[]`를 전달하고 `ai.state=SKIPPED`, `ai.reason=mapping_unverified`, `transportStatus=null`, `result=null`을 반환한다. 가짜 매출·승인 시점이나 분기 통계를 생성하지 않는다.

나중에 실제 자료가 연결되면 `AVAILABLE` 응답의 `ai.result`는 AI 서버가 보낸 JSON 중 범위·시점·형식을 검증하고 허용된 필드만 남긴 값이다. `SKIPPED`와 `FAILED`에서는 `result=null`이고, `transportStatus`는 실제 AI 호출 결과가 있을 때만 채운다. FE는 `state`와 `reason`을 함께 확인한다.

`/predict`는 별도 서울·경기 공동 연구 API의 `/research/unified/predict` 및 서울 현황 API와 **다른 앱과 계약**이다. 이 구현은 이슈에 명시된 기본 `/predict`만 대상으로 한다.

최신 서울 2026Q2 단기 연구 모델의 `/research/near-term/predict`에는 [별도 내부 호출기](near-term-research-client.md)를 준비했다. 이 호출기는 공개 분석 API와 연결하지 않았으며 승인된 원천 코드 대응·매출 이력이 마련될 때까지 연구 계약 테스트에만 사용한다.

클라이언트의 `AVAILABLE`은 HTTP 응답의 범위·형식이 검증됐다는 뜻이다. 그 안의 `growth`, `risk`, `fit`은 각각 `available` 또는 `unavailable`일 수 있다. 예를 들어 모델 미준비는 HTTP 200의 `growth.status=unavailable`과 `reason=model_not_found`로 돌아올 수 있으며 위험·적합도 초안 지수는 별도로 존재할 수 있다. 성장률 `0.05`는 5%라는 비율이고 점수·성공 확률이 아니다. `policy_version`, 계약상 상세와 경고를 보존하며 미정의 필드는 제외한다. FE의 새 분석 화면은 상태·사유·경고를 구분해 표시한다. 알 수 없는 사유 코드는 원문 코드로 표시하므로 새 코드를 추가할 때 문구를 함께 검토해야 한다.

클라이언트는 인증 실패, AI 입력 거부, 제한 시간 초과, AI 서버/통신 장애, 응답 형식 오류를 구분한다. 실패 응답은 원본 이력, 인증키, 응답 본문, URL 내부 정보나 스택 트레이스를 포함하지 않는다. AI가 실패해도 orchestration 결과는 별도로 받은 통계와 AI 불가 상태를 보존한다. DB 조회 자체 실패는 통계 없음으로 바꾸지 않는다.

기본 `/predict` 응답의 스키마 버전은 문자열 `"1.0"`이어야 하고, 정책 버전은 필수다. 같은 JSON 필드가 중복되거나 응답 객체 뒤에 값이 더 붙으면 형식 오류로 처리한다. 지역·업종·기준 시점은 요청과 일치하는 문자열이어야 한다. 위험도·적합도 점수는 0~100이며, 성장 예측값이 제공되면 응답 전체와 `growth`에 동일한 모델 버전이 있어야 한다. 모델 버전이 없는 경우 성장 상태는 `unavailable`로 반환된다. 이미 수신한 오류 상태는 본문이 크거나 지연돼도 상태 코드로 분류한다. 애플리케이션은 인증 실패, 타임아웃, 5xx에도 분석 POST를 재호출하지 않는다.

## 안전한 이력 준비와 부분 결과

`AnalysisOrchestrator.SnapshotReader`는 게시된 선택·요약 및 향후 검증된 코드 대응·원본 이력을 **하나의 불변 결과로 만든 뒤 읽기 트랜잭션을 종료**하는 포트다. AI 호출은 그 결과를 받은 이후 실행한다. 활성 트랜잭션 중 호출은 보류한다. `Predictor`는 `AiPredictClient::predict`에 연결했다.

지역 및 업종 대응은 승인 여부·버전·승인일·기준 기간을 확인하며 H10 및 업종 원요청을 결과에 보존한다. `available_at`은 실제로 사용 가능했던 시점으로 전달한다. 원천 승인·공개·사용 가능 시점을 예측 기준일과 비교하고, 서로 다른 인구·점포·면적 값에는 개별 출처·승인·공개·단위·범위 근거가 필요하다. 검증되지 않은 보조 값은 그 값만 제외한다. 승인/공개·매핑·단위 근거가 없는 매출 이력으로 AI를 호출하지 않는다. 이 검증 플래그와 날짜는 **실제 승인 기록을 조회하는 후속 어댑터**가 채워야 하며, 임의로 `true`나 과거 날짜를 만들어서는 안 된다.

현재 조회 포트는 게시 요약본 확인까지만 수행하며 분기별 매출·공개 지표 값은 제공하지 않는다. 기존 백엔드의 `SummaryContextService.withContext` 콜백 안에 외부 HTTP 호출을 넣지 않는다. 후속 자료 연결 때도 읽기 트랜잭션을 종료한 다음 AI를 호출해야 한다.

## 설정과 연결을 준비할 때

변경은 기존 설정 파일이 아닌 환경변수로 주입한다. 기본값은 비활성이다. 현재는 승인 매출·코드 대응이 없어 활성화해도 공개 API에서 `/predict`를 호출하지 않는다.

| 환경변수 | 의미 | 기본값 |
| --- | --- | --- |
| `PICKPLACE_AI_ENABLED` | 호출기 활성화 | `false` |
| `PICKPLACE_AI_BASE_URL` | FastAPI 기본 앱의 루트 주소 | 없음 |
| `PICKPLACE_AI_API_KEY` | AI 앱 `PICKPLACE_API_KEY`와 일치하는 내부 인증키 | 없음 |
| `PICKPLACE_AI_CONNECT_TIMEOUT_MS` | 연결 제한 시간 | `1000` |
| `PICKPLACE_AI_REQUEST_TIMEOUT_MS` | 응답 본문 수신까지 포함한 요청 제한 시간 | `3000` |

활성화할 때만 기본 URL과 키가 필요하다. API 키는 Git·문서·로그에 넣지 않는다. AI 서버는 내부 네트워크에서 접근하도록 배포 환경의 네트워크 정책을 설정한다. 개발에서 loopback 주소를 쓰더라도 실제 서비스에 외부 공개된 AI 포트를 전제하지 않는다.

이 설정은 클라이언트를 사용할 수 있게 준비할 뿐, 누락된 매출 조회·코드 연결을 자동 생성하지 않는다. 서버 실행 시 기존 DB의 Flyway 적용 정책을 유의하며, 테스트를 위해 개발 DB를 변경하지 않는다.

## T28 지역·업종 대응 검토

[청운효자동 카페 검토 후보](../examples/ai-scope-cheongunhyo-coffee.json)는 H10 `1111051500`과 서울시 행정동 코드 `11110515`를 공식 명칭·상위 지역으로 대조한 결과다. `I21201` 카페와 `CS100010`은 `PARTIAL`로 기록한다. 서울 원천의 분석 범위는 **커피 전문점**이며, 소진공 원본의 동일 지역 카페 80건 가운데 75건이 KSIC 56221 커피 전문점, 5건이 KSIC 56229 기타 비알코올 음료점업이었다.

서울 202609 소진공 원본에는 `I21007`에 서울 `CS100008`의 세세분류 밖 표준산업분류가 함께 수록되어 있다. `I21001`도 서울 `CS100005`가 정의한 KSIC 56191 외에 여러 분류를 포함한다. 두 조합의 공개 범위는 근거를 추가로 검토해야 한다. [서울시 생활밀접업종 정의](https://golmok.seoul.go.kr/images/100_v3.pdf) · [소진공 상가업소 정보](https://www.data.go.kr/data/15012005/openapi.do)

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,ai-scope-review --confirm=true --scope-review=docs\examples\ai-scope-cheongunhyo-coffee.json
```

명령은 현재 게시된 서울 지역과 승인 업종 분류 버전을 확인한 뒤 `PENDING` 대응을 등록한다. 동일 근거의 재실행은 같은 기록을 반환한다. 공개 사용 판정은 대응 승인과 자료별 승인·게시 및 지표 검증을 통과해야 하며, 부분 대응 결과에는 실제 서울 업종 범위를 표시한다.

### 전체 소분류 검토 결과

[247개 소분류 대조표](../examples/seoul-industry-crosswalk-202609.csv)는 승인 분류 `semas-202609-service-v1`, 소진공 202609 서울 원본 552,422행, 서울시 공식 생활밀접업종 100개 정의를 대조한 검토 자료다.

| 상태 | 전체 | 이번에 늘린 244개 | 의미 |
| --- | ---: | ---: | --- |
| `PRIORITY_REVIEW` | 74 | 72 | 상위 후보가 양쪽 표본에서 80% 이상이며 공통 행이 30건 이상 |
| `REVIEW` | 135 | 134 | 공통 KSIC를 관측했지만 범위 차이·표본·복수 후보를 확인해야 함 |
| `NO_SHARED_KSIC` | 38 | 38 | 이번 서울 원본과 서울시 정의로 공통 KSIC를 관측하지 못함 |

`semasPercent`는 선택 소진공 코드에서 후보 KSIC에 속한 행의 비율이다. `ksicPercent`는 같은 후보 KSIC에 속한 서울 원본 전체 행 중 선택 소진공 코드의 비율이다. 모수가 다른 두 비율을 함께 써서 서울 쪽 정의가 여러 업종을 포괄하는 경우도 확인한다. 공통 행 수가 가장 많은 후보를 먼저 표시하고 나머지 후보를 `otherCs`에 남긴다. 우선순위는 검토 순서로 사용한다.

청운효자동에서 다섯 분기의 유효한 게시 매출·점포 행까지 확인한 치킨(`I21006 → CS100007`), 미용실(`S20701 → CS200028`), 편의점(`G20405 → CS300002`)을 부분 대응 검토 후보로 추가한다. 대상별 실제 분석 범위는 각각 치킨 전문점, 두발 미용업, 체인화 편의점이다.

대조표 재생성은 Python과 `pypdf`로 `tools/audit_seoul_industry_crosswalk.py --help`를 참고한다. 스크립트는 승인 분류 스냅샷의 원본 SHA-256과 소진공 ZIP을 대조하고, 수록 정보는 업종별 집계만 내보낸다. [서울시 공식 분류표](https://golmok.seoul.go.kr/images/100_v3.pdf) · [소진공 상가업소 정보](https://www.data.go.kr/data/15012005/openapi.do)

### 1차 대응 승인 기록

[소분류 247개 결정표](../examples/seoul-industry-release-decisions-202609.csv)는 첫 승인 시점의 상태를 기록한다. 후속 확대는 서울 원천 업종 참고 분석 정책과 위험도 입력 확인표를 따른다. 청운효자동 6개 조합은 `APPROVED_PARTIAL_SCOPE`다. 201개는 범위·지역별 자료 검토를 남기고, 2개(분식·제과)는 원천 범위 차이로, 38개는 공통 KSIC 관측이 없어 보류한다. 다른 지역도 해당 지역에 대해 검증된 대응이 필요하다.

확정된 부분 범위는 커피 전문점, 치킨 전문점, 두발 미용업, 체인화 편의점, 자동차 세차업, 가전제품 수리업이다. 2025Q2~2026Q2에 해당 서울 원천 동·업종 조합의 게시 매출·점포 행을 확인했다. 이 기간은 대응 검토 기간이다. AI 결과의 공개 기준 분기는 최근 완료 분기와 직전 분기이며, 더 오래된 자료는 검증된 비교값이나 성장 입력으로 사용한다.

확정된 파일을 등록할 때는 위 명령에 `--approve-by=실제_검토자`를 추가한다. 확정은 기존 근거와 버전을 다시 검사하고, 같은 기록을 재실행해도 승인자·승인 시각을 보존한다. 대응을 확정해도 지표별 AI 사용 승인이나 모델 지원 범위가 자동으로 생기지 않는다.


### 서울 원천 업종 참고 분석

부분 대응이 승인되고 제공 가능한 지표가 있으면 v2의 `data.ai.analysisScope`를 반환한다. `industryMatch=PARTIAL`의 `referenceOnly=true`는 화면과 보고서에 **서울 원천 업종 참고 분석**과 `sourceIndustryName`을 표시하라는 뜻이다. 선택 업종의 기본 현황은 기존 소진공 코드로 조회한다. AI 지표마다 기준 분기와 제공 상태를 유지한다.

[2026Q2 위험도 입력 확인표](../examples/seoul-reference-risk-coverage-2026q2.csv)에 우선 검토 업종 46개의 서울 원천 후보와 유효한 H10 목록을 담았다. Q2 매출·전체 점포 수·폐업 수와 전년 동기 매출이 존재하며, 비교 매출과 점포 수가 양수인 게시 행으로 확인했다. 이 표는 검토 대상이다. 실제 참고 분석은 지역·업종 대응, 자료별 사용 승인 및 지표 입력 조건을 충족한 조합에서 제공한다.

FE에서 백엔드 v2를 연결할 때 `VITE_AI_ANALYSIS_V2_ENABLED=true`로 설정한다.


## 남은 의존 작업

1. 서울 원천 행정동 8자리와 FE H10, 서울 CS 업종과 FE 소진공 업종을 **승인된 시점별 대응 자료**로 확인한다. 자릿수 절단·이름 일치로 변환하지 않는다.
2. 승인·게시 가능한 분기별 매출 이력과 실제 최초 공개·정정·확보 시점, 통화 및 집계 정의를 확보하고, 검증 가능한 DB 조회 어댑터를 구현한다. 현재 DB의 전국 인구·업소 스냅샷을 매출 이력으로 대체하지 않는다.
3. 공개 AI API의 기본 통계는 현황 판정으로 채웠다([지역·업종 분석 API](analysis-api.md)). 새 지표가 추가될 때 FE의 값·null·사유 표시를 함께 검증한다.
4. 모델 파일·버전·공개 범위·사용 승인과 AI 서버 내부 네트워크를 준비한다. 사용자에게 제공할 수 있는 지표와 초안 정책의 제한을 확정한다.
5. 실제 격리 DB와 실제 AI 서버를 사용한 경계·승인·시간·오류·부분 실패 통합 테스트 및 FE 계약 테스트를 수행한다.

## 확인한 테스트

- 2026-11-24 T29 후속 검증은 최신 `develop`을 기준으로 모의 서버, 로컬 FastAPI, 격리 MySQL 분석 API를 실행했다. 74건 중 73건 통과, 단기 연구 모델 선택 실행 1건 건너뜀, 빌드 성공. 로컬 기본 FastAPI는 인증키 미전송 시 401을 반환했고, 유효한 키와 테스트 이력을 사용한 실제 HTTP 계약 테스트 2건은 통과했다. 실제 서비스 자료·운영 네트워크 검증은 T32에서 확인한다.

- Java 25와 프로젝트의 원래 Gradle 설정을 사용한 격리 단위 테스트에서 신규 38건과 기존 현황 판정 관련 29건, 총 67건을 통과했다. Docker 기반 MySQL 전체 통합 테스트는 별도로 실행하지 않았다.
- 모의 HTTP 서버의 정상/부분 응답, 인증 오류, 요청 거부, 서버 장애, 시간 초과, 리디렉션 및 크기 초과·범위 오류를 검증한다.
- 도메인 테스트는 검증되지 않은 대응·원천·기간을 보류하고 AI 실패 시 통계를 보존한다.
- 실제 기본 FastAPI를 loopback에 임시 실행해 Java 클라이언트와 HTTP 계약, 그리고 `POST /api/ai/analyses` 공개 컨트롤러부터 FastAPI까지의 응답 봉투를 확인했다. 실행된 실제 HTTP 테스트 2건은 모두 통과했다. 이때 가상 fixture를 테스트 목적으로 `official` 형식에 넣고 빈 모델을 사용했으므로 실제 공식 자료·예측 성능 검증이 아니다. 모델 파일 부재에 따라 성장률은 `null`·`model_not_found`, 위험·적합도 초안은 별도 제공됐다.
- 신규 공개 API의 형식·불가 사유·검증 전 DB 미조회·게시 요약본 미준비 503과 분기 경계를 웹 계층 테스트에서 확인했다. Docker가 실행 중이 아니어서 실제 MySQL Testcontainers 통합 테스트는 이번 실행에 포함되지 않았다.
