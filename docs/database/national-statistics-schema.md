# 전국 인구·업소 통계 DB — T11

구현일: 2026-11-09 · `feature/national-statistics-schema` · Flyway V5.

후속 T14는 V5를 유지하고 V6에 첫 적재·게시 경합을 보호하는 mutex 테이블만 추가한다. 실행 명령·재시도·승인·게시 절차는 [T14 적재 안내](../data/national-statistics-import.md)를 참고한다.

주민등록 **월말 인구**와 소진공 **배포본 수록 업소**의 저장 구조다. 서울 분기 통계·CS 업종과 의미를 섞지 않으며 V1~V4 SQL·기존 테이블·이력은 변경하지 않는다.

설계 원문: [전국 통계·적재 이력 설계 v0.2](https://app.notion.com/p/40c2114f823a81b1a2abeef62c225ff1). 실행 기준은 [V5 SQL](../../src/main/resources/db/migration/V5__create_national_statistics_tables.sql)이다. [T06 기준정보](nationwide-reference-schema.md)·[데이터 정의서](https://app.notion.com/p/3fa2114f823a818c9bd8e2664cbcd618)를 함께 참고한다.

## 테이블과 처리 순서

| 단계 | 테이블 | 한 행·책임 |
| --- | --- | --- |
| 적재 시도 | national_import_batch | 데이터셋 하나의 처리 시도. 원천·월·대응·분류·지문·행수 회계·상태 |
| 원본 | national_source_artifact | 배치+artifact 번호. 인구 CSV, 상가 ZIP/내부 CSV의 해시·크기·멤버·헤더·인코딩 |
| 인구 | resident_population_month | 배치+H10. 총·남·녀 인구와 남녀 각각 111개 연령값 |
| 업소 | store_snapshot_entry | 배치+고유 업소ID. 원천지역·H10·소분류·좌표·상태·원문행 |
| 수록 범위 | store_region_coverage | 배치+실제 수록 원천지역. 지역별 회계·업종 분포·시점/대응 상태 |
| 사전 집계 | store_industry_count | 배치+H10+분류본+소분류. 내부 채택 수와 집계 품질 |
| 품질 이력 | national_quality_issue | 행/배치 문제와 근거·집계 영향 범위 |
| 현황 묶음 | national_summary_release | 같은 월의 인구·업소 성공 배치와 지역/업종 기준정보·승인 기록 |
| 게시 포인터 | national_summary_publication | MONTH:YYYYMM 또는 DEFAULT의 승인 묶음·revision |
| 게시 이력 | national_summary_publication_event | 이전/새 묶음·월·revision·작업·기본 동기화 여부·주체·사유 |

인구와 업소 사실 테이블을 직접 연결하지 않는다. summary가 검증된 두 배치를 고정하며, 요청은 게시된 summary를 기준으로 각각 조회한다.

## 원천·기간·버전 제약

| 원천 | 데이터셋 | 기간 |
| --- | --- | --- |
| MOIS_RESIDENT | POPULATION_MONTH | MONTH / YYYYMM / 월말 기준일 |
| SEMAS_STORE | STORE_SNAPSHOT | SNAPSHOT / YYYYMM / 배포본 기준일 |

월은 01~12이고 기준일과 같은 월이어야 한다. 인구는 월말 날짜를 요구한다. 상가 배포일을 모든 업소의 실제 조사일로 해석하지 않는다.

사실 테이블은 batch_id 외에 데이터셋·원천 시스템·기간·대응 릴리스·원천 지역 버전도 복합 FK로 묶는다. 상가 분류본은 배치의 분류본과 같아야 하며 인구 배치에는 분류본을 넣을 수 없다. 승인된 T06 지역·분류만 배치가 참조한다.

인구 원천 H10은 지역 H10과 같아야 한다. 상가 원천 행정동8·법정동10·공식 H10은 별도 필드다. region_stat_mapping의 원천 체계·버전·대응 릴리스·대상 H10을 정확히 참조하며 문자열 변환으로 대응하지 않는다.

## 재적재·회계·원문 추적

- 지문은 T13/T14에서 원본 SHA·파서·정제·정책·대응·분류 버전으로 계산한다. DB는 지문 형식과 시도 유일성을 검사한다.
- 같은 데이터셋·지문의 RUNNING/VALIDATING/SUCCEEDED는 하나만 허용한다. FAILED는 보존하고 새 attempt_no로 재시도한다.
- 원천 행수 = 채택 + 보류 + 거부 + 중복. 추가 경고를 행수에 다시 더하지 않는다. SUCCEEDED는 종료 시각·행수·보고서가 필요하며 게시와 별개다.
- 업소 ID와 원본 artifact/행 위치는 배치 안에서 각각 유일하다. 동일 ID의 내용 충돌과 중복 회계는 파서가 판단한다.
- 내부 CSV는 같은 배치의 부모 artifact를 참조한다. ZIP/member 해시·CRC·컬럼·행수를 보존하며 원문 39컬럼·URL을 업소마다 복제하지 않는다.
- 잘못된 원천 키·누락 ID 등 사실 테이블에 넣을 수 없는 행은 quality issue의 원문 위치·원시값·사유로 보존한다. 알려진 원천의 대응/업종 보류는 HOLD 사실 행으로 남길 수 있다.

## 값·품질·0 구분

인구 AVAILABLE은 총·남·녀와 연령 JSON이 필수이며 총=남+녀, 비음수·JavaScript 안전 정수 범위를 검사한다. 연령 JSON은 male/female 각각 111개 배열을 요구한다. 인덱스 0~109·110PLUS의 의미와 **모든 연령 원소의 정수/비음수·합계는 T13 전수 검사**다. JSON 길이만으로 전체 유효성을 보장하지 않는다.

실제 0은 AVAILABLE의 0이고, NO_DATA/INVALID/SUPPRESSED는 NULL+사유다. NOT_LOADED를 원천값으로 넣지 않는다. temporal_region_status는 VALID/PENDING/BOUNDARY_MISMATCH로 별도 보존한다.

업소 ACCEPTED는 H10·소분류·VALID 시점이 필수다. HOLD/REJECTED는 사유가 필요하다. nullable FK 때문에 채택 행의 필수 참조가 NULL로 우회되지 않도록 CHECK를 둔다. 좌표 품질은 별도 축이므로 유효한 ID·지역·업종의 업소는 위치 누락만으로 개수에서 제외하지 않는다.

| 사례 | 내부 채택 수 | 집계 품질 | 후속 조회 기대값 |
| --- | --- | --- | --- |
| 유효 100행 | 100 | VALID | 100 |
| 동일 지역×업종 100행 중 집계 영향 3행 보류 | 97 | HOLD / 영향행 3 | NULL+사유. 정상 인구·다른 업종 유지 |
| 검증된 수록 지역에서 선택 업종만 없음 | 0 | VALID | 목록 기준 0 |
| 원천 지역 부재·미대응·처리 미완료 | 부재/보류 | 별도 상태 | 0으로 대체 금지 |

HOLD는 사유·영향행 수>0, VALID는 사유 없음·영향행 수=0이다. coverage는 실제 수록 지역만 저장하며 source_row_count>0과 지역별 회계를 검사한다. coverage 부재를 임의로 업소 0으로 만들지 않는다. 업종 부재의 0 생성 근거와 누락 영향은 T13/T14가 검증한다.

품질 문제의 영향은 NONE/REGION/REGION_INDUSTRY/GLOBAL로 기록한다. 업종을 한정할 수 없으면 해당 지역 전체, 지역도 한정할 수 없으면 GLOBAL이다. 한 원본행의 여러 경고를 여러 누락 업소로 세지 않으며, GLOBAL 집계 문제는 새 게시를 막아야 한다.

## 게시 구조와 T14의 책임

summary는 같은 월·같은 지역 릴리스의 인구/업소 SUCCEEDED 배치와 승인된 분류만 묶는다. READY는 게시할 수 없고 APPROVED만 포인터가 참조한다. MONTH:202609는 자기 월만 참조하고, DEFAULT와 별도 행이므로 9월 포인터 변경이 기본 12월을 자동 변경하지 않는다.

이번 T11은 **저장 구조와 제약**이다. 다음은 DB CHECK만으로 보장되지 않으며 T14 쓰기 서비스에서 구현·검증한다.

1. 지문 계산, 상태 전이 순서, 완료 배치·승인 묶음·이벤트 불변성.
2. 매핑 VERIFIED 여부·원천/기간/경계·연령·원본·집계 영향의 전수 검증.
3. DEFAULT 대상의 월별 게시 이력 확인, 예상 revision·기존 묶음 검사와 잠금 순서.
4. 명시적 DEFAULT 변경/동기화, 실제 변경 포인터와 각 이벤트의 원자 저장·경합·복구.
5. 과거 월 정정이 DEFAULT를 임의로 바꾸지 않는 정책, 실패 재시도 및 회계 복구.

저장점 테스트는 포인터 쓰기 후 이벤트 제약 실패 시 같은 연결에서 되돌릴 수 있음을 검사한다. 실제 게시 서비스나 동시성 검증 완료를 뜻하지 않는다.

## 검증·실행·적용 상태

격리 MySQL 8.4.11에서 새 스키마 58건과 업그레이드 1건을 검증했다. FK/CHECK/중복키는 실제 MySQL 오류 코드까지 검사한다. 전체 빌드 결과는 [검증 기록](national-statistics-validation.json)을 따른다.

```cmd
gradlew.bat test --tests "*NationalStatisticsSchemaTests" --tests "*StatisticsSchemaUpgradeTests"
gradlew.bat clean check bootJar
```

빈 DB와 V4 기존 DB 모두 V5 적용·재실행을 검사하며 V1~V4 체크섬·기존 원천 대응·서울 통계 0·업종 지원값을 보존한다.

개발 DB는 읽기 전용 확인 시 V1~V4 성공 이력, 새 전국 통계 테이블 0개였다. **이번 작업에서 실제 개발 DB 적용·숫자 적재는 수행하지 않았다.** 이 브랜치로 서버를 실행하면 Flyway V5가 대상 DB에 적용될 수 있으므로 실행 전 주소·스키마를 확인한다. MySQL DDL은 일반 트랜잭션으로 전체 롤백된다고 가정하지 않는다.

9월 인구 3,619행·업소 2,753,778행·경계 예외 674행의 파싱·실적재·전수 회계·성능·게시 서비스 검증은 T13/T14 범위다. 다음은 기존 원본을 이 구조로 파싱하고 원문행별 품질·집계 영향 보고서를 생성하는 T13이다.
