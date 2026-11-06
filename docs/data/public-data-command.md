# 공공데이터 수동 CLI

> T12 2단계 · 수집 조건 검증과 실행 계획 생성
>
> 현재 모드는 PLAN_ONLY다. 웹 서버·DB·Flyway를 초기화하지 않으며 네트워크 요청·디렉터리 생성·원본 저장을 수행하지 않는다.

## 실행

저장소 루트에서 실행한다. Docker·MySQL·공공데이터 인증키는 필요하지 않다.

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
| output | 기본 data/raw | 절대 경로로 정규화. 기존 파일이면 거부하고 디렉터리는 생성하지 않음 |
| plan-only | 선택 플래그 | 값을 받지 않음. 생략해도 현재 구현은 PLAN_ONLY |

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
| networkRequestsExecuted / filesWritten | 현재는 모두 0. 다운로드 성공 보고가 아님 |

officialQuarterCount는 실제 조회 전이므로 425·414 같은 과거 관찰값을 끼워 넣지 않는다. 점포의 businessTypeCode도 요청별로 채워야 하므로 템플릿에 남긴다. 점포 원천 업종 목록은 검증된 기준정보 JSON에서 읽고 형식·중복·개수를 확인한다.

## 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 실행 계획 생성 성공 |
| 2 | 인자·지원 범위 오류 |
| 3 | 카탈로그·업종 스냅샷 로딩 또는 계획 생성 오류 |

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
  → PLANNED JSON 출력 후 종료
```

다운로드 모드에서는 일반 SpringApplication.run을 호출하지 않는다. 일반 서버·기준정보 적재는 기존 기동 경로를 사용한다.

## 검증

```cmd
gradlew.bat test --tests "*DataDownload*"
gradlew.bat clean build
```

CLI 전용 테스트는 Docker 없이 실행된다. 전체 빌드는 기존 DB 통합 테스트를 포함하므로 Docker가 필요하다.

입력 오류·분기 범위·출력 경로·점포 100업종·요청 템플릿·독립 main 기동을 검사한다. 실제 HTTP 다운로드·재시도·타임아웃·파일 확정·수집 메타데이터 생성은 다음 단계에서 구현한다.

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
