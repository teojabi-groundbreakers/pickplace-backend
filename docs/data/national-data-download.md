# T12 · 전국 인구·업소 원본 수집

구현일: 2026-11-09 · 브랜치 `feature/national-data-download`.

전국 원본을 **DB·웹 서버·Flyway 없이** 다운로드·검증·보관하는 수동 CLI다. 기본 실행은 계획만 출력한다. 정기 스케줄러·숫자 정제·DB 적재는 포함하지 않는다.

## 지원 범위

| 데이터셋 | 공식 원천 | 검증 시점 | 형식·인코딩 | 원본 규모 |
| --- | --- | --- | --- | --- |
| POPULATION_MONTH | 행정안전부 / 15097972 | 202609 / 2026-09-30 월말 | CSV / MS949(CP949) | 2,568,111 bytes / 230컬럼 / 3,619행 |
| STORE_SNAPSHOT | 소상공인시장진흥공단 / 15083033 | 202609 / 2026-09-30 배포본 | ZIP 내부 CSV / UTF-8 | 349,070,686 bytes / 16CSV / 39컬럼 / 2,753,778행 |

공식 자료: [주민등록 인구](https://www.data.go.kr/data/15097972/fileData.do), [상가(상권)정보](https://www.data.go.kr/data/15083033/fileData.do). URL·해시·크기·헤더·멤버 목록은 [전국 수집 카탈로그](../../src/main/resources/data-download/national-source-catalog.json)에 고정했다. 확인하지 않은 월이나 최신 파일로 자동 치환하지 않는다. 새 배포본은 검증 후 별도 항목으로 추가한다.

## 실행 — Windows CMD

프로젝트 루트와 JDK 25를 사용한다. 검증한 공식 다운로드에는 API 키·로그인·Docker·MySQL이 필요하지 않다. 다른 Spring 프로필과 혼합하지 않는다.

```cmd
gradlew.bat bootJar
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=POPULATION_MONTH --month=202609 --plan-only
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=STORE_SNAPSHOT --month=202609 --plan-only
```

계획은 네트워크 요청·파일 생성 없이 끝난다. 실제 수집은 다음 명령으로 실행한다.

```cmd
java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=POPULATION_MONTH --month=202609 --download --output=data/raw
java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=STORE_SNAPSHOT --month=202609 --download --output=data/raw
```

보유한 검증 원본은 아래 경로를 본인 보관 경로로 바꿔 재사용한다. 새 다운로드로 위장하지 않고 원래 수집 시각과 메타데이터를 보존한다.

```cmd
java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=POPULATION_MONTH --month=202609 --source-file=C:\pickplace-data\population-202609-original.bin --source-metadata=C:\pickplace-data\population-202609-original.meta.json --output=data/raw
java -Xmx256m -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=national-data-download --dataset=STORE_SNAPSHOT --month=202609 --source-file=C:\pickplace-data\stores-original.bin --source-metadata=C:\pickplace-data\stores-original.meta.json --output=data/raw
```

재사용 메타데이터는 `url`, `status=200`, `bytes`, `sha256`, `downloadedAt`(UTC offset 포함 ISO 시각)을 요구한다. URL·크기·해시는 카탈로그와 같아야 한다. 원본 파일은 수정하지 않는다.

| 옵션 | 의미 |
| --- | --- |
| --dataset | POPULATION_MONTH / STORE_SNAPSHOT |
| --month | 검증된 YYYYMM. 현재 202609 |
| --output | 보관 기준 폴더. 기본 data/raw |
| --plan-only | 계획만 출력. 다운로드/재사용 옵션이 없으면 생략해도 같은 동작 |
| --download | 실제 다운로드. 완성된 동일 묶음이 있으면 검증 후 REUSED |
| --source-file / --source-metadata | 기존 원본과 당시 수집 기록. 두 옵션 모두 필요 |

계획·다운로드·원본 재사용 모드를 혼합하지 않는다. 중복 값·미정의 옵션·서울 분기 옵션은 거절한다. 종료 코드: 0 성공 / 2 입력 오류 / 3 전송·검증·저장 실패.

## 보관과 T13 인계

```text
data/raw/national/<dataset 소문자>/202609/<sha256>/
  raw.csv 또는 raw.zip
  metadata.json
  upstream-metadata.json  # 기존 원본 재사용 때만
```

원본을 압축 해제하거나 다시 CSV로 쓰지 않는다. 임시 .pending-* 폴더에서 검증·저장을 마치고 폴더를 원자적으로 확정한다. 기존 완성본의 변조·누락은 자동 덮어쓰기 없이 실패한다. 동시에 최초 수집하면 한쪽 확정이 실패할 수 있으며 먼저 저장된 묶음을 재확인한다.

재실행은 원본 해시·전체 구조·수집 기록을 검증하고 REUSED로 반환한다. 기존 원본·수집 시각·파일 수정 시각을 바꾸지 않는다. 원본 재사용은 ADOPTED, 새 HTTP 수집은 DOWNLOADED다.

| metadata | T13에서 사용할 의미 |
| --- | --- |
| schemaVersion | 1, 이번 전국 수집 형식 |
| dataset/sourceSystem/sourceId | 데이터셋·원천 namespace |
| periodType/periodKey/sourceReferenceOn/sourceLabel | MONTH와 SNAPSHOT을 구분한 원천 시점 |
| sourcePage/requestedUrl/finalUrl/httpStatus | 출처·요청/최종 URL·HTTP 결과 |
| collectedAt/storedAt/acquisition | 원래 수집 시각·새 보관 시각·수집 방식 |
| rawFile/format/encoding/bytes/sha256 | 폴더 내 원본 위치·형식·해시 |
| rowNumbering | DATA_RECORD_1_BASED_HEADER_EXCLUDED. 헤더 제외 첫 CSV 데이터 레코드가 1. 인용부호 내부 줄바꿈은 별도 행이 아님 |
| validation.rows/members | 총행수와 CSV별 이름·비압축 크기·SHA·CRC·행수·컬럼 순서 |
| validation.ignoredMembers | CSV가 아닌 ZIP 안내 파일. 데이터 행수에 합산하지 않음 |
| upstreamMetadataSha256 | 보존한 원래 메타데이터의 해시 |

T13은 결과의 rawFile과 metadataFile을 입력으로 받는다. ZIP의 각 멤버와 데이터 레코드 번호로 원문을 재현한다. 파일명 확장자만으로 형식·시점을 판단하지 않는다.

## 검증 범위와 한계

- 연결 10초, 본문 완료까지 최대10분/시도, 최대3회. 408/429/일부5xx·전송 오류만 제한적으로 재시도한다.
- 원본은 파일로 스트리밍한다. 카탈로그 크기·SHA를 확인하며 ZIP 내부 CSV 비압축 총량은4GiB 이내다.
- CSV 인코딩·헤더 순서·중복 헤더·행폭·인용부호·빈 파일·총행수와 인구의 모든 기준일을 검사한다.
- ZIP CSV 멤버의 정확한 구성·크기·SHA·CRC를 검사하고 경로 이탈·중복 이름을 거절한다. 외부에 추출하지 않는다.
- 404·변경된 파일·해시 불일치를 다른 원천/최신 월로 우회하지 않는다.
- 숫자·성별/연령 합계·지역 대응·고유ID 충돌·집계 영향·경계 품질은 T13이다. 수집 성공을 공개 승인·DB 적재 완료로 보지 않는다.

[Apache Commons CSV 1.14.1](https://commons.apache.org/proper/commons-csv/changes.html)로 인용부호 내부 쉼표·줄바꿈을 레코드 단위로 읽는다. 전체 CSV를 리스트로 올리지 않는다.

[실제 원본·전체 회귀 검증 기록](national-data-download-validation.json)을 참고한다. 기존 서울 data-download는 이전 범위 재현용이며 전국 서비스의 기본 수집 경로는 이 문서다. 원본349MB는 Git에 포함하지 않는다. 다른 개발자는 이 CLI로 같은 검증 배포본을 다운로드하거나 보유 파일을 재사용하면 된다.
