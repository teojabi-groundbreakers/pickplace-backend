# 공공데이터 수동 CLI

> T12 · 수집 계획 확인과 2026년 2분기 상주인구·직장인구·시설 원본 수집
>
> 기본 실행은 계획만 출력한다. `--download`를 지정하면 선택 데이터의 원본과 메타데이터를 저장한다. 두 모드 모두 웹 서버·DB·Flyway 없이 실행한다.

## 실행

저장소 루트에서 실행한다. Docker·MySQL·공공데이터 인증키는 필요하지 않다.

상주인구 원본 수집은 서울시 열린데이터광장에 연결해야 한다.

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=data-download --dataset=RESIDENT --quarter=20262 --output=data/raw --download"
```

직장인구와 시설도 같은 옵션으로 실행한다.

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=data-download --dataset=WORKER --quarter=20262 --output=data/raw --download"
gradlew.bat bootRun --args="--spring.profiles.active=data-download --dataset=FACILITY --quarter=20262 --output=data/raw --download"
```

상주인구 수집 계획만 확인하는 명령이다.

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=data-download --dataset=RESIDENT --quarter=20262 --output=data/raw --plan-only"
```

점포 수집 계획:

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=data-download --dataset=STORE --quarter=20262 --output=data/raw --plan-only"
```

빌드한 JAR도 같은 인자로 실행할 수 있다.

```cmd
java -jar build/libs/pickplace-backend-0.2.0.jar --spring.profiles.active=data-download --dataset=RESIDENT --quarter=20262 --output=data/raw --plan-only
```

이 JAR 이름은 현재 build.gradle의 version을 기준으로 한다. 버전이 바뀌면 실제 생성된 실행 JAR 이름을 사용한다.

## 입력

| 옵션 | 필수·기본값 | 검사 |
|---|---|---|
| spring.profiles.active | data-download | 다른 프로필과 혼합하지 않음. 환경변수 SPRING_PROFILES_ACTIVE로도 지정 가능 |
| dataset | 필수 | STORE / RESIDENT / WORKER / FACILITY. 소문자는 대문자로 정규화 |
| quarter | 필수 | YYYYQ 형식과 실행 카탈로그의 지원 분기 검사. 초기 범위는 20262 |
| output | 기본 data/raw | 절대 경로로 정규화. 실제 수집이 검증을 통과하면 저장 경로 생성 |
| plan-only | 선택 플래그 | 값을 받지 않음. 실행 모드 옵션을 생략해도 PLAN_ONLY |
| download | 선택 플래그 | 값을 받지 않음. RESIDENT / WORKER / FACILITY를 지원하며 plan-only와 함께 지정할 수 없음 |

형식상 유효해도 초기 수집 경로를 설정하지 않은 분기는 거부한다. 예를 들어 점포 20243을 2026년용 업종 분할 경로로 잘못 계획하지 않는다. 과거 연도 ZIP과 신규 분기는 경로를 검증·설정한 뒤 확장한다. 다른 분기에 원천 데이터가 없다는 뜻은 아니다.

알 수 없는 옵션·위치 인자·중복 옵션·빈 값은 거부한다. 일반 서버와 기준정보 적재는 기존 프로필로 실행하며 다운로드 모드와 섞지 않는다.

## 출력 읽기

상주인구 계획에서 주요 부분만 추린 예시다.

```json
{
  "status": "PLANNED",
  "mode": "PLAN_ONLY",
  "dataset": "RESIDENT",
  "sourceId": "OA-22183",
  "quarter": "20262",
  "strategy": "SHEET_QUARTER_FILTER",
  "partitionCount": 1,
  "unresolvedVariables": ["officialQuarterCount"],
  "networkRequestsExecuted": 0,
  "filesWritten": 0
}
```

전체 출력에는 저장 예정 절대 경로와 두 요청의 URL·POST 방식·폼 Content-Type·파라미터도 포함된다.

| 출력 | 의미 |
|---|---|
| countRequest | 해당 분기의 공식 총건수를 확인할 요청 |
| downloadRequestTemplate | 공통 값과 선택 분기를 반영한 다운로드 요청 틀 |
| unresolvedVariables | 후속 실행 단계에서 채워야 할 값 |
| partitionCount | 인구·시설 1개, 점포는 전체 원천 업종 100개 |
| firstPartitionCode | 점포 분할의 첫 코드. 전체 100개를 계획하며 지원 3개로 제한하지 않음 |
| networkRequestsExecuted / filesWritten | 계획 모드에서는 모두 0 |

officialQuarterCount는 실제 조회 전이므로 425·414 같은 과거 관찰값을 끼워 넣지 않는다. 점포의 businessTypeCode도 요청별로 채워야 하므로 템플릿에 남긴다. 점포 원천 업종 목록은 검증된 기준정보 JSON에서 읽고 형식·중복·개수를 확인한다.

## 원본과 메타데이터

`--download`는 공식 분기 총건수를 조회해 `gridTotalCnt`를 채운 뒤 원본을 요청한다. 원본의 JSON 구조, 행 수, 기준 분기, 행정동 코드 형식과 중복 여부를 검사한다. 선택 데이터의 핵심 필드 존재 여부도 확인한다. 숫자 정제와 지표 계산은 T13에서 처리한다.

검증을 통과하면 `<output>/<데이터 종류 소문자>/<분기>/<수집 ID>/`에 원본과 `metadata.json`을 저장한다. 메타데이터에는 원천 ID, 요청 주소와 조건, 수집 시각(UTC), 공식·실제 행 수, 바이트 수, SHA-256을 기록한다.

| 데이터 | 원천 ID | 원본 파일 | 핵심 필드 |
|---|---|---|---|
| 상주인구 | OA-22183 | `resident.json` | `tot_repop_co` |
| 직장인구 | OA-22184 | `worker.json` | `tot_wrc_popltn_co` |
| 시설 | OA-22169 | `facility.json` | `viatr_fclty_co` |

행 수는 매번 조회한 공식 분기 총건수와 비교한다. 직장인구를 포함해 데이터별 건수가 다를 수 있으므로 425행을 고정 조건으로 사용하지 않는다.

두 파일을 임시 폴더에 기록한 뒤 폴더를 한 번에 확정한다. 성공 시 `DOWNLOADED`와 두 파일의 절대경로를 출력한다. 다시 실행하면 새로운 수집 폴더를 만든다. 기본 경로인 `data/raw/`는 Git에서 제외한다.

연결 제한은 5초, 요청당 본문 수신까지 25초, 응답 크기는 최대 8 MiB다. 일시적인 연결·읽기 오류와 일부 HTTP 오류는 요청별 최대 3번 시도한다. 응답 내용 검증에 실패하면 해당 수집을 중단한다.

## 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 실행 계획 생성 또는 원본 수집 성공 |
| 2 | 인자·지원 범위 오류 |
| 3 | 설정 로딩, 계획 생성, 다운로드, 검증 또는 저장 실패 |

오류는 표준 오류에 안내하고 성공 JSON을 출력하지 않는다. 잘못된 입력을 정상 완료로 처리하지 않는다.

## 실행 설정과 조사 기록

| 파일 | 역할 |
|---|---|
| [실행 카탈로그](../../src/main/resources/data-download/source-catalog.json) | CLI가 classpath에서 실제 읽는 설정. 실행 JAR에도 포함 |
| [조사 카탈로그](public-data-source-catalog.json) | 1단계 수집 경로·판정 기준·관찰 결과의 참조 기록. 실행 설정과 구분 |
| [요청 검증 근거](public-data-source-verification.json) | 실제 원본 요청 조건·해시·행 수 |
| [원천 업종 스냅샷](../../src/main/resources/reference-data/seoul-reference.json) | 점포의 전체 업종 분할 대상 |

실행 설정은 필요한 값만 별도로 추렸고, 과거 행 수·해시 같은 관찰값은 실행 조건으로 사용하지 않는다. 경로 변경 시 [수집 기준](public-data-sources.md)의 재검증 근거와 실행 카탈로그를 함께 갱신한다.

## 코드 흐름

```text
PickplaceBackendApplication
  → data-download 모드 판별
  → DataDownloadCommand: 인자 검사
  → SourceCatalogLoader: 실행 카탈로그·필요한 업종 목록 읽기
  → DownloadPlanService: 요청 조건 조합
  → 기본 모드: PLANNED JSON 출력
  → --download: QuarterDownloadService에서 총건수 조회 → 원본 다운로드
                → 검증 → 원본·메타데이터 저장 → DOWNLOADED JSON 출력
```

다운로드 모드에서는 일반 SpringApplication.run을 호출하지 않는다. 일반 서버·기준정보 적재는 기존 기동 경로를 사용한다.

## 검증

```cmd
gradlew.bat test --tests "*DataDownload*"
gradlew.bat test --tests "*QuarterDownloadServiceTests"
gradlew.bat clean build
```

CLI 전용 테스트는 Docker 없이 실행된다. 전체 빌드는 기존 DB 통합 테스트를 포함하므로 Docker가 필요하다.

CLI 테스트는 입력 검증과 독립 기동을 검사한다. 분기별 서비스 테스트는 로컬 HTTP 응답으로 일시 오류 재시도, 원본과 메타데이터의 일치, 데이터별 요청·저장 경로, 다른 데이터의 응답을 받았을 때 저장이 차단되는지 확인한다.

### 2026-11-07 분기별 3종 수집 확인

| 데이터 | 공식 행 수 | 원본·고유 행정동 수 | 원본·메타데이터 체크섬 |
|---|---|---|---|
| 상주인구 | 425 | 425 | 일치 |
| 직장인구 | 414 | 414 | 일치 |
| 시설 | 425 | 425 | 일치 |

공통화한 서비스로 빌드한 JAR의 CLI를 실행해 세 데이터를 실제 수집했다. 전체 build는 157건 통과, 실패·오류·건너뜀 0건이다. 수집 관련 테스트는 CLI 33건과 서비스 9건으로 총 42건이다.

### 상주인구 최초 수집 기록

| 항목 | 결과 |
|---|---|
| 원천·분기 | OA-22183 · 20262 |
| 행 수 | 공식 425행 · 원본 425행 · 고유 행정동 425개 |
| 저장 | 원본 273,965바이트와 메타데이터 저장 |
| 무결성 | 저장 파일의 SHA-256을 다시 계산해 메타데이터와 일치 확인 |

### 2026-11-05 검증 결과

| 항목 | 결과 |
|---|---|
| CLI 전용 | 29건 통과 |
| 전체 clean build | 144건 통과 · 실패·오류·건너뜀 0건 |
| 문서의 bootRun 명령 | PLAN_ONLY 출력 후 정상 종료 |
| 실행 JAR | 상주인구·점포 계획 정상. 점포 분할 대상 100개 확인 |
| DB 독립 실행 | 환경변수로 접속 불가능한 DB 주소를 지정해도 CLI 정상 종료 |
| 입력 오류 | 잘못된 분기에 종료 코드 2 반환 |
| 파일 부작용 | 지정한 새 저장 경로를 생성하지 않음 |

이 PC의 한글 임시 경로 문제를 피하도록 전체 테스트에는 외부 Gradle init script로 테스트 JVM의 java.io.tmpdir만 ASCII 경로로 지정했다. 공유 프로젝트 설정은 변경하지 않았다.
