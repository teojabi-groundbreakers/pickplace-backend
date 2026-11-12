# 서울 행정동 상세 통계 적재

전국 기본 통계는 유지하고 서울 행정동별 추정매출·길단위인구·직장인구·시설·점포/개폐업 집계를 추가한다. 적재와 기술 검수를 위한 작업이며 **기존 공개 API·AI 입력·전국 DEFAULT를 변경하지 않는다.**

| 자료 | 보유 적재 범위 | 원천 | 관측 키 |
|---|---|---|---|
| 추정매출 | 2021Q1~2026Q2 | [OA-22175](https://data.seoul.go.kr/dataList/OA-22175/S/1/datasetView.do) | 분기×서울 행정동×서울 업종 |
| 점포·개폐업 | 2021Q1~2026Q2 | [OA-22172](https://data.seoul.go.kr/dataList/OA-22172/S/1/datasetView.do) | 분기×서울 행정동×서울 업종 |
| 길단위인구 | 2024Q3~2026Q2 | [OA-22178](https://data.seoul.go.kr/dataList/OA-22178/S/1/datasetView.do) | 분기×서울 행정동 |
| 직장인구 | 2024Q3~2026Q2 | [OA-22184](https://data.seoul.go.kr/dataList/OA-22184/S/1/datasetView.do) | 분기×서울 행정동 |
| 시설 | 2024Q3~2026Q2 | [OA-22169](https://data.seoul.go.kr/dataList/OA-22169/S/1/datasetView.do) | 분기×서울 행정동 |

상주인구 추가 적재·상권별/골목상권·관광 자료는 이번 대상이 아니다. 데이터 원본은 Git에 넣지 않는다.

출처: 서울특별시 서울 열린데이터광장 / 서울신용보증재단. 각 원본의 수집 시각·출처 URL·해시는 적재 명세와 artifact에 남긴다.

## 기존 자료와 연결하는 방식

| 항목 | 처리 |
|---|---|
| 전국 인구·업소·현황 | V5/V6 및 기존 조회 서비스 그대로 유지 |
| 과거 서울 테이블 | V1/V2와 8자리 FK를 수정하지 않음 |
| 신규 통계 | V7 `regional_statistic_*`로 분리, 원천·분류·기간 명시 |
| 지역 연결 | 승인된 전국 스냅샷의 검증된 원천8→H10 대응과 공식 서울 동 명칭을 교차 확인. `제1동/1동`·`·/.` 표기만 정규화하고 지명 `홍제`는 보존 |
| 과거 지역 | 공식 생성·말소일과 분기 전체를 비교. 경계 변경 당분기·불명확 대응은 HOLD |
| 업종 연결 | CS 코드를 `SEOUL_CS100`으로 보존. SEMAS 코드로 치환하지 않음. 공통 업종 대응은 별도 의미 검토 후 추가 |
| 기간 | 원천 분기 유지. 월별 자료로 나누거나 전국 DEFAULT에 자동 합류하지 않음 |

2026년 기준 대응표가 과거 모든 시점의 동일 경계를 증명하지는 않는다. 생성·말소 이력으로 확인할 수 없는 경계 개편/분류 의미는 후속 비교 가능성 검토에서 추가 확인해야 한다. 해당 검토 없이 과거 숫자를 현재 지역에 재분배하지 않는다.

## 저장 구조

| 테이블 | 역할 |
|---|---|
| `regional_statistic_batch` | 자료 정의·단위·집계 의미·원천/대응 버전·지문·시도·건수·승인 상태 |
| `regional_statistic_artifact` | 원본 경로·URL·SHA256·크기·수집 metadata |
| `regional_statistic_row` | 분기·원천 지역/업종·H10·대응/시점 상태·지표 값/상태·원문·행번호 |
| `regional_statistic_issue` | 원문 키·행번호·보류/오류/중복 근거 |
| `regional_statistic_publication` | 원천×자료×기간의 명시적 게시 배치·revision |
| `regional_statistic_publication_event` | 기존/새 배치·revision·주체·사유 |
| `regional_statistic_mutex` | 첫 적재/재시도·게시 경합 직렬화 |

`published_regional_statistic`은 APPROVED 배치의 명시적으로 게시된 기간 중 VALID 행만 읽는다. 전국 DEFAULT와는 별개인 내부 조회 경로다. 값의 단위·정의는 해당 배치의 `definition_json.metrics`를 함께 읽는다. 정의를 매 행에 복제하지 않는다.

원문 행번호는 JSON `DATA` 또는 CSV 헤더를 제외한 논리 레코드 기준으로 1부터 센다. 파생 점포 2026Q1은 `raw_payload`의 부모 파일·행번호도 함께 확인할 수 있다.

## 값·품질·업무 해석의 경계

- 원천의 실제 0은 0, 빈값은 null/NO_DATA, 음수·안전 정수 범위를 넘는 값은 INVALID다. 원문은 남긴다.
- 합산 가능 여부를 확인하지 않은 성별·연령·시간대·시설 유형을 임의 합산하지 않는다.
- 매출 원, 건수, 원천 길단위인구 수치는 보존하되 `SOURCE_ONLY` 정의의 지표는 `UNVERIFIED`로 저장한다. 매출의 ‘당월’을 월평균/분기 합계로 단정하지 않고, 길단위인구를 고유 방문자나 실제 고객 수라고 부르지 않는다.
- 점포 원천 비율도 분모·산식 확인 전 `UNVERIFIED`다. 값을 다시 계산하거나 0분모를 0%로 바꾸지 않는다. 개폐업은 집계 통계이고 개별 사업체 사건 이력이 아니다.
- 기술 검수용 APPROVED/내부 게시가 `UNVERIFIED` 지표의 업무 해석·공개 API·AI 활용 승인을 뜻하지 않는다. 소비자는 지표 status와 정의를 반드시 확인해야 한다.
- 서울 점포 집계와 SEMAS 수록 업소 수는 다른 지표다. 합산·덮어쓰기하지 않는다.
- 현재 수록 범위에 없는 동·업종에 0행을 생성하지 않는다. 2026Q2 직장인구 414행과 다른 자료 425행의 차이도 그대로 보존한다.

공식 [상권분석 화면](https://golmok.seoul.go.kr/main.do)은 월평균 매출·일평균 유동인구를 표시하며 개폐업률의 일반 산식을 설명한다. 다만 화면의 가공값과 다운로드 원본 필드가 어떤 변환으로 연결되는지까지 확인한 것은 아니다. 이 설명만으로 원본 금액을 3으로 나누거나 원본 인구를 일수로 나누지 않는다. 시설의 분기 반복값도 원천 갱신 방식과 구분하며, 숫자가 같다는 이유만으로 다른 분기를 중복 삭제하지 않는다.

서울 업종 코드북의 버전을 별도로 확정하기 전에는 원천 파일 해시·분기·코드·코드명을 함께 보존하고, 같은 코드가 모든 시점에 같은 의미라고 단정하지 않는다.

## 실행 순서

### 1. 원본 준비와 명세 생성

명령은 저장소 폴더에서 실행한다. Python 표준 라이브러리만 사용한다.

```cmd
python tools\prepare_seoul_detail.py --data-dir "..\PickPlace-데이터-정리\02-서울상세자료\01-행정동별" --reference data\reference\national-202609-sejong-hint-v1\snapshot.json --output "..\data-validation\2026-11-11\t20-seoul\manifests"
```

원본 metadata의 SHA256·건수·기간을 확인해 `catalog.json`, 대응표와 파일별 적재 명세를 생성한다. 각 명세는 원본·대응표·승인 기준정보 해시와 필드 별칭·단위·정의를 고정한다.

연도별 ZIP은 2021~2025년 자료로 사용하고, 중복되는 업종별 JSON은 다시 적재하지 않는다. 점포 2026Q1만 업종별 원본에서 추출해 별도 파생 파일로 만든다. 파생 파일 metadata에는 부모 원본 100개의 경로·해시·수집 metadata, 각 행에는 부모 파일/행번호를 보존한다. 기존 원본을 수정하지 않는다.

### 2. 기존 기준정보를 준비하고 DB 연결 설정

[전국 기준정보 적재](national-reference-import.md)에 따라 명세에 지정된 스냅샷이 해당 DB에 APPROVED 상태로 있어야 한다. 명세의 해시가 DB 승인 스냅샷과 다르면 적재를 거부한다.

```cmd
set "DETAIL_JDBC_URL=jdbc:mysql://localhost:3307/pickplace?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
set "DETAIL_DB_USER=사용할_DB_계정"
set "DETAIL_DB_PASSWORD=해당_계정_비밀번호"
gradlew.bat bootJar
```

애플리케이션 실행 시 Flyway가 필요한 미적용 마이그레이션을 순서대로 적용한다. 기존 Flyway SQL 파일을 수정하거나 삭제하지 않는다.

### 3. 자료별 적재 → 검토 → 승인 → 기간별 게시

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=regional-detail --action=import --input="..\data-validation\2026-11-11\t20-seoul\manifests\worker-20262.manifest.json" --confirm=true
```

성공 출력의 `batchId`로 `regional_statistic_batch.validation_report`와 issue를 확인한다. READY는 적재·검증 완료이며 게시 상태가 아니다.

38개 전체를 순서대로 적재하려면 다음 명령을 사용한다. 카탈로그와 각 명세·원본을 먼저 검증하고 파일별로 적재한다. 도중 실패 후 같은 명령을 다시 실행하면 앞서 완료된 파일은 REUSED 처리한다. 이 명령은 승인·게시를 자동 실행하지 않는다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=regional-detail --action=import-catalog --catalog="..\data-validation\2026-11-11\t20-seoul\manifests\catalog.json" --confirm=true
```

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=regional-detail --action=approve --batch-id=실제배치ID --actor=검토자 --confirm=true
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=regional-detail --action=publish --batch-id=실제배치ID --period=20262 --expected-revision=0 --actor=게시자 --reason=검토결과 --confirm=true
```

첫 게시는 revision=0만 사용한다. 정정·복구는 승인된 새/과거 배치를 지정하고 현재 `--expected-revision`과 `--previous-batch-id`를 함께 전달한다. 이미 바뀐 포인터를 덮어쓰지 않는다. 한 원본이 여러 분기를 포함하면 분기마다 명시적으로 게시한다.

### 4. 실패·중복·재실행

- 500행 청크마다 실제 커밋한다. 중간 실패 배치는 FAILED로 남고 게시하지 않는다.
- 지문은 원본 해시·원천/자료·기간·분류·대응표·필드/단위/정의·인코딩·writer 버전으로 만든다. 저장 경로·재다운로드 시각·receipt만 바뀐 동일 성공 입력은 REUSED이며 새 행/배치를 만들지 않는다. 재사용 시 최초 원본 수집 이력은 유지한다.
- 같은 키·같은 내용은 duplicate로 회계 처리한다. 내용이 충돌하면 첫 행도 INVALID로 바꾸고 충돌 원문을 이슈에 보존한다.
- 실패한 동일 입력은 새 attempt로 재시도한다. 기존 실패 시도를 지우지 않는다.
- 비정상 종료된 RUNNING은 실행 중인 작업이 없음을 확인한 뒤 `--action=recover --batch-id=... --actor=... --reason=... --confirm=true`로 실패 처리한다. 살아 있는 writer는 다음 청크 상태 검사에서 중단된다.

## 테스트와 실제 보유 원본 검수

```cmd
gradlew.bat check bootJar
gradlew.bat verifyRegionalDetail -PdetailCatalog="..\data-validation\2026-11-11\t20-seoul\manifests\catalog.json" -PdetailEvidence="..\data-validation\2026-11-11\t20-seoul\real-verification.json"
```

두 번째 명령은 별도 임시 MySQL에 실제 원본 전체를 적재·내부 승인/게시하고 건수·재실행·기존 게시 경계를 검증한다. 결과는 파일별로 저장하고, 실패하면 완료 파일 목록과 실패 사유를 보존한다. 개발 DB를 변경하지 않으며 임시 DB는 종료 후 삭제된다. 결과 파일과 공유 DB 적재/서비스 배포를 구분한다.

### 2026-11-11 실행 결과

| 자료 | 원천 행 | 채택 | 대응 보류 | 오류 | 동일 중복 |
|---|---:|---:|---:|---:|---:|
| 추정매출 | 376,485 | 374,097 | 2,386 | 2 | 0 |
| 길단위인구 | 3,400 | 3,376 | 24 | 0 | 0 |
| 직장인구 | 3,312 | 3,296 | 16 | 0 | 0 |
| 시설 | 3,400 | 3,376 | 24 | 0 | 0 |
| 점포·개폐업 | 775,084 | 765,450 | 5,268 | 0 | 4,366 |
| 합계 | **1,161,681** | **1,149,595** | **7,718** | **2** | **4,366** |

- 격리 MySQL에서 38개 배치·68개 기간별 게시와 읽기 행 회계를 검증했다.
- 개발 DB `pickplace`에 실제 38개 배치·1,157,315행을 READY로 저장했다. 동일 목록 재실행은 38개 모두 REUSED였으며, 기존 21개 테이블의 전후 체크섬이 일치했다.
- 청운효자동 2026Q2의 5종 표본에서 원문과 저장 값·결측 상태 118개 필드를 대조했다.
- 자동 테스트 490건 및 실행 JAR 빌드 통과. 개발 DB의 상세 게시 포인터는 0건이다.
- 일원2동·상일동·용신동의 미확정 대응은 보류했다. 매출 원본 두 행의 시간대 매출 건수 오류는 원문을 남기고 INVALID로 격리했다.

[검증 기록 JSON](seoul-detail-import-validation.json)에서 원본별 건수·테스트·개발 DB 검수 근거를 확인할 수 있다.

READY 데이터를 검토할 때는 게시 뷰 대신 배치와 원천 행을 조회한다.

```sql
SELECT b.dataset_code, r.period_key, r.source_industry_code, r.row_status, r.metrics
FROM regional_statistic_row r
JOIN regional_statistic_batch b ON b.batch_id = r.batch_id
WHERE b.source_system = 'SEOUL_COMMERCIAL' AND b.status IN ('READY', 'APPROVED')
  AND r.region_code = '1111051500' AND r.period_key = '20262'
  AND r.source_industry_code IN ('', 'CS100010');
```

## 다른 원천을 추가할 때

### 코드 읽는 순서

| 클래스 | 책임 |
|---|---|
| `RegionalDetailRunner` | 프로필·명령 인자 검증, 명령 실행 |
| `DetailCatalogImporter` | 전체 파일 목록 사전 검증, 파일별 적재와 재실행 |
| `DetailInputLoader` / `DetailImportManifest` | 해시·기간·단위·코드 체계를 고정한 입력 생성 |
| `SeoulDongSourceAdapter` | 원본 JSON/ZIP CSV를 원천 필드명으로 읽기 |
| `DetailRowValidator` | H10 대응·분기 시점·숫자·결측·정의 상태 판정 |
| `RegionalDetailImporter` | 청크 쓰기·중복 충돌·행 회계·실패 보존 |
| `DetailBatchService` | 내용 기반 재사용·시도·복구·승인·revision 게시 |

### 확장 절차

1. 새 `RegionalSourceAdapter`로 원천 포맷을 읽는다. 기존 서울 어댑터를 조건문으로 계속 늘리지 않는다.
2. 해당 원천의 지역/업종/기간 규칙과 검증된 대응 근거를 정의한다. 현재 구현은 서울8·CS 코드·분기만 수용한다. 다른 코드나 월/연 자료는 별도 검증 정책을 추가한 후 허용한다.
3. 원천별 metric 단위·집계·정의 상태를 새 명세에 넣고, 새 원본/정의/대응 버전으로 별도 배치를 만든다.
4. 배치·청크 적재·이력·품질·멱등성·게시 서비스는 재사용한다. 기존 원천 행이나 전국 게시 포인터를 변경하지 않는다.

미확정 후속 사항: 서울 CS→공통 업종의 의미 대응, 매출·길단위인구·비율의 업무 해석, 비교 가능한 기간/경계(T21), 공개 API·AI 계약. 원천 저장 완료만으로 이 항목들을 완료 처리하지 않는다.
