# 전국 기준정보 수동 적재 — T07

구현일: 2026-11-09 · 브랜치 `feature/nationwide-reference-import`

전국 인구·업소 통계를 연결할 지역·원천 대응·업종 분류를 저장한다. 인구 수치나 개별 업소275만행을 서비스 통계 테이블에 적재하는 작업은 T13/T14다. [T06 저장 구조](../database/nationwide-reference-schema.md)를 먼저 확인한다.

## 실행 흐름

| 단계 | 입력·처리 | 결과 |
|---|---|---|
| 원본 정리 | 공식 현행/말소 포함 H ZIP·Layout, 9월 인구 CSV·상가 ZIP와 다운로드 metadata | 원본 SHA256·크기 대조 |
| 묶음 생성 | Python에서 XLSX·CSV를 읽고 원문/계층 후보/원천 지역/대응 후보/업종 계층 추출 | 체크섬이 있는 검토용 JSON |
| DB 적재 | Java에서 코드·날짜·부모·중복·출처·계층/대응 관계 검증 후 JDBC 배치 INSERT | 원자적 READY 릴리스, 자동 승인/게시 없음 |
| 검토·승인 | 근거가 있는 새 검토본을 적재하고 명시적 approve 실행 | 승인 메타데이터·검토본의 지원 업종 반영 |
| 게시·복구 | 기대 revision과 승인 버전을 지정 | 포인터와 이벤트의 원자 변경 |

원본 계층·대응을 이름이나 접두어로 자동 승인하지 않는다. 생성기는 모든 계층/대응과 이용조건 검토를 PENDING으로 기록하며 선택/지원 플래그를 FALSE로 둔다. 실제 원본 검증 성공은 공개 승인과 다르다.

## 1. 원본에서 검토 묶음 만들기

Python 3.11 이상과 `scripts/requirements-national-reference.txt`의 openpyxl이 필요하다. 실행 예시는 Windows CMD 기준이다. `C:\pickplace-data`는 자신의 원본 보관 경로로 바꾼다.

```cmd
python -m pip install -r scripts\requirements-national-reference.txt
python scripts\build_national_reference.py --regions C:\pickplace-data\nationwide-regions --statistics C:\pickplace-data\nationwide-statistics --version national-202609-candidate-v1 --output C:\pickplace-data\national-reference\snapshot.json
```

현재 생성기는 **검증한2026년9월 배포본 전용**이다. 다음 배포본은 파일명·기준일·원천 변경을 검수한 뒤 확장한다. 명령 이름만 바꿔 임의의 최신 자료로 간주하지 않는다.

| 폴더 | 필수 파일 |
|---|---|
| regions | `jscode20261026.zip`, `jscode20261026-history.zip`, `layout.hwpx`, 각 파일의 `원래파일명.meta.json` |
| statistics | `population-202609-original.bin`, `stores-original.bin`, 각각 `population-202609-original.meta.json`, `stores-original.meta.json` |

metadata는 기존 다운로드 결과의 URL·시각·bytes·sha256을 사용한다. 파일명이 버전/공식 코드 체계의 증거를 대신하지 않는다. 생성기는 원본 체크섬·크기, 현행/이력 일치, 원천 지역/업종 이름·부모 충돌과 CSV 행폭을 검사한다. 상가는 ZIP member를 스트리밍하며 업소 전체를 메모리에 보관하지 않는다. 기존의 인구 수치 합계·업소 ID/좌표 전수 검증을 대신하는 스크립트는 아니다.

출력은 `snapshot.json`과 `snapshot.report.json`이다. 출력 파일이 이미 있으면 덮어쓰지 않는다. 보고서의 `snapshotSha256`을 Java 적재 명령에 전달한다. 원본과 생성 묶음은 저장소 밖에 보관하며 자격증명은 포함하지 않는다.

## 2. DB에 READY 상태로 적재

로컬 DataSource 설정의 `MYSQL_PASSWORD`를 실행 환경에 설정한다. DB 주소·사용자가 의도한 대상인지 먼저 확인한다. 아래 실행은 Flyway V1~V4를 적용할 수 있으며 스키마 DDL과 적재 트랜잭션은 별개다.

```cmd
gradlew.bat bootJar
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=import --snapshot=C:\pickplace-data\national-reference\snapshot.json --sha256=보고서의_SHA256
```

웹 서버는 실행하지 않고 명령이 끝나면 종료한다. `national-reference` 프로필에서만 동작한다. `--confirm=true`와 각 필수 인자는 한 번씩 명시해야 한다. DB 비밀번호를 명령 인자로 쓰지 않는다.

| 재실행 상황 | 처리 |
|---|---|
| 같은 버전·같은 묶음 SHA | 기존 release/classification ID 반환, 행/지원 정책/게시 유지 |
| 같은 버전·다른 묶음 SHA | 거부. 새 버전으로 검토 후 적재 |
| 새 릴리스·동일 원천/분류본 | 원천 메타데이터·분류 내용이 같으면 재사용 |
| 동일 원천/분류 버전의 내용 충돌 | 해당 새 적재 전체 롤백. 기존 승인/게시 데이터 유지 |
| 적재 중 SQL 실패 | 새 원본·노드·대응·분류 쓰기 전체 롤백. 오류 로그로 원인 확인 |

단일 기준정보 묶음은 현재 약9MB다. 로더는32MiB 이하만 받는다. JDBC300행씩 배치 실행하되 기준정보 적재 전체는 한 트랜잭션이다. 대규모 업소 사실 적재와 다른 선택이며 통계275만행을 이 트랜잭션에 넣지 않는다.

## 3. 검토·승인·게시

검토자는 이용조건·공식 계층/역할·원천의 유일 대응·원문 날짜·표시·지원 업종을 확인한다. 검토 결과의 근거를 각 행에 기록한 **새 버전 묶음**을 적재한다. 기존 승인본을 UPDATE하는 명령은 제공하지 않는다.

- 계층은 검토된 VERIFIED 상태와 evidence를 기록하고, 선택할 노드는 검토된 부모 경로를 포함해야 한다.
- 공개 제외 노드도 검토 완료 근거를 기록하되 selectable=FALSE·적절한 role을 유지한다.
- 원천 대응은 원천 체계/코드/버전별 유일한 VERIFIED 후보와 날짜 품질이 정상인 공식 대상을 요구한다.
- `MOIS_RESIDENT`의 VERIFIED 대상은 같은 H10이다. 상가8의 후보를 문자열 변환으로 승인하지 않는다.
- 구지면/고덕동의 과거 귀속은 보존한다. 통계 기준일의 호환성·경계 보류는 T13/T14에서 추가 검증해야 하며 T07 승인만으로 통계 공개가 승인되지는 않는다.
- 실제 후보3업종의 지원을 켜려면 소분류별 명칭·범위를 검토한 새 분류 버전을 사용한다. 이전 CS 지원 플래그를 복사하지 않는다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=approve --version=검토한_새_버전 --actor=검토자
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=publish --version=승인된_버전 --expected-revision=0 --actor=게시자 --reason=초기게시
```

미검토 묶음은 승인 명령에서 거부한다. 승인만으로 게시하지 않는다. 기대 revision이 다르면 포인터/event를 바꾸지 않고 재확인을 요구한다. 동일 버전을 같은 현재 revision으로 다시 게시하면 불필요한 이벤트를 만들지 않는다.

복구는 `--action=rollback`과 이전에 게시된 승인 버전·현재 revision·사유를 지정한다. revision을 되감거나 승인본 내용을 수정하지 않고 새 이벤트를 만든다. 게시 행을 잠그고 포인터/event를 같은 트랜잭션에서 처리한다. 동시 최초 게시의 중복 키/잠금 충돌은 실패한 실행을 재검토한 뒤 다시 시도한다.

## 검증과 후속 범위

[T07 테스트](../../src/test/java/com/pickplace/backend/NationalReferenceImportTests.java)는 멱등성·소스/분류 충돌 롤백·미승인 공개 거부·선택/계층 검증·승인/게시/복구·동시 적재/게시·이벤트 실패 시 포인터 롤백을 검사한다.

실제202609 검토 묶음 검사는 `PICKPLACE_REFERENCE_SNAPSHOT`에 JSON 절대 경로, `PICKPLACE_REFERENCE_SHA256`에 검증한 해시를 환경변수로 제공하면 추가 실행한다. 값이 없으면 소형 fixture로 기본 검사를 수행한다. 실제 파일·계정은 테스트 fixture에 넣지 않는다.

```cmd
gradlew.bat clean check bootJar
```

최종 건수·실제 DB 적용/적재 상태는 [실행 검증 기록](national-reference-validation.json)을 확인한다. T07의 지역 기준정보 게시와 T11의 인구·업소 현황 묶음 게시는 별개다. 전국 목록 API·수치 적재·현황 조회·FE 연동은 후속 구현이다.

2026-11-09 실행 결과: 전체229건 통과·실패/오류/건너뜀0. 실제 원본 검토 묶음의 격리 MySQL 적재를 포함한다. 이후 기존 개발 `pickplace` DB에 V1~V4를 적용하고 release/classification ID1에 READY 기준정보를 적재했다. 공식 노드9,613·계층 후보3,926·인구 원천3,619·상가 원천3,558·대응 후보7,177·업종332개이며 재실행은 inserted=false였다. 선택/지원/게시 건수는 모두0이다. 실제 공개 승인과 통계 수치 적재는 완료하지 않았다.
