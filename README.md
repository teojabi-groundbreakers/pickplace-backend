# PickPlace Backend

공공데이터 기반 지역 상권 진단 서비스의 백엔드입니다. 데이터 적재·조회 API·AI 서버 연동을 담당합니다.

현재 구현 범위는 Spring Boot 및 MySQL 실행 환경과 기본 통합 테스트입니다. 기능 API, 업무 테이블, AI 연동은 아직 구현하지 않았습니다.

## 개발 환경

| 구성 | 버전·방식 |
|---|---|
| Java | 25 LTS · Eclipse Temurin 권장 |
| Spring Boot | 4.1.1 |
| Gradle | 9.7.1 · 저장소의 Wrapper 사용 |
| MySQL | 8.4.11 · 공식 Docker 이미지 |
| DB 접근 | Spring Data JPA |
| 로컬 실행 | 애플리케이션: IDE / MySQL: Docker Compose |

필수 도구는 JDK 25, Git, 실행 중인 Docker Desktop입니다. Windows에서는 WSL 2 기반 Linux containers를 사용합니다. MySQL과 Gradle을 별도로 설치할 필요는 없습니다.

```cmd
java -version
docker version
docker compose version
```

`java`가 25인지, `docker version`에 Client와 Server가 모두 표시되는지 확인합니다.

## 로컬 실행

아래 명령은 저장소 루트에서 실행합니다. Windows 예시는 CMD 기준입니다.

### 1. 환경변수 준비

```cmd
copy .env.example .env
```

이미 `.env`가 있으면 복사하지 않습니다. `.env`의 두 항목에 서로 다른 비밀번호를 입력합니다.

| 변수 | 용도 |
|---|---|
| `MYSQL_PASSWORD` | 애플리케이션 계정 `pickplace_app` 비밀번호 |
| `MYSQL_ROOT_PASSWORD` | DB 관리자 비밀번호 |

실제 비밀번호가 있는 `.env`는 Git에 올리지 않습니다. `.env.example`에는 변수 이름만 유지합니다.

### 2. 개발용 MySQL 실행

```cmd
docker compose up -d
docker compose ps
```

초기 이미지 다운로드·DB 초기화가 끝나고 MySQL이 `healthy`가 될 때까지 기다립니다.

| 접속 항목 | 값 |
|---|---|
| Host | `localhost` |
| Port | `3307` |
| Database | `pickplace` |
| Username | `pickplace_app` |
| Password | `.env`의 `MYSQL_PASSWORD` |

DB는 로컬 PC에만 포트를 공개합니다. 컨테이너 내부 포트는 `3306`이며, 데이터는 Docker의 `mysql_data` 명명 볼륨에 보관합니다.

```cmd
docker compose exec mysql mysql -u pickplace_app -p pickplace
```

비밀번호를 입력한 다음 확인합니다.

```sql
SELECT VERSION(), DATABASE(), CURRENT_USER();
SHOW TABLES;
exit;
```

업무 테이블을 아직 만들지 않았으므로 테이블 목록이 비어 있어도 정상입니다.

### 3. IntelliJ IDEA에서 서버 실행

1. 프로젝트를 열고 Gradle 동기화를 완료합니다.
2. Project SDK, Gradle JVM, 실행 설정의 JRE를 JDK 25로 맞춥니다.
3. `PickplaceBackendApplication` 실행 설정을 만듭니다.
4. **Run → Edit Configurations → Environment variables**에서 다음을 설정합니다. 항목이 없으면 **Modify options**에서 활성화합니다.

| Name | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` |
| `MYSQL_PASSWORD` | `.env`에 입력한 애플리케이션 계정 비밀번호 |

Docker Compose는 `.env`를 읽지만 Spring Boot는 기본적으로 이 파일을 자동으로 읽지 않습니다. IDE에 비밀번호를 별도로 지정해야 합니다. 비밀번호가 포함된 실행 설정은 **Store as project file**로 공유하지 않습니다.

서버 실행 후 확인합니다.

```cmd
curl http://localhost:8080/actuator/health
```

정상 응답:

```json
{"status":"UP"}
```

기능 API가 없으므로 `/`에서 404가 나오는 것은 정상입니다.

### 4. 개발용 DB 종료

```cmd
docker compose down
```

일반 `down`은 데이터를 유지합니다. **`down -v`는 데이터 볼륨을 삭제하므로 데이터 초기화가 목적일 때만 사용합니다.**

## 자동 테스트

Docker Desktop을 실행한 뒤 저장소 루트에서 실행합니다.

Windows CMD:

```cmd
gradlew.bat test
```

macOS / Linux:

```sh
bash ./gradlew test
```

전체 빌드·테스트:

```cmd
gradlew.bat build
```

테스트는 `test` 프로필과 Testcontainers를 사용합니다. 임시 MySQL 8.4.11을 시작하고 Spring Boot가 연결 정보를 자동으로 전달받습니다. 개발용 `.env`, IDE의 `MYSQL_PASSWORD`, 실행 중인 Compose MySQL은 필요하지 않습니다.

- 임시 DB 이름: `pickplace_test`
- DB·HTTP 포트: 자동 할당
- 개발용 DB 및 데이터 볼륨: 사용하지 않음
- 종료 시 테스트 컨테이너·임시 데이터: 정리
- Docker 사용 불가 시: 테스트 실패로 처리하며 조용히 건너뛰지 않음

검증 항목은 서버 컨텍스트 기동, MySQL SQL 실행·DB 이름·버전, `/actuator/health`의 HTTP 200 및 `UP` 응답입니다. 기능 API·업무 로직 테스트는 구현과 함께 추가합니다.

첫 실행에는 의존성과 Docker 이미지를 다운로드하기 위한 인터넷 연결이 필요합니다. 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

### GitHub Actions

`.github/workflows/ci.yaml`은 `develop`·`main` 대상 PR과 두 브랜치의 Push에서 빌드·테스트를 실행합니다. Temurin 25, Gradle Wrapper, 임시 MySQL을 사용하며 DB 비밀번호를 GitHub Secrets에 등록할 필요가 없습니다.

GitHub의 **Actions** 또는 PR의 **Checks**에서 결과를 확인합니다. 이 워크플로는 검증만 수행하며 자동 병합·배포는 하지 않습니다. 실패 시 병합을 막으려면 팀이 브랜치 보호 규칙에 `build-and-test` 검사를 별도로 등록해야 합니다.

## 설정 파일

| 파일 | 역할 |
|---|---|
| `application.yaml` | 공통 애플리케이션 설정 |
| `application-local.yaml` | 로컬 개발 DB 연결 |
| `src/test/resources/application-test.yaml` | 테스트 전용 JPA·상태 API 설정 |
| `MySqlTestConfiguration.java` | 테스트 전용 MySQL 및 자동 연결 구성 |
| `compose.yaml` | 개발용 MySQL 컨테이너 |
| `.env.example` | 비밀값 없는 환경변수 양식 |

현재 `ddl-auto: none`이므로 애플리케이션이 테이블을 자동 생성하지 않습니다. 다음 단계에서 Flyway로 스키마를 관리합니다. 로컬 JDBC의 SSL 비활성화 및 공개키 조회 허용 설정은 운영 환경에 그대로 사용하지 않습니다.

## 문제 해결

| 증상 | 확인할 사항 |
|---|---|
| Compose에 비밀번호 누락 오류 | `.env` 위치·파일명·두 비밀번호 값 확인 |
| Docker 연결 실패 | Docker Desktop 실행 및 Linux 엔진 상태 확인 |
| `MYSQL_PASSWORD` 해석 실패 | 서버 실행 설정의 환경변수 확인 |
| DB 접근 거부 | 최초 초기화한 비밀번호와 현재 입력값 비교 |
| 기본 프로필로 서버 실행 실패 | 개발 서버는 `local` 프로필 활성화 |
| JDK 또는 toolchain 오류 | JDK 25·Gradle JVM·`JAVA_HOME` 확인 |
| 3307 또는 8080 포트 사용 중 | 기존 프로그램의 해당 포트 사용 여부 확인 |

`.env` 비밀번호를 바꿔도 이미 초기화된 DB 계정은 자동 변경되지 않습니다. 데이터가 있는 볼륨을 삭제해 해결하지 말고 계정 비밀번호를 명시적으로 변경합니다.

로그 확인:

```cmd
docker compose logs --tail 100 mysql
```

로그·스크린샷 공유 시 비밀번호와 개인 정보는 가립니다. `.env`, IDE 개인 설정, 빌드 결과물은 커밋하지 않습니다. `gradle-wrapper.jar`와 Wrapper 스크립트는 재현 가능한 빌드를 위해 커밋합니다.
