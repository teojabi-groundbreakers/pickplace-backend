# T14 전국 통계 적재·집계·게시

T13 결과를 MySQL에 적재하고 승인된 현황 묶음을 게시한다. **수집·정제 결과 파일, DB 적재 완료, 승인, 공개 기본 자료 선택은 서로 다른 단계**다.

| 순서 | 작업 | 결과 |
| --- | --- | --- |
| 1 | T07 기준정보 적재·승인 | 지역 릴리스·소진공 분류 APPROVED |
| 2 | T12 수집 → T13 검증 | 원본·metadata·rows/issues/report 준비 |
| 3 | T14 import · 데이터셋별 실행 | 인구/업소 SUCCEEDED 배치 |
| 4 | prepare-summary → approve-summary | 같은 월·지역 릴리스의 승인 묶음 |
| 5 | publish-month | 내부 MONTH 포인터·이력 |
| 6 | set-default | 서비스가 읽을 DEFAULT 포인터·이력 |

이 문서의 명령은 운영자가 수동 실행하는 CLI다. 공개 현황 HTTP API를 추가하지 않는다.

## 대상 DB 설정

JDK 25와 MySQL 8.4를 사용한다. Flyway가 V1~V6를 순서대로 적용한다. 기존 V5는 수정하지 않았으며 V6는 첫 실행/첫 게시 동시성을 위한 `national_statistics_mutex` 고정 행 2개만 추가한다.

```cmd
set "STATISTICS_JDBC_URL=jdbc:mysql://localhost:3307/pickplace?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
set STATISTICS_DB_USER=pickplace_app
set STATISTICS_DB_PASSWORD=본인의_DB_비밀번호
gradlew.bat bootJar
```

위 비밀번호는 예시 자리표시자다. 실제 비밀번호를 파일·문서·커밋에 저장하지 않는다. `national-statistics` 프로필은 이 환경변수를 요구하며, `local` 등 다른 프로필과 혼합하지 않는다. Hikari 최대 연결 4개·대기 5초·JDBC 배치 재작성 옵션을 사용한다. 웹 서버는 실행하지 않고 명령 완료 시 컨텍스트를 닫는다.

<details>
<summary>입력 파일 확인·인구/업소 적재</summary>

아래 경로는 T12/T13 기본 위치 예시다. 실제 보관 경로가 다르면 해당 값을 바꾼다. 결과 파일을 편집해 오류를 숨기지 말고, 원천/정제 정책을 검토한 뒤 새 T13 결과를 만든다.

```cmd
set PICKPLACE_REFERENCE=data\reference\national-202609-sejong-hint-v1\snapshot.json
set PICKPLACE_POPULATION=data\raw\national\population_month\202609\23a269d2fd754f27bab32c9a26c711c3e49e65aee7169dc3184d8b5a548cd36d
set PICKPLACE_STORES=data\raw\national\store_snapshot\202609\c83b30e7a1d1144864a91d0e1818abf360f565972878d6e3c88d75ceafe2286a

java -Xmx512m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=import --confirm=true --input=data/validated/population-202609-v1 --raw="%PICKPLACE_POPULATION%\raw.csv" --metadata="%PICKPLACE_POPULATION%\metadata.json" --reference="%PICKPLACE_REFERENCE%"

java -Xmx512m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=import --confirm=true --input=data/validated/stores-202609-v1 --raw="%PICKPLACE_STORES%\raw.zip" --metadata="%PICKPLACE_STORES%\metadata.json" --reference="%PICKPLACE_REFERENCE%"
```

| 확인 | 기준 |
| --- | --- |
| T13 파일 | report/rows/issues 해시·실제 회계·규칙 버전 |
| 원본·메타데이터 | 카탈로그·원본 SHA·ZIP/CSV 구조·수집 기록 해시·원래 수집 시각 |
| 기준정보 파일 | 스냅샷 SHA·검토 조건·원천 버전·분류 원본 |
| 실제 DB | mappingVersion+snapshot SHA가 맞는 APPROVED 지역 릴리스, 연결된 APPROVED 분류 |
| 행 | 원본 위치·코드 대응·시점·업종 계층·판정과 영향 범위 |

처리 중 파일이 바뀌면 성공 처리하지 않는다. 원본 URL·ZIP/멤버 해시·CRC·인코딩·헤더·행 수는 artifact로 보존하며 원본 파일은 이동하거나 수정하지 않는다. DB의 artifact 경로는 원본 파일 보관 위치를 가리키므로 다른 개발자는 자신의 T12 경로를 사용한다.

</details>

## 배치·청크·재시도

| 상황 | 처리 |
| --- | --- |
| 같은 입력의 SUCCEEDED 배치 | REUSED. 사실·집계·게시 이력 추가 없음 |
| 같은 입력의 RUNNING/VALIDATING | 중복 실행 거부. 임의 시간 초과 처리 없음 |
| 청크 실패 | 현재 청크 롤백, 배치 FAILED. 앞서 커밋한 청크는 실패 이력으로 보존 |
| FAILED 입력 재실행 | 새 batch_id·attempt_no. 이전 사실/이슈를 덮어쓰지 않음 |
| 작업자 종료로 RUNNING 잔류 | 운영자가 실행 종료 확인 후 recover-batch |

지문에는 원본·정제 행 해시, 파서/쓰기/정책 버전, 승인 지역/분류 ID·스냅샷 SHA·원천 버전을 포함한다. 파일명이나 “가장 최근 배치”로 동일성을 정하지 않는다. collectedAt은 원본 취득 시각이며 시작/완료/게시 시각과 구분한다.

한 번에 500행의 사실·이슈만 트랜잭션에 넣는다. 배치 행을 잠그고 RUNNING인지 확인하므로 복구 명령으로 FAILED가 된 작업은 다음 청크에서 중단된다. 모든 사실 처리 후 VALIDATING으로 전환하고 수록 지역·집계를 나눠 생성한다. 마지막 회계가 맞을 때만 SUCCEEDED다.

<details>
<summary>멈춘 배치 복구 명령</summary>

먼저 로그와 배치를 확인하고 실행 중인 작업을 종료한 뒤 사용한다. 아래 ID는 예시이며 실제 값을 조회해 바꾼다.

```sql
SELECT batch_id, dataset_code, status, attempt_no, started_at, finished_at, failure_code
FROM national_import_batch ORDER BY batch_id;
```

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=recover-batch --confirm=true --batch-id=실제ID --expected-status=RUNNING --actor=담당자 --reason="종료된 작업 확인 후 재시도"
```

현재 상태가 예상과 다르거나 SUCCEEDED면 복구를 거부한다. 복구는 상태를 FAILED로 기록할 뿐 부분 데이터를 삭제하지 않는다. 이후 원래 import 명령으로 새 시도를 만든다. DB 연결 자체가 끊겨 FAILED 기록도 실패하면 원래 오류와 함께 기록 실패를 남기며, DB 복구 후 이 명령으로 처리한다.

</details>

## 품질과 집계

| 원천 행 | 사실·이슈·집계 |
| --- | --- |
| 정상 행 | 사실 저장, 내부 채택 수 증가 |
| 같은 내용의 복사본 | 사실 중복 INSERT 안 함, 중복 회계 유지 |
| 동일 ID 내용 충돌 | 해당 키 전체를 quality issue로 격리. 마지막 행으로 덮어쓰지 않음 |
| 저장할 수 없는 ID/원천 지역 | 원본 위치·원문·사유를 quality issue로 보존 |
| 좌표/법정동/부가값 경고 | 경고 보존, 업소 수는 유지 |
| REGION_INDUSTRY | 해당 지역×업종 HOLD |
| REGION | 해당 지역의 소분류 전체 HOLD |
| GLOBAL | 배치 적재 기록은 보존하되 새 현황 묶음 준비/승인 거부 |

수록 지역은 실제 관측한 원천 지역만 저장한다. 모든 소분류의 내부 집계를 만들되 **유효한 수록 범위에서 해당 업종만 없는 경우에만 0을 생성**한다. coverage 부재는 0이 아니다. 경계 보류 지역은 관측 수 또는 영향이 있는 업종만 남긴다.

내부 채택97·집계영향3이면 `accepted_store_count=97`, 품질HOLD·영향3을 보존한다. 공개 조회는 null+사유로 변환해야 한다. 정상 인구나 다른 업종까지 일괄 제거하지 않는다. 수치AVAILABLE이어도 시점PENDING인 인구 행은 정상 공개할 수 없다.

## 현황 묶음 승인·월별 게시·기본 선택

묶음은 같은 월·같은 승인 지역 릴리스의 인구/업소 SUCCEEDED 배치를 고정한다. 업종 분류는 해당 업소 배치와 연결된 승인 분류여야 한다. GLOBAL·사실/집계/수록 범위 회계 오류를 검사한다. READY와 APPROVED는 자동 공개되지 않는다.

<details>
<summary>첫 게시 명령 · 실제 ID를 확인해서 실행</summary>

아래 숫자 자리표시자를 실제 import 결과 batchId로 바꾼다. summaryId는 prepare 결과다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=prepare-summary --confirm=true --version=national-summary-202609-v1 --population-batch-id=인구ID --store-batch-id=업소ID

java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=approve-summary --confirm=true --version=national-summary-202609-v1 --actor=담당자

java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=publish-month --confirm=true --version=national-summary-202609-v1 --expected-month-revision=0 --actor=담당자 --reason="첫 월별 게시"

java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=set-default --confirm=true --version=national-summary-202609-v1 --expected-default-revision=0 --actor=담당자 --reason="기본 자료 명시 선택"

java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=status
```

revision=0은 포인터가 없는 첫 게시에만 사용하며 기존 묶음 ID를 지정하지 않는다. 기존 포인터를 바꿀 때는 `status`에서 확인한 revision과 summary_release_id를 모두 지정한다. 같은 버전을 다른 배치로 재사용할 수 없으며 같은 포인터를 다시 선택해도 이력·revision을 불필요하게 늘리지 않는다.

</details>

<details>
<summary>월 정정·DEFAULT 동기화·복구</summary>

| 작업 | 옵션·결과 |
| --- | --- |
| 월 자료 정정 | publish-month, 기존 month revision+summary-id. DEFAULT 유지 |
| 기본 자료에도 정정 반영 | 위 명령에 sync-default + 기존 default revision+summary-id |
| 과거 자료 복구 | rollback-month. 같은 월에 게시 이력이 있는 승인 묶음만 |
| 기본 자료 별도 선택 | set-default. 월별 게시 이력이 있는 승인 묶음만 |

예: 9월 v1을 v2로 바꾸며 현재 DEFAULT도 v1을 가리킬 때 동기화한다. 아래 ID/revision은 실제 조회 값으로 바꾼다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-statistics --action=publish-month --confirm=true --version=national-summary-202609-v2 --expected-month-revision=1 --expected-month-summary-id=기존묶음ID --sync-default --expected-default-revision=1 --expected-default-summary-id=기존묶음ID --actor=담당자 --reason="검토한 정정을 기본 자료에도 반영"
```

같은 월이어도 DEFAULT가 교체할 기존 묶음을 가리키지 않으면 동기화를 거부한다. DEFAULT가 이미 10월 자료라면 9월 정정은 월 포인터만 바꾸거나 별도의 명시적 기본 변경을 사용한다. `rollback-month`도 기본값을 자동 변경하지 않는다.

모든 게시 작업은 고정 mutex 행 → 승인 묶음 → 관련 포인터 순서로 잠근다. 첫 포인터 생성 경합도 직렬화한다. 예상 revision/묶음 불일치 또는 이력 INSERT 실패가 나면 관련 월·기본 포인터와 이력 변경을 모두 롤백한다. 완료된 이력을 덮어쓰거나 삭제하지 않는다.

</details>

## 검증·후속 범위

- 작은 실제 MySQL fixture로 97+3, 무영향 경고, 지역/전체 영향, 청크 실패·새 시도, 실행 중 복구, 정정/동기화/복구/게시 경합을 검사한다.
- 전체 원본 검수는 `gradlew.bat verifyNationalStatistics`로 별도 실행한다. T12/T13 로컬 원본이 필요하며 전용 MySQL 컨테이너를 생성·종료한다. 개발 DB는 사용하지 않는다. 검수용 MySQL의 InnoDB 버퍼 풀은 4 GiB, Java 최대 힙은 512 MiB이므로 Docker에 충분한 메모리를 배정한다.
- 이 실데이터 검수 도구는 저장소의 상위 폴더에 보관한 `data-validation/2026-11-09/t12-national/final/adopted/national` 원본과 `data-validation/2026-11-09/t13-national/final-v2` 정제 결과를 사용한다. 원본을 Git에 포함하지 않으므로 해당 검수 파일이 없는 팀원은 일반 `test`의 격리 MySQL fixture를 사용하고, 직접 받은 파일은 위 CLI에 자신의 경로로 지정한다. 증거 출력 경로는 `-PstatisticsEvidenceFile=절대경로`로 바꿀 수 있다.
- 실제 검증 수치·환경·범위는 `national-statistics-import-validation.json`에 기록한다. 단일 SQL EXPLAIN/조회 시간은 공개 API 지연이나 부하 시험 결과가 아니다.
- 현재 선택 기준과 DEFAULT 묶음을 같은 읽기 시점에 고정하고 분류 불일치를 처리하는 공개 조회는 후속 T15/API 작업이다. 이번 CLI 구현으로 미완성 HTTP API를 호출하지 않는다.

관련 문서: [T13 정제·검증](national-data-validation.md), [V5 통계 테이블](../database/national-statistics-schema.md), [FE 현재 지원 API](../api/frontend-api-guide.md).
