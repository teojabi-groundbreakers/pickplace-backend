# T13 전국 인구·업소 정제·검증

전국 원본을 읽어 **정제 행·품질 이슈·검증 보고서**를 만든다. DB·웹 서버·Flyway·네트워크를 사용하지 않는다. 실제 통계 적재와 게시 처리는 T14에서 한다.

| 단계 | 입력 | 결과 |
| --- | --- | --- |
| T12 수집 | 공식 CSV/ZIP | 원본 + `metadata.json` |
| T13 검증 | T12 결과 + 해시가 고정된 기준정보 | `rows.jsonl` + `issues.jsonl` + `report.json` |
| T14 적재·게시 | T13 결과·승인된 DB 기준정보 | 사실/품질/집계 적재·승인·포인터 변경 |

## 실행 전 준비

1. JDK 25에서 [T12 수집](national-data-download.md)을 실행한다.
2. 저장소의 `data/reference/national-202609-sejong-hint-v1/snapshot.json`을 준비한다.
3. 결과를 저장할 **새 폴더**와 충분한 디스크 여유를 확보한다. 중복 검사용 임시 파일과 최종 JSONL을 함께 보관하므로 원본보다 많은 공간이 필요하다.

현재 카탈로그에 검증된 `202609`만 지원한다. 다른 월은 원본과 대응 기준정보를 검증한 뒤 별도로 추가한다.

<details>
<summary>Windows CMD 실행 명령</summary>

저장소 루트에서 실행한다. T12의 보관 위치가 다르면 `--raw`와 `--metadata`를 실제 경로로 바꾼다.

```cmd
gradlew.bat bootJar
set PICKPLACE_REFERENCE=data\reference\national-202609-sejong-hint-v1\snapshot.json
set PICKPLACE_REFERENCE_SHA256=0b5430750eed5ea951ee9161589e92e07df5fb5beea6c0cc36e651d5982bc1a9
set PICKPLACE_POPULATION=data\raw\national\population_month\202609\23a269d2fd754f27bab32c9a26c711c3e49e65aee7169dc3184d8b5a548cd36d
set PICKPLACE_STORES=data\raw\national\store_snapshot\202609\c83b30e7a1d1144864a91d0e1818abf360f565972878d6e3c88d75ceafe2286a

java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-validation --dataset=POPULATION_MONTH --month=202609 --raw="%PICKPLACE_POPULATION%\raw.csv" --metadata="%PICKPLACE_POPULATION%\metadata.json" --reference="%PICKPLACE_REFERENCE%" --reference-sha256=%PICKPLACE_REFERENCE_SHA256% --output=data/validated/population-202609-v1

java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-validation --dataset=STORE_SNAPSHOT --month=202609 --raw="%PICKPLACE_STORES%\raw.zip" --metadata="%PICKPLACE_STORES%\metadata.json" --reference="%PICKPLACE_REFERENCE%" --reference-sha256=%PICKPLACE_REFERENCE_SHA256% --output=data/validated/stores-202609-v1
```

모든 옵션은 값 하나가 필요하다. 다른 Spring 프로필·미정의 옵션·중복 옵션은 거부한다. 재실행할 때는 새 `--output` 경로를 지정한다. 기존 결과를 덮어쓰지 않는다.

| 종료 코드 | 의미 |
| --- | --- |
| 0 | 원본 전체 검사·행 회계·결과 파일 확정 완료. 개별 보류/거부가 없다는 뜻은 아님 |
| 2 | 잘못된 옵션·메타데이터·기준정보 입력 |
| 3 | 원본 무결성·파일 구조·읽기/쓰기·실행 실패 |

명령이 성공해도 DB 승인·적재·게시를 실행한 것은 아니다. `blocksNewPublication=true`는 다음 게시를 막아야 한다는 결과다.

</details>

## 검증 기준

| 대상 | 검사와 처리 |
| --- | --- |
| 출처 | T12 메타데이터의 데이터셋·기간·기준일·URL·형식·해시와 카탈로그 일치 |
| 원본 | 크기·SHA-256·ZIP/CSV 구조 재검증, 처리 완료 전 원본 해시 재확인 |
| 기준정보 | 스냅샷 SHA·기존 기준정보 검증기·원천 버전/출처·분류 원본 해시 확인. DB 승인 여부는 T14에서 별도 확인 |
| 인구 | 총/남/녀·남녀 각 111개 연령값의 비음수 안전 정수 검사. 총=남+녀, 성별 값=해당 연령 합계 |
| 인구 누락 | 빈값은 `NO_DATA`, `*`는 `SUPPRESSED`, 잘못된 숫자/합계는 `INVALID`. 숫자·연령은 null로 보존하며 0으로 채우지 않음 |
| 코드 | 인구 H10 동일 대응, 업소 원천 행정동8→H10. 법정동10을 행정동으로 대체하지 않음 |
| 업종 | 전체 소분류와 대/중/소 부모 관계 검증. 지원 3개 업종만 골라 원본을 버리지 않음 |
| 시점 | 승인된 코드 대응과 별도로 생성/말소일·변경일 근거 검사. 변경 당일은 보류, 유효기간 밖은 불일치 |
| 좌표·부가정보 | 누락/비정상 좌표는 좌표 쌍을 null 처리하고 경고. 층/호수 누락은 업소 수 제외 사유가 아님. 저장 길이를 넘는 부가값은 null+원문 경고로 보존 |

CSV의 쉼표·줄바꿈이 포함된 따옴표 필드를 해석한다. `rowNumber`는 **헤더를 제외한 논리 레코드의 1부터 시작하는 번호**이며 물리적인 줄 번호가 아니다.

<details>
<summary>중복 검사·행 회계</summary>

업소 ZIP의 모든 CSV를 함께 검사한다. 인구는 H10, 업소는 원본 업소 ID가 중복 키다. 키 대소문자는 구분한다.

| 경우 | 처리 |
| --- | --- |
| 같은 키·모든 CSV 값 동일 | 첫 행만 기존 판정을 유지, 나머지는 `DUPLICATE` |
| 같은 키·내용 충돌 | 첫 행을 포함한 해당 키의 모든 행을 `HOLD`, `DUPLICATE_ID_CONFLICT` 기록 |
| 키가 공백인 행 | 원본 위치별로 구분해 각각 검증·격리. 여러 누락 행을 하나로 합치지 않음 |

내용은 모든 CSV 필드의 값으로 비교한다. CSV 따옴표 표현 자체의 차이는 내용 충돌이 아니다. 충돌 행은 나중 행으로 덮어쓰지 않으며, 충돌 그룹의 동일 복사본도 전부 보류한다.

`원천행 = ACCEPTED + HOLD + REJECTED + DUPLICATE`가 맞아야 결과를 확정한다. 경고는 행 회계에 다시 더하지 않는다.

64개 임시 파티션으로 나눠 한 파일씩 검사한다. 파티션당 고유 키 150,000개 제한을 넘으면 실패한다. 전체 업소를 메모리 배열에 쌓지 않는다. 결과 순서는 파티션 순서이며 원본 정렬 순서를 보장하지 않는다.

</details>

## 결과 파일

| 파일 | 내용 |
| --- | --- |
| `rows.jsonl` | 모든 원천행의 판정·정제값·원본 위치·문제. 한 줄이 하나의 JSON 객체 |
| `issues.jsonl` | 문제가 있는 행의 원본 위치·키·사유·원문 값·집계 영향. 동일 복사본의 이슈는 중복 집계하지 않음 |
| `report.json` | 버전·원본/메타데이터/결과 해시·수집 시각·행 회계·실제 수록 지역·집계 영향·게시 차단 여부 |

`artifactNumbers`가 원본 파일/ZIP 멤버와 번호의 대응표다. 인구 CSV=1, 업소 ZIP=1, 각 CSV=2부터 카탈로그 순서다. `rawValidation.members`의 배열 순서로 번호를 추정하지 않는다. 원본은 T12 보관 위치에 그대로 두며 각 행에 원본 URL·39개 원문 필드를 반복 저장하지 않는다.

임시 폴더에서 모든 검사를 마친 뒤 결과 폴더를 한 번에 확정한다. 실패 시 이번 실행의 임시 파일만 정리하고 기존 결과·원본을 변경하지 않는다. 강제 종료로 남은 `.validation-pending-*` 폴더는 완성된 결과로 사용하지 않는다. 기본 결과 경로 `data/validated/`는 Git에서 제외된다.

## T14 인계 규칙

| T13 결과 | T14 처리 |
| --- | --- |
| `REGION_INDUSTRY` 영향 | 해당 H10×분류×업종 집계를 보류. 채택 97행·영향 보류 3행이면 내부97, 공개null+사유 |
| `REGION` 영향 | 그 지역의 전체 업종 집계 보류. 정상인 다른 지역/인구까지 일괄 제거하지 않음 |
| `GLOBAL` 영향 | 새 배치/묶음 게시 차단. 기존 정상 게시 유지 |
| `NONE` 경고 | 집계 수에는 영향 없음 |
| 원천 지역 부재 | 관측한 지역만 보고함. 행이 없다는 이유로 0을 생성하지 않음 |
| `DUPLICATE` | 사실 테이블에 다시 넣지 않고 중복 회계만 보존 |
| 충돌 키·저장 불가능한 원천 ID/지역 | 원문 위치와 quality issue로 격리. JSONL 모든 행을 사실 테이블에 단순 INSERT하지 않음 |

인구의 `population.rowStatus=AVAILABLE`이어도 `disposition=HOLD` 또는 시점이 비정상이면 공개할 수 없다. 집계 영향은 중복 사유 수가 아니라 영향받은 원천행 수로 계산한다.

T14는 입력 결과 해시를 검증하고, 스냅샷 버전/해시를 **실제 승인된 DB 지역 릴리스와 분류 ID**에 연결해야 한다. 보고서의 `VALIDATED`나 `blocksNewPublication=false`를 승인·게시 완료로 해석하지 않는다. 게시 포인터 변경, 공개 상태/사유 변환, 현재 선택 기준과의 같은 조회 스냅샷 검사는 후속 서비스의 책임이다.

## 실제 검증 결과

2026-11-09, 최대 Java 힙 256MiB로 원본 전체를 검사했다. 전체 회귀 테스트 350개(신규 40개), `check bootJar`를 통과했다.

| 데이터 | 원천행 | 채택 | 보류 | 거부·중복 |
| --- | ---: | ---: | ---: | ---: |
| 주민등록 인구 | 3,619 | 3,618 | 1 | 0 |
| 수록 업소 | 2,753,778 | 2,753,104 | 674 | 0 |

인구 1행은 구지읍 `2771026800`의 생성일과 기준일이 같은 경우다. 수치 합계는 정상이나 시점은 보수적으로 보류했다. 업소 674행은 구지면 `27710380`→`2771038000`의 말소일과 기준일이 같은 기존 경계 예외다. 둘 다 자동 치환·재배분하지 않았다. 고덕동은 9월 원본 기준으로 검사했으며 10월 동으로 배분하지 않았다.

보고서·출력 해시와 상세 검증 결과는 [T13 검증 기록](national-data-validation-result.json)에 정리했다. 이 결과는 통계 DB 적재·승인·게시 완료를 의미하지 않는다.
