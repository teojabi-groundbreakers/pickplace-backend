# T07 최종 승인·게시

> 최신 개발 DB는 [세종 자동 선택 권고 후속 릴리스](sejong-auto-select-release.md)가 revision 2로 게시됐다. 아래 release 4/revision 1은 최초 승인·게시 이력이다.

기준일: 2026-11-09 · 브랜치 `feature/nationwide-reference-import`

**T07 완료.** 개발 DB에서 `national-202609-release-v1`(release ID4)을 APPROVED로 승인하고 최초 게시 revision1/event1을 기록했다. 분류 승인본은 ID3이며 초기3업종을 활성화했다. 기존 후보ID1·검토본ID3과 출장소 원본76개를 보존했다.

선택 검수: 시·도16·그룹256·읍면동3,565가 게시본에서 조회되고, 제외76개 중 선택 가능한 행은0이다. 원본/검토본/게시본을 포함한 Java234건·Python16건과 배포 빌드가 통과했다. 변경일 시점 보류2건은 유지한다.

출장소 초기 제외 정책과 자료 이용 근거를 반영한 `national-202609-release-v1`을 승인·게시 대상으로 사용한다. 이전 후보·검토 릴리스는 덮어쓰지 않는다. 실제 실행 결과는 [완료 검증 기록](national-reference-completion.json)을 기준으로 확인한다.

## 최종 기준

| 항목 | 기준 |
|---|---|
| 출장소 | 고정 원본 해시·명시 코드76개·정책 버전·제외 근거가 일치할 때만 비선택 제외로 인정 |
| 임의 보류 | 다른 BLOCKED/PENDING 노드를 같은 방식으로 통과시키지 않음 |
| 원본 보존 | 76개 공식 노드·이력·원천 대응을 유지하고 일반 읍면동으로 합치지 않음 |
| 이용 근거 | [공식 자료 목록과 원문 연결](national-reference-usage.md)을 검토하고 출처·적용 범위 기록 |
| 초기 업종 | 원천명 그대로 카페I21201, 김밥/만두/분식I21007, 빵/도넛I21001 |
| 분류 이력 | 새 `semas-202609-service-v1` 승인본에 지원3개 설정. 기존 원천 분류본과 서울 CS는 보존 |
| 통계 시점 | 구지읍/구지면 변경일2건은 계속 HOLD. 지역 목록 게시와 숫자 집계 승인을 구분 |

김밥/만두/분식 및 빵/도넛은 기존 서울 CS 업종과 동일 범위라고 변환하지 않는다. 화면·API는 새 원천명을 사용하고, 소분류247개 전체 저장과 지원3개를 구분한다.

## 재현 순서

팀원이 같은 데이터를 로컬 DB에 적재하려면 [저장소 포함 묶음](../../data/reference/national-202609-release-v1/README.md)을 사용한다. 원본 재생성은 필요하지 않다. 아래는 원본부터 새 묶음을 만드는 유지보수 절차다.

1. [검토본 생성](national-reference-review.md)을 마친다.
2. `scripts/prepare_national_reference_release.py`에 검토본 경로/해시, 정책 JSON, 이용 근거 JSON, 원문 보관 폴더를 전달한다.
3. 출력 SHA256으로 새 버전을 import한다. 검토본의 원본·분류 내용을 직접 UPDATE하지 않는다.
4. approve 명령으로 승인한다. 명시적으로 검토된 제외만 허용하며 미검토 원천은 거절한다.
5. 현재 게시 revision을 확인하고 publish한다. 게시 포인터·이벤트를 함께 기록한다.
6. 게시된 release_id를 고정해 선택 목록과 승인된 분류본을 조회하고, 재실행 시 중복과 이벤트 증가가 없는지 확인한다.

실행 예시(원본·경로·해시는 자신의 환경에 맞춘다):

```cmd
python -B scripts\prepare_national_reference_release.py --input 검토본_JSON --sha256 검토본_SHA256 --policy src\main\resources\reference-data\national-initial-selection-policy.json --usage docs\data\national-reference-usage.json --evidence-dir 이용근거_보관폴더 --version national-202609-release-v1 --output 새_게시본_JSON
```

DB 명령은 [수동 적재 안내](national-reference-import.md)의 import→approve→publish를 사용한다. 자료를 공개하는 승인 행위는 사용자의 T07 최종 완료 요청에 따라 수행하며 실행 주체는 `codex-t07-finalization`으로 남긴다.

## 완료 범위

T07 완료는 기준정보 적재·검토·승인·DB 게시·재실행/실패 복구 검증까지다. 실제 인구 수치·개별 업소 사실 적재는 T13/T14, 전국 목록/현황 API와 FE 연결은 해당 후속 작업이다. 초기 게시에서 이전 실제 게시본은 없으므로 복구 동작은 격리 MySQL의 두 승인 릴리스로 검증한다.

기본 테스트와 추가 실제 데이터 검사는 [테스트 클래스](../../src/test/java/com/pickplace/backend/NationalReferenceImportTests.java)에 있다. 확정 제외 허용, 잘못된 제외/원본 변경/선택 플래그 차단, 초기 지원 업종, 포인터·event 원자성·revision 경합·복구를 포함한다.

실제 최초 승인·게시는 성공했다. 추가 실DB 재게시 검사는 자동 승인 심사에서 불필요한 중복 쓰기 위험을 이유로 거절되어 실행하지 않았다. 재실행·복구는 격리 MySQL 검사로 검증했고 실DB는 읽기 전용 조회로 최초 게시의 revision/event/해시·선택 결과를 확인했다. 실행하지 않은 재게시를 실제 검증 성공으로 기록하지 않는다.

실제 데이터 테스트는 `PICKPLACE_REFERENCE_*`, `PICKPLACE_REVIEWED_REFERENCE_*`, `PICKPLACE_RELEASE_REFERENCE_*` 환경변수의 SNAPSHOT/SHA256 쌍으로 활성화한다. 기본 fixture 검사와 원본 후보·검토본·게시본 검사를 구분해 기록한다.
