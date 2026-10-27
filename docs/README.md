# 백엔드 개발 문서

> 현재 상태: Flyway V1·수동 기준정보 적재 구현, 임시 MySQL에서 전체 34건 검증.  
> 개발 DB 실제 적재와 Entity·목록 API 구현은 아직 진행하지 않았다.

## 문서 바로가기

| 문서 | 확인할 내용 |
|---|---|
| [처음 실행부터 DB 적재까지](getting-started.md) | 기초 환경 준비부터 실제 데이터 적재·SQL 확인까지 따라가는 통합 안내 |
| [기준정보 DB 설계](database/reference-schema-design.md) | ERD·컬럼·PK/FK·인덱스·이력 정책 |
| [Flyway 마이그레이션 검증](database/migration-validation.md) | V1 단계의 23건 검증과 DB 적용 주의사항 |
| [기준정보 수동 적재](data/reference-import.md) | 실행 명령·원천 스냅샷·재적재 정책·최신 34건 검증 |
| [원천 manifest](data/reference-source-manifest.json) | 원본 URL·조회 조건·SHA-256·수집 시각 |

## 실제 실행 기준과 설계 이력

| 파일 | 구분 |
|---|---|
| [V1 마이그레이션](../src/main/resources/db/migration/V1__create_reference_tables.sql) | 실제 테이블 생성 기준 |
| [적재 스냅샷](../src/main/resources/reference-data/seoul-reference.json) | 실제 적재기의 고정 입력 |
| [최초 검토용 SQL](database/reference-schema-draft.sql) | 설계 이력, 별도 실행하지 않음 |

문서의 설명과 적용된 SQL이 다르다면 먼저 확인하고 문서를 갱신한다. 이미 적용한 V1을 문서에 맞추기 위해 임의 수정하지 않는다.

## 읽는 순서

처음 시작한다면 [통합 실행 안내](getting-started.md)부터 읽는다. 아래는 세부 설계를 확인할 때의 순서다.

1. 구조를 이해하려면 **DB 설계**를 확인한다.
2. 테이블 생성·안전 설정을 확인하려면 **마이그레이션 검증**을 읽는다.
3. 실제 데이터를 넣으려면 **수동 적재**의 대상 DB·환경변수·주의사항을 먼저 확인한다.

서버 실행·환경변수·테스트 기본 사용법은 [프로젝트 README](../README.md)를 참고한다.
