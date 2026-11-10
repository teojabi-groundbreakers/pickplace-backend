# 기준정보 수동 적재

> T07 · 구현·검증일: 2026-10-26  
> 임시 MySQL에서 검증 완료. 개발 DB에는 실제 적재하지 않았다.

[문서 목록](../README.md) · [DB 설계](../database/reference-schema-design.md) · [V1 검증](../database/migration-validation.md)

## 1. 실행 전 확인

**아래 적재 명령은 연결된 DB에 실제 데이터를 저장한다.** 대상 DB 주소·사용자·스키마를 먼저 확인하고, 필요하면 기존 데이터를 백업한다.

| 준비 항목 | 확인 내용 |
|---|---|
| Java | JDK 25 |
| MySQL | Docker Compose MySQL 실행 |
| 연결 설정 | `local` 프로필의 DB 주소·스키마 확인 |
| 비밀번호 | `MYSQL_PASSWORD` 환경변수 설정 |
| 적재 대상 | 기존 테이블과 원천 버전의 충돌 여부 확인 |

`.env`는 Docker Compose용이다. Spring Boot는 기본적으로 자동 로드하지 않으므로 IDE 실행 설정 또는 현재 CMD 세션에 환경변수를 지정한다. 비밀번호를 명령 인자·공유 실행 설정·Git에 기록하지 않는다.

## 2. 수동 적재 실행

### Gradle로 실행

저장소 루트의 CMD에서 실행한다.

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=local,reference-import --pickplace.reference-import.confirm=true"
```

### 빌드된 JAR로 실행

```cmd
java -jar build/libs/pickplace-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,reference-import --pickplace.reference-import.confirm=true
```

실행 순서:

1. 웹 서버 없이 Spring Boot 실행
2. 미적용 Flyway 마이그레이션 적용
3. 스냅샷 무결성·입력값 검사
4. 자치구 → 행정동 → 업종을 한 트랜잭션으로 적재
5. 성공 로그 출력 후 애플리케이션 컨텍스트 종료

### 실행 결과 읽기

| 실행 상황 | 신규 자치구 | 신규 행정동 | 신규 업종 |
|---|---:|---:|---:|
| 최초 전체 적재 | 25 | 425 | 100 |
| 같은 원본 재실행 | 0 | 0 | 0 |

완료 로그의 `insertedDistricts`·`insertedDongs`·`insertedBusinessTypes`를 확인한다. 실패 시 성공 로그를 출력하지 않고 종료한다.

> 일반 `local` 서버 실행은 기준정보 행을 자동 적재하지 않는다.  
> 단, 미적용 Flyway 테이블 생성은 실행된다.

`reference-import` 프로필과 `confirm=true`를 함께 지정해야 적재한다. 확인 옵션이 없으면 거부하지만 **Flyway 초기화는 이 거부 전에 실행될 수 있다.** 확인 옵션은 DDL 차단 장치가 아니다.

`local` 프로필 없이 실행할 경우 DB 연결 설정을 별도로 제공해야 한다.

## 3. 적재 데이터와 출처

### 고정 스냅샷 범위

| 구분 | 내용 |
|---|---|
| 자치구 | 원천 25개 |
| 행정동 | 원천 425개 — CURRENT 422 / LEGACY 3 |
| 업종 | 20262 점포 원천에서 확인한 전체 100개 |
| 초기 지원 | 커피·음료 `CS100010` / 분식 `CS100008` / 제과점 `CS100005` |
| 공식 코드표 기준일 | 2026-10-12 |
| 원천 수집 | 2026-10-15 |
| 공식 코드 대조 | 2026-10-16 |

기존에 검증한 원본을 고정한 자료다. 현재 시점의 모든 행정동 경계를 새로 수집한 것이 아니다. 현행 코드표에만 있는 5개 동으로 통계를 임의 배분하지 않으며 말소 코드도 삭제하지 않는다.

### 출처와 저장 파일

출처는 서울 열린데이터광장 / 서울신용보증재단 / 행정안전부다.

- [행정동 영역 — OA-22160](https://data.seoul.go.kr/dataList/OA-22160/S/1/datasetView.do)
- [자치구 영역 — OA-22161](https://data.seoul.go.kr/dataList/OA-22161/S/1/datasetView.do)
- [점포 — OA-22172](https://data.seoul.go.kr/dataList/OA-22172/S/1/datasetView.do)
- [행정안전부 코드표](https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=130109)

| 파일 | 용도 |
|---|---|
| [적재 스냅샷](../../src/main/resources/reference-data/seoul-reference.json) | 적재기의 실제 입력 |
| [원천 manifest](reference-source-manifest.json) | 원본 URL·조회 조건·해시·수집 시각 |
| [재현 스크립트](../../scripts/build-reference-snapshot.mjs) | 원본과 스냅샷·manifest 비교 |

스냅샷에는 개인정보·API 키가 없다. JSON과 manifest는 저장소에 포함되며, 대용량 원본 파일 전체가 포함된 것은 아니다.

스냅샷 SHA-256:

```text
da5e5e5aaccc7f6ca0e2706dc925a6076274491211060bd386388759e35ec796
```

## 4. 저장·재적재·충돌 정책

### 입력 검증

- SHA-256을 코드에 고정해 런타임 스냅샷 변조를 감지한다.
- `.gitattributes`로 JSON을 LF로 유지해 줄바꿈 변환에 따른 해시 불일치를 방지한다.
- 전체 건수·필수값·코드 형식·중복·부모 존재·공식 상태·날짜·초기 지원 3개 코드를 검사한다.

### 기존 데이터 처리

| 상황 | 처리 |
|---|---|
| 신규 코드 | 필수값·부모 관계 검증 후 INSERT |
| 원천 필드·버전이 같은 기존 행 | INSERT/UPDATE하지 않음 |
| 일부 행 누락 | 누락된 행만 보충 |
| 기존 업종 서비스 설정 변경 | 표시명·지원 여부·정렬순서 보존 |
| 원천명·부모·공식 매핑·수집 시각·버전 충돌 | 임의 덮어쓰기 없이 전체 중단 |
| 기존 DB에 스냅샷 밖 코드 존재 | 자동 삭제·병합하지 않고 중단 |

같은 원본을 재실행하면 `created_at`·`updated_at`·`collected_at`을 유지한다. 초기 3업종 설정은 신규 행에만 적용한다.

### 트랜잭션과 적용 한계

자치구 → 행정동 → 업종을 한 트랜잭션으로 적재한다. 기존 행은 테이블별 한 번 조회하고 신규 행은 100건 단위 JDBC batch로 저장한다. 중간 실패 시 이번 실행의 모든 INSERT가 롤백되며 기존 행은 유지된다.

JDBC 사용은 기준정보 배치 적재에 한정한다. 후속 조회 API의 Spring Data JPA 선택을 변경한 것은 아니다.

이 적재기는 **초기 고정 스냅샷용**이다. 다음 기능은 제공하지 않는다.

- 새 분동·폐지·원천 정정본의 자동 병합
- 다른 스냅샷 버전의 자동 이관
- 동시 수동 실행의 자동 재시도·병합

수동 명령은 동시에 중복 실행하지 않는다. DB PK가 중복 저장을 막더라도 동시 실행을 자동 조정하지는 않는다. 새 버전은 별도 이력 설계·검토 후 반영한다.

## 5. 테스트와 원본 재현

### Java·MySQL 테스트

```cmd
gradlew.bat build --no-daemon
```

별도의 Testcontainers MySQL 8.4.11과 `pickplace_test`만 사용한다. 개발용 Compose DB·데이터 볼륨은 사용하지 않으며, 테스트 행은 임시 DB 안에서 정리한다.

### 원본을 가진 작업자의 재현 검사

```cmd
node scripts/build-reference-snapshot.mjs ../data-validation --check
```

Node.js 내장 모듈만 사용하며 파일을 수정하지 않는다. 원본 해시를 검사하고 스냅샷·manifest를 재생성해 저장소 파일과 비교한다.

팀원은 이미 검증된 JSON과 manifest로 적재·Java 테스트를 실행할 수 있으므로 **대용량 원본·Node.js·외부 API 키가 필수는 아니다.** Node.js와 원본은 위 재현 검사에만 필요하다.

## 6. 검증 결과

### 최종 빌드

| 항목 | 결과 |
|---|---|
| 실행 명령 | `gradlew.bat build --no-daemon` |
| 결과 | **BUILD SUCCESSFUL** |
| 테스트 | **34건 성공 / 실패·오류·건너뜀 0건** |
| 구성 | 기존 23건 + 적재 9건 + 확인 옵션 1건 + 수동 프로필 1건 |
| 최종 실행 시간 | 54초 |

### 확인한 동작

- [x] 25개 자치구·425개 행정동·100개 업종 저장
- [x] CURRENT 422 / LEGACY 3 및 초기 지원 3개 확인
- [x] 전체 행정동의 부모·공식 코드·명칭 대응 확인
- [x] 같은 원본 2회 재실행 시 신규 0/0/0, 전체 값·시각·서비스 설정 유지
- [x] 누락 보충, 없는 부모·중복·공백 이름·잘못된 말소 상태 거부
- [x] 기존 지역·버전·원천명 충돌 중단, 앞선 INSERT 전체 롤백
- [x] 일반 서버 미적재, 확인 옵션 거부, 수동 프로필의 DB 트랜잭션 커밋
- [x] 최종 JAR에 JSON·수동 프로필·Runner 포함
- [x] 원본 104개와 파생 대조표의 해시·스냅샷 재생성 비교
- [x] `git diff --check` 통과

확인 옵션 단위 테스트는 JDK 동적 에이전트에 의존하지 않는다.

### 아직 하지 않은 작업

- [ ] 개발 DB 실제 적재 및 팀 검토
- [ ] Entity·Repository·조회 API 구현
- [ ] 커밋·푸시

T07은 **검토** 상태다. 임시 DB의 검증 성공과 개발 DB 실제 반영을 구분한다.
