# 처음 실행부터 DB 적재까지

> Windows CMD + IntelliJ IDEA 기준 · 작성일: 2026-10-26  
> 목표: MySQL 실행 → 서버 기동 → 테이블 생성 → 기준정보 적재 → SQL로 확인

[문서 목록](README.md) · [프로젝트 README](../README.md)

**이 문서는 실행 안내서다. 작성하면서 개발 DB에 적재한 것은 아니다.** 아래 적재 명령을 직접 실행하면 연결된 개발 DB에 실제 데이터가 저장된다.

현재 로컬 작업에는 미커밋 구현이 포함되어 있다. 새로 클론한 팀원은 Flyway·수동 적재 코드가 반영된 브랜치를 받아야 한다. 파일이 없다면 DB 설정 전에 코드 반영 상태부터 확인한다.

## 전체 흐름

| 단계 | 하는 일 | 완료 기준 |
|---|---|---|
| 1 | 프로젝트·실행 도구 준비 | JDK 25·Docker 사용 가능 |
| 2 | 비밀번호 설정 | Docker와 Spring의 환경변수 준비 |
| 3 | MySQL 실행 | DB 접속 가능 |
| 4 | Spring Boot 실행 | Flyway 테이블 생성, health 응답 UP |
| 5 | 테이블 확인 | 업무 테이블 3개·V1 성공 이력 |
| 6 | 자동 테스트 | 임시 DB 검증 성공 |
| 7 | 개발 DB에 수동 적재 | 신규 25개 구·425개 동·100개 업종 |
| 8 | SQL로 결과 확인 | 건수·지원 업종·공식 코드 상태 확인 |

**MySQL 실행, 테이블 생성, 데이터 적재는 별개다.** 이미 실행해본 사람은 현재 DB 상태를 확인하고 필요한 단계부터 진행한다. 처음 상태로 만들려고 데이터를 삭제할 필요는 없다.

## 1. 프로젝트와 실행 도구 준비

### 프로젝트 폴더로 이동

이미 프로젝트가 있다면 다시 클론하지 않는다. 현재 PC의 CMD 예시:

```cmd
cd /d "C:\Users\정주원\Desktop\대학\4-2\캡스톤\pickplace-backend"
```

다른 팀원은 자신의 프로젝트 경로로 바꾼다. `cd /d`는 다른 드라이브의 폴더로도 이동한다.

프로젝트가 없는 팀원만 원하는 작업 폴더에서 클론한다.

```cmd
git clone https://github.com/teojabi-groundbreakers/pickplace-backend.git
cd pickplace-backend
```

이후 명령은 모두 **`gradlew.bat`와 `compose.yaml`이 있는 저장소 루트**에서 실행한다.

### 도구 확인

| 도구 | 용도 |
|---|---|
| JDK 25 | Spring Boot·Gradle 실행 |
| IntelliJ IDEA | 코드 편집·서버 실행 |
| Docker Desktop | MySQL 컨테이너 실행 |
| Git | 프로젝트 코드 관리 |

MySQL·Gradle은 PC에 별도로 설치하지 않아도 된다. MySQL은 Docker, Gradle은 저장소의 Wrapper를 사용한다. 별도 Linux 서버를 구축하는 작업도 아니다. Docker Desktop은 이 MySQL 이미지에 맞는 Linux containers 엔진을 사용한다.

Docker Desktop을 실행한 뒤 확인한다.

```cmd
java -version
docker version
docker compose version
```

Java는 25여야 하며, Docker는 Client뿐 아니라 **Server 정보도 보여야 한다.**

### CMD에서 다른 Java가 선택될 때

현재 PC에 설치된 JDK 경로 예시다. 다른 PC에서는 실제 JDK 25 경로로 바꾼다.

```cmd
set "JAVA_HOME=C:\Users\정주원\.jdks\temurin-25.0.4.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
gradlew.bat --version
```

`set`은 현재 CMD 창의 환경변수를 설정한다. 위 명령은 Windows 전체 설정을 변경하지 않으며 새 CMD에서는 다시 설정해야 할 수 있다.

## 2. 비밀번호 준비

### Docker용 환경변수

처음 한 번만 실행한다. **기존 `.env`가 있으면 복사해서 덮어쓰지 않는다.**

```cmd
copy .env.example .env
notepad .env
```

파일의 두 항목에 서로 다른 비밀번호를 입력하고 저장한다.

```dotenv
MYSQL_PASSWORD=
MYSQL_ROOT_PASSWORD=
```

| 변수 | 용도 |
|---|---|
| `MYSQL_PASSWORD` | Spring Boot용 `pickplace_app` 계정 |
| `MYSQL_ROOT_PASSWORD` | 관리자 `root` 계정 |

빈칸을 그대로 두면 Compose가 실행되지 않는다. 실제 비밀번호는 문서·Git에 넣지 않는다.

### 같은 값을 두 곳에 설정하는 이유

| 실행 대상 | 비밀번호를 읽는 곳 |
|---|---|
| Docker Compose | 저장소 루트 `.env` |
| IntelliJ의 Spring Boot | IntelliJ 실행 설정의 환경변수 |
| CMD의 Gradle·Spring Boot | 해당 CMD 창의 환경변수 |

**Spring Boot는 기본적으로 `.env`를 자동으로 읽지 않는다.** IntelliJ·CMD의 `MYSQL_PASSWORD`는 `.env`와 같은 값이어야 한다.

이미 초기화된 DB는 `.env`를 수정해도 계정 비밀번호가 자동 변경되지 않는다. 비밀번호 오류 때문에 기존 볼륨을 삭제하지 않는다.

## 3. Docker로 MySQL 실행

```cmd
docker compose up -d
docker compose ps
```

- `up -d`: 설정에 따라 MySQL을 백그라운드로 실행한다.
- `ps`: 컨테이너 상태를 확인한다.

첫 실행에는 이미지 다운로드·DB 초기화 시간이 필요하다. 상태가 **`healthy`**가 될 때까지 기다린다.

| 연결 항목 | 값 |
|---|---|
| 이미지 | `mysql:8.4.11` |
| PC에서 접속 | `localhost:3307` |
| 컨테이너 내부 포트 | `3306` |
| DB / 앱 계정 | `pickplace` / `pickplace_app` |

로그가 필요하면 확인한다. 공유 전에는 비밀번호·개인 정보를 가린다.

```cmd
docker compose logs --tail 100 mysql
```

**이 단계는 MySQL만 켠 상태다.** 새 DB에 업무 테이블이 없어도 정상이다.

## 4. IntelliJ에서 서버 실행

1. 저장소를 열고 Gradle 동기화를 완료한다.
2. Project SDK·Gradle JVM·실행 JRE를 모두 JDK 25로 맞춘다.
3. `PickplaceBackendApplication`의 실행 설정을 연다.
4. **Run → Edit Configurations → Environment variables**에 다음 값을 추가한다. 항목이 없으면 **Modify options**를 확인한다.

| Name | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` |
| `MYSQL_PASSWORD` | `.env`의 앱 계정 비밀번호와 같은 값 |

비밀번호가 들어 있는 실행 설정을 공유 프로젝트 파일로 저장하지 않는다.

### 실행하면서 자동으로 일어나는 일

1. Spring Boot가 개발용 MySQL에 연결한다.
2. Flyway가 미적용 V1을 실행한다.
3. 기준정보 테이블과 적용 이력이 생성된다.
4. 웹 서버가 8080 포트로 실행된다.

**Flyway는 테이블 구조를 만들 뿐 기준정보 행을 넣지는 않는다.** 이미 적용된 V1은 중복 실행하지 않는다.

### 서버 상태 확인

별도 CMD 창에서 실행한다.

```cmd
curl http://localhost:8080/actuator/health
```

정상 응답:

```json
{"status":"UP"}
```

현재 기능 API는 아직 없으므로 `/`에서 404가 나오는 것은 서버 실행 실패가 아니다.

## 5. 테이블 생성 확인

CMD에서 MySQL 클라이언트에 접속한다.

```cmd
docker compose exec mysql mysql -u pickplace_app -p pickplace
```

프롬프트에 **앱 계정 비밀번호**를 입력한다. 비밀번호를 명령 뒤에 직접 붙이지 않는다.

프롬프트가 `mysql>`로 바뀌면 다음은 CMD가 아닌 **SQL**이다. SQL 끝에는 세미콜론을 붙인다.

```sql
SELECT VERSION(), DATABASE(), CURRENT_USER();
SHOW TABLES;
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

정상 상태:

- 선택된 DB가 `pickplace`다.
- `district`, `administrative_dong`, `business_type`, `flyway_schema_history`가 보인다.
- Flyway 이력에 V1이 있고 `success`는 `1`이다.

미적재 상태의 건수도 확인할 수 있다.

```sql
SELECT 'district' AS table_name, COUNT(*) AS row_count FROM district
UNION ALL
SELECT 'administrative_dong', COUNT(*) FROM administrative_dong
UNION ALL
SELECT 'business_type', COUNT(*) FROM business_type;
```

**처음 만든 DB라면 0 / 0 / 0이 정상**이다. 예전에 적재했다면 행이 있어도 정상이므로 삭제하지 않는다.

MySQL 클라이언트를 종료한다.

```sql
exit;
```

이후에는 IntelliJ의 일반 서버를 중지해도 된다. **Spring 서버를 중지해도 Docker MySQL은 계속 실행된다.**

## 6. 적재 전 자동 테스트

Docker Desktop을 켠 상태에서 CMD의 저장소 루트에서 실행한다.

```cmd
gradlew.bat build --no-daemon
```

`build`는 컴파일·패키징·테스트를 수행한다. `--no-daemon`은 빌드 후 지속 실행되는 Gradle 데몬을 유지하지 않도록 요청하는 옵션이다.

정상 결과는 **`BUILD SUCCESSFUL`**이다. 작성 시점에는 34건이 통과했으며 이후 테스트 추가에 따라 건수는 달라질 수 있다.

### 테스트 성공과 개발 DB 적재는 다르다

| 구분 | 개발용 DB | 자동 테스트 DB |
|---|---|---|
| 이름 | `pickplace` | `pickplace_test` |
| 생성 방식 | Docker Compose | Testcontainers 임시 생성 |
| PC 포트 | 3307 | 동적 할당 |
| 데이터 | 명명 볼륨에 유지 | 테스트 종료 후 임시 컨테이너 정리 |

테스트는 개발 DB에 기준정보를 넣지 않는다. 일반 Spring 서버나 IDE 비밀번호 설정도 테스트 실행에 필요하지 않다.

종료 시 `Shutdown` 로그는 테스트용 서버·DB 연결을 정리하는 정상 과정일 수 있다. 성공 여부는 빌드 결과와 `build/reports/tests/test/index.html`로 판단한다.

## 7. 개발 DB에 기준정보 적재

> 여기서부터 연결된 개발 DB에 실제 데이터를 저장한다.  
> 대상이 `pickplace`인지 확인하고, 동시에 여러 번 실행하지 않는다.

### CMD에 비밀번호 전달

IntelliJ의 환경변수는 CMD로 자동 전달되지 않는다. 적재할 CMD 창에서 실행한다.

```cmd
set /p "MYSQL_PASSWORD=MySQL 앱 계정 비밀번호 입력: "
```

`.env`의 앱 계정 비밀번호를 입력하고 Enter를 누른다. **입력값은 화면에 보일 수 있으므로 터미널을 공유·촬영하지 않는다.** 현재 CMD에서 실행하는 자식 프로세스에 이 값이 전달된다.

### 수동 적재 실행

```cmd
gradlew.bat bootRun --args="--spring.profiles.active=local,reference-import --pickplace.reference-import.confirm=true"
```

| 항목 | 의미 |
|---|---|
| `bootRun` | Spring Boot 실행 |
| `local` | 개발용 DB 연결 설정 사용 |
| `reference-import` | 웹 서버 없이 기준정보 적재 |
| `confirm=true` | 데이터 저장 의사를 명시적으로 확인 |

정상 적재 후 완료 로그를 출력하고 종료한다. 최초 전체 적재의 로그 내용:

```text
Reference import committed: version=seoul-reference-20262-mois-20261012-v1, insertedDistricts=25, insertedDongs=425, insertedBusinessTypes=100
```

`committed`는 **DB 트랜잭션 저장**을 뜻하며 Git 커밋이 아니다. 적재 명령은 Git 커밋·푸시를 하지 않는다.

### 재실행·실패 시 동작

- 같은 원본이면 신규 건수는 **0 / 0 / 0**이며 기존 값·수집/저장 시각을 유지한다.
- 팀에서 바꾼 업종 표시명·지원 여부·정렬순서도 유지한다.
- 원천명·부모·공식 대응·버전 충돌은 자동 덮어쓰기 없이 중단한다.
- 중간 실패 시 이번 INSERT 전체를 롤백하고 기존 데이터는 유지한다.
- 고정 초기 스냅샷용이며 분동·통합·정정본을 자동 병합하지 않는다.

확인 옵션이 없으면 적재를 거부한다. 다만 **Flyway는 거부 전에 실행될 수 있으므로 확인 옵션은 DDL 차단 장치가 아니다.**

적재 후 현재 CMD의 비밀번호 변수를 지울 수 있다.

```cmd
set "MYSQL_PASSWORD="
```

## 8. SQL로 최종 확인

다시 MySQL에 접속한다.

```cmd
docker compose exec mysql mysql -u pickplace_app -p pickplace
```

### 전체 건수

```sql
SELECT 'district' AS table_name, COUNT(*) AS row_count FROM district
UNION ALL
SELECT 'administrative_dong', COUNT(*) FROM administrative_dong
UNION ALL
SELECT 'business_type', COUNT(*) FROM business_type;
```

| 테이블 | 최초 전체 적재 후 |
|---|---:|
| `district` | 25 |
| `administrative_dong` | 425 |
| `business_type` | 100 |

### 초기 지원 업종

```sql
SELECT business_type_code, source_business_type_name, display_name, is_supported
FROM business_type
WHERE is_supported = TRUE
ORDER BY display_order, business_type_code;
```

| 코드 | 초기 표시명 |
|---|---|
| `CS100010` | 커피·음료 |
| `CS100008` | 분식 |
| `CS100005` | 제과점 |

팀에서 서비스 설정을 바꿨다면 결과가 달라질 수 있다. 재적재는 이를 초기값으로 강제 복원하지 않는다.

### 공식 코드 상태

```sql
SELECT official_code_status, COUNT(*) AS row_count
FROM administrative_dong
GROUP BY official_code_status
ORDER BY official_code_status;
```

| 상태 | 예상 건수 |
|---|---:|
| `CURRENT` | 422 |
| `LEGACY` | 3 |

말소 코드 확인:

```sql
SELECT dong_code, dong_name, official_dong_code, official_abolished_on
FROM administrative_dong
WHERE official_code_status = 'LEGACY'
ORDER BY dong_code;
```

용신동·일원2동·상일동의 원천 코드가 남아 있어야 한다. 상태는 **2026-10-12 공식 코드표와 대조한 결과**다. 원천 425개 동이 오늘의 모든 행정동 경계와 같다는 뜻은 아니다.

확인 후 종료한다.

```sql
exit;
```

이제 기준정보가 준비됐다. IntelliJ에서 일반 `local` 서버를 다시 실행해 개발을 이어갈 수 있다. 목록 API는 이후 구현할 작업이다.

## 9. 종료와 다음 실행

| 작업 | 방법 | 데이터 |
|---|---|---|
| Spring 서버 종료 | IntelliJ Stop | DB·데이터 유지 |
| MySQL 종료·컨테이너 제거 | `docker compose down` | 명명 볼륨의 데이터 유지 |
| 다음 개발 때 MySQL 실행 | `docker compose up -d` | 기존 데이터 재사용 |

평소 종료는 다음 명령을 사용한다.

```cmd
docker compose down
```

**`docker compose down -v`는 데이터 볼륨을 삭제한다.** 비밀번호·Flyway 오류 해결을 위해 실행하지 않는다. 다음 날은 MySQL과 일반 서버를 켜면 되며, 정상 적재된 기준정보를 매번 넣을 필요는 없다.

## 10. 자주 막히는 부분

| 증상 | 먼저 확인할 내용 |
|---|---|
| Compose 비밀번호 누락 오류 | 루트 `.env`의 파일명·위치·빈 값 |
| Docker 연결 실패 | Docker Desktop·Linux containers 엔진 실행 상태 |
| Java·toolchain 오류 | CMD의 JDK, IDE SDK·Gradle JVM이 25인지 |
| Spring 비밀번호 변수 해석 실패 | IDE 또는 해당 CMD에 환경변수를 설정했는지 |
| `Access denied` | 앱 계정 비밀번호와 최초 초기화 시의 값 |
| 8080 / 3307 사용 중 | 기존 서버·다른 MySQL·컨테이너 실행 여부 |
| 테이블은 있는데 데이터 0건 | 일반 서버만 실행하고 수동 적재는 안 했는지 |
| 테스트는 성공했는데 개발 DB가 비어 있음 | 테스트 DB와 개발 DB는 별개이므로 정상일 수 있음 |
| Flyway가 기존 DB를 거부 | 스키마·이력부터 확인. baseline·repair·삭제로 우회하지 않음 |
| 적재 중 원천·버전 충돌 | 고정 스냅샷과 기존 값 비교. 덮어쓰기·초기화 금지 |
| 적재 후 서버가 종료됨 | 수동 적재 프로필은 완료 후 종료하므로 정상 |
| 클론한 코드에 적재 파일 없음 | 구현이 반영된 브랜치인지 확인. 미커밋 파일은 클론되지 않음 |

비밀번호를 바꿔도 기존 DB 계정은 자동 변경되지 않는다. 실제 비밀값이 담길 수 있는 `docker compose config` 전체 출력이나 IDE 실행 설정을 그대로 공유하지 않는다.

## 완료 체크리스트

- [ ] JDK 25·Docker 실행 확인
- [ ] `.env`와 Spring 실행 환경변수 준비
- [ ] MySQL healthy·DB 접속 확인
- [ ] 일반 서버 health 응답 UP
- [ ] 업무 테이블 3개·V1 성공 이력 확인
- [ ] 자동 테스트 BUILD SUCCESSFUL
- [ ] 수동 적재 완료 로그 확인
- [ ] 개발 DB의 25 / 425 / 100건 확인
- [ ] 초기 지원 업종·공식 코드 상태 확인

상세 근거는 [DB 설계](database/reference-schema-design.md), [V1 검증](database/migration-validation.md), [수동 적재 상세](data/reference-import.md)에 있다. 기본 실행은 이 문서 순서대로 진행하면 된다.
