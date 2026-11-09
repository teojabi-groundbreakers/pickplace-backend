# 세종 자동 선택 권고 · 승인·게시 기록

반영일: 2026-11-09 · 브랜치 `chore/sejong-auto-select`.

**개발 DB 반영 완료.** 새 `national-202609-sejong-hint-v1`(release_id 5)을 APPROVED로 승인하고 revision 2로 게시했다. 세종 그룹 `3611000000`만 auto_select_group=TRUE이며, 기존 release_id 4의 FALSE와 이전 게시 이벤트를 보존했다.

## 정책과 범위

true는 BE의 자동 선택 권고다. false는 권고 없음이며 자동 선택 금지가 아니다. 실제 선택 적용·기존 선택 복원·중간 칸 숨김/잠금·후속 목록 요청은 FE가 결정한다. 세종의 시·도3600000000과 그룹3611000000을 유지하며 다른 단일 그룹으로 확대하지 않는다.

FE가 권고를 적용하면 그룹 선택 후 `/api/regions/localities?groupCode=3611000000`을 조회한다. **이번 작업은 실제 FE 동작이나 배포된 API 서버 검수 완료를 뜻하지 않는다.** PR #20의 DB 값을 그대로 읽는 구현을 유지하며 세종 코드 하드코딩은 필요하지 않다.

## 실제 개발 DB 검수

| 항목 | 결과 |
| --- | --- |
| 새 버전 | national-202609-sejong-hint-v1 / APPROVED |
| 새 release_id / 게시 revision | 5 / 2 |
| snapshot SHA-256 | 0b5430750eed5ea951ee9161589e92e07df5fb5beea6c0cc36e651d5982bc1a9 |
| 세종 그룹 autoSelect | 기존 release 4 FALSE → 새 release 5 TRUE |
| autoSelect=TRUE인 코드 | 3611000000 하나 |
| 다른 지역 선택 정보 변경 | 0건 |
| 선택 가능 지역 | 시·도16 / 그룹256 / 읍면동3,565 유지 |
| 출장소 관련 제외 | 76개 보존·선택 불가 유지 |
| 업종 | classification_id 3 재사용, I21201 / I21007 / I21001 유지 |
| 원천 지역 | 7,177개 유지 |
| 게시 이벤트 | 기존 최초 게시 포함 총2개 |
| Flyway | V1~V4 유지. 이번 작업으로 통계 V5 적용하지 않음 |

출처 날짜 null·원천 경계 보류·원본/대응/분류 내용은 변경하지 않는다. 기존 snapshot 원문과 해시는 그대로이며 승인된 행을 직접 수정하지 않았다. 생성 후 import→approve→publish 순서로 실행했고 기존 revision 1을 기대값으로 확인했다. 실제 게시 후에는 읽기 전용 SQL로 검수했으며 불필요한 재게시를 하지 않았다.

## 재현·테스트

[최신 적재 묶음과 팀원 실행 안내](../../data/reference/national-202609-sejong-hint-v1/README.md)를 사용한다. 원본 재다운로드나 Python 없이 같은 JSON을 적재할 수 있다. 새 묶음 생성은 `scripts/prepare_sejong_auto_select.py`로 재현하며 원본 해시·역할·부모·검토 상태·중복 권고를 검사한다.

검증: Java 23개 스위트·235건, Python 21건, 실패/오류/건너뜀0 및 `clean check bootJar` 성공. 이 브랜치는 통계 V5/PR #20의 미병합 코드와 분리한 develop cc27ab3 기준이므로 다른 브랜치의 292/270건과 합산하지 않는다.

`SejongAutoSelectReleaseTests`는 저장소의 실제 이전/새 묶음을 격리 MySQL에서 적재·승인·게시한다. 허용된 version/autoSelect/evidence 외 전체 JSON 동일성, 기존 FALSE 보존, 새 TRUE 하나, 원천·업종·제외 정책·게시 이력·재적재를 확인한다. 실제 개발 DB는 쓰기 후 읽기 전용 검수를 수행했다. [검증 기록](sejong-auto-select-release.json).

팀원이 현재 게시본을 사용하려면 새 버전 적재·승인·게시가 필요하다. Git 병합만으로 각자의 DB가 자동 갱신되지는 않는다. 기존 게시본이 있으면 반드시 본인 DB의 현재 revision을 확인한다.
