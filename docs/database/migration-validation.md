# Flyway 마이그레이션 검증

> 검증일: 2026-10-26 · 대상: T06 기준정보 테이블 생성  
> 이 문서는 V1 단계의 검증 기록이다. 후속 적재까지 포함한 최신 결과는 [T07 문서](../data/reference-import.md#6-검증-결과)를 참고한다.

[문서 목록](../README.md) · [DB 설계](reference-schema-design.md) · [수동 적재](../data/reference-import.md)

## 1. 결과 요약

| 항목 | 결과 |
|---|---|
| 실행 명령 | `gradlew.bat build --no-daemon` |
| V1 단계 결과 | **23건 성공 / 실패·오류·건너뜀 0건** |
| 구성 | 서버·연결·health 3건 + 마이그레이션 20건 |
| 당시 최종 실행 시간 | 41초 |
| 개발 DB | 변경하지 않음 |
| 후속 T07 포함 최신 결과 | **34건 성공**, 별도 문서에 기록 |

## 2. 구현 범위와 환경

### 구현 범위

- Flyway starter·MySQL 모듈 추가. 버전은 기존 Spring Boot 4.1.1이 관리한다.
- [V1 SQL](../../src/main/resources/db/migration/V1__create_reference_tables.sql)로 자치구·행정동·업종 테이블을 생성한다.
- Hibernate DDL 생성과 기본 SQL 초기화 대신 Flyway로만 스키마를 관리한다.
- 이 단계에는 Entity·Repository·서비스 API·실제 공공데이터 적재를 포함하지 않았다. 초기 적재는 이후 T07에서 구현했다.

### 검증 환경

| 구성 | 값 |
|---|---|
| JDK | Eclipse Temurin 25.0.4.1 |
| Gradle | 저장소 Wrapper 9.7.1 |
| MySQL | Testcontainers `mysql:8.4.11` |
| 테스트 DB | `pickplace_test`, 동적 포트 |
| Flyway | 관리된 버전 12.4.0 |

개발용 Compose DB·기존 데이터 볼륨은 사용하지 않았다. 가상 테스트 행은 트랜잭션 롤백으로 제거하고 임시 DB는 테스트 종료 후 정리한다.

## 3. 검증 항목

| 구분 | 확인 내용 |
|---|---|
| 최초 적용 | 빈 DB에 업무 테이블 3개와 Flyway 적용 이력 생성 |
| 재실행 | 2회 재실행 모두 신규 적용 0개, 기존 값·이력 유지 |
| 코드 | 자치구·행정동·업종 코드 형식, 소문자·후행 공백 거부 |
| 참조 무결성 | 없는 부모 참조, 자식이 있는 부모 삭제·키 변경 거부 |
| 공식 코드 | CURRENT·LEGACY·UNVERIFIED 정상 저장, 상태·메타데이터·날짜 불일치 거부 |
| 지원 업종 | 기본 FALSE, 원천명·표시명 분리, 잘못된 지원값·음수 정렬순서 거부 |
| 필수값 | NOT NULL·공백 이름 검사 |
| 기존 동작 | 서버 기동, MySQL 연결·버전, health HTTP 200·UP |
| 안전 설정 | clean 비활성화, 자동 baseline 거부, migrate 시 검증 |
| 패키징 | 실행 JAR에 V1 SQL과 Flyway MySQL 모듈 포함 |

### 검증 중 확인한 예외 처리

MySQL CHECK 위반은 JDBC에서 `UncategorizedSQLException`과 오류 코드 `3819`로 전달됐다. 처음 예상한 `DataIntegrityViolationException`만으로 판단하지 않고 실제 MySQL PK/FK/NOT NULL/CHECK 오류 코드를 검사하도록 테스트를 수정했다.

단순히 예외가 발생하면 통과하는 검사는 사용하지 않는다.

## 4. 재실행 방법

저장소 루트에서 실행한다. JDK 25와 실행 중인 Docker Desktop이 필요하다.

```cmd
gradlew.bat build --no-daemon
```

마이그레이션 테스트만 실행하려면:

```cmd
gradlew.bat test --tests "*ReferenceSchemaMigrationTests"
```

현재 전체 빌드는 후속 T07 테스트도 포함한다. 위의 23건은 V1 단계에서 남긴 기록이며, 현재 전체 테스트 수와 동일하다는 뜻이 아니다.

IntelliJ의 Project SDK·Gradle JVM은 JDK 25로 지정한다. CMD에서 다른 Java가 잡히면 해당 세션의 `JAVA_HOME`을 설치된 JDK 25 경로로 설정한다. 비밀번호·개인별 절대 경로를 공유 설정에 넣지 않는다.

## 5. 근거 파일

| 자료 | 위치 |
|---|---|
| 실제 마이그레이션 | [V1 SQL](../../src/main/resources/db/migration/V1__create_reference_tables.sql) |
| 통합 테스트 | [ReferenceSchemaMigrationTests.java](../../src/test/java/com/pickplace/backend/ReferenceSchemaMigrationTests.java) |
| HTML 보고서 | `build/reports/tests/test/index.html` |
| XML 보고서 | `build/test-results/test/TEST-*.xml` |

자동 생성되는 `build/` 보고서는 Git에서 제외한다.

V1 SHA-256:

```text
B0819E615ADD4BCF8C1E97FABDFFAEF20FBD19F9BC68DA9A2BBFC5396BCADC00
```

이 단계에서 `git diff --check`를 통과했고 커밋·푸시는 하지 않았다.

## 6. DB 적용 시 주의사항

> 일반 로컬 서버를 실행하면 연결된 개발 DB에도 미적용 V1이 자동 적용된다.  
> 검증에서는 임시 DB만 사용했다.

| 설정 | 값 | 목적 |
|---|---|---|
| `clean-disabled` | `true` | Flyway 전체 초기화 방지 |
| `baseline-on-migrate` | `false` | 기존 비어 있지 않은 DB의 자동 승인 방지 |
| `validate-on-migrate` | `true` | 적용 이력·체크섬 검증 |

- 기존 테이블이 있는데 Flyway 이력이 없다면 스키마·데이터부터 확인한다.
- 자동 baseline, repair, 테이블·볼륨 삭제로 오류를 우회하지 않는다.
- 이미 적용된 V1은 수정하지 않는다. 변경은 V2 이상의 새 파일로 관리한다.
- 가상 행 기반 스키마 검증과 실제 기준정보 적재 검증은 별개다. 실제 원본 적재·재적재 검증은 [T07 문서](../data/reference-import.md)에 기록했다.
