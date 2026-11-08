# 백엔드 개발 문서

> 이 브랜치의 상태: Flyway V1 기준정보·V2 통계 및 적재 이력·V3 H10 기준정보·V4 전국 원천/소진공 업종 스키마, 기존 수동 기준정보 적재·공통 처리·서울 목록 API 구현. 전국 실제 적재·통계 구조·새 목록 전환은 후속 작업.

<a id="문서-바로가기"></a>

## 처음 실행 · FE 연동

| 문서 | 확인할 내용 |
| --- | --- |
| [처음 실행부터 DB 적재까지](getting-started.md) | 기초 환경 준비부터 실제 데이터 적재·SQL 확인까지 따라가는 통합 안내 |
| [FE 인계 · 현재 지원 API 안내](api/frontend-api-guide.md) | 실제 호출 가능한 API·main/develop 차이·응답·접속·오류·미구현 목록 |

## 설계 · API 계약

| 문서 | 확인할 내용 |
| --- | --- |
| [기준정보 DB 설계](database/reference-schema-design.md) | ERD·컬럼·PK/FK·인덱스·이력 정책 |
| [H10 기준정보 DB](database/h10-reference-schema.md) | T06 V3·전국 지역/서울 원천 분리·9개 테이블·제약·T07/T11 후속 범위 |
| [전국 원천 기준정보 DB](database/nationwide-reference-schema.md) | T06 V4·인구 H10/상가8 대응·소진공 업종 계층·승인 제약 |
| [상권 통계·적재 이력 DB 설계](database/statistics-schema-design.md) | T11 통계 6종·배치·게시·품질 이슈, V2 적용 방법 |
| [공통 응답 형식](api/common-response.md) | 성공·오류 응답 구조, 오류 코드, `X-Request-Id`, 서버 상태 API |
| [FE·BE 통합 API 명세 v0.2](api/fe-be-contract.md) | 목록·공통 통신·오류·빈 결과와 FE 전환 작업. FE 공동 검토 전 초안 |
| [분기별 기본 현황 API·표시 규칙 v0.1](api/quarterly-summary-contract.md) | 핵심 지표·분기 선택·0/null·품질 상태·현황 응답과 화면 표시. 구현 전 초안 |
| [기준정보 조회 API](api/reference-api.md) | 자치구·행정동·지원 업종 목록 API의 요청·응답·오류 예시 |

## 적재 · 원천 · 검증

| 문서 | 확인할 내용 |
| --- | --- |
| [Flyway 마이그레이션 검증](database/migration-validation.md) | V1 단계의 23건 검증과 DB 적용 주의사항 |
| [기준정보 수동 적재](data/reference-import.md) | 실행 명령·원천 스냅샷·재적재 정책·T07 단계 34건 검증 |
| [전국 기준정보 수동 적재](data/national-reference-import.md) | T07 전국 원본→검토 묶음→적재·승인·게시·복구, 실제 실행 결과 |
| [원천 manifest](data/reference-source-manifest.json) | 원본 URL·조회 조건·SHA-256·수집 시각 |
| [공공데이터 수집 대상·다운로드 경로](data/public-data-sources.md) | T12 1단계 · 초기 4종·분할 수집·실제 응답·수집 설정 |
| [공공데이터 수동 CLI](data/public-data-command.md) | T12 · 핵심 4종 수집·점포 100업종 검증·원본 보관 |

## 실제 실행 기준과 설계 이력

| 파일 | 구분 |
|---|---|
| [V1 마이그레이션](../src/main/resources/db/migration/V1__create_reference_tables.sql) | 실제 테이블 생성 기준 |
| [V2 마이그레이션](../src/main/resources/db/migration/V2__create_statistics_tables.sql) | 통계·적재 이력 테이블 생성 기준 |
| [V3 마이그레이션](../src/main/resources/db/migration/V3__create_h10_reference_tables.sql) | H10 기준정보 테이블 추가. 기존 데이터·API·통계 FK 유지 |
| [V4 마이그레이션](../src/main/resources/db/migration/V4__extend_nationwide_reference_tables.sql) | 전국 원천 지역 제약 확장·소진공 업종 분류본과 계층 추가 |
| [적재 스냅샷](../src/main/resources/reference-data/seoul-reference.json) | 실제 적재기의 고정 입력 |
| [최초 검토용 SQL](database/reference-schema-draft.sql) | 설계 이력, 별도 실행하지 않음 |

문서의 설명과 적용된 SQL이 다르다면 먼저 확인하고 문서를 갱신한다. 이미 적용한 V1을 문서에 맞추기 위해 임의 수정하지 않는다.

## 읽는 순서

처음 시작한다면 [통합 실행 안내](getting-started.md)부터 읽는다. 아래는 세부 설계를 확인할 때의 순서다.

1. 구조를 이해하려면 **DB 설계**를 확인한다.
2. 테이블 생성·안전 설정을 확인하려면 **마이그레이션 검증**을 읽는다.
3. 실제 데이터를 넣으려면 **수동 적재**의 대상 DB·환경변수·주의사항을 먼저 확인한다.

서버 실행·환경변수·테스트 기본 사용법은 [프로젝트 README](../README.md)를 참고한다.
