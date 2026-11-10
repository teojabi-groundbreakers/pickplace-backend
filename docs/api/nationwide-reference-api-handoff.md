# T08~T10 개발 인계 · 전국 지역·업종 목록 전환

> 데이터 후속 반영: [세종 권고 릴리스](../data/sejong-auto-select-release.md). autoSelect는 BE 권고이며 false가 자동 선택 금지는 아니다. 현재 개발 DB에서 세종 그룹은 true, FE 동작은 별도 검수다.

> 작성: 2026-11-09 · 상태: 확정 지역 계약에 따른 구현 인계 / 보조 필드·오류 세부·공개 업종 전환은 별도 확인
> 코드 대조 기준: `feature/nationwide-reference-import`의 `2354912`.
> 기존 서울 API는 구현 완료 상태다. 이 문서는 전국 전환에 필요한 추가 수정 범위를 다룬다.

## 1. 담당자가 먼저 볼 내용

| 작업 | 현재 동작 | 수정 목표 |
| --- | --- | --- |
| T08 | 서울 자치구 25개, 원천 5자리 코드 | 게시된 전국 시·도와 선택 그룹 조회 |
| T09 | 자치구별 서울 행정동, 원천 8자리 코드 | 선택 그룹별 읍·면·동, H10 코드 조회 |
| T10 | 서울 업종 `CS...` 지원 3개 | 소진공 게시 분류본 조회 준비. 공개 코드 전환은 기존 CS 계약과 대조 후 반영 |

T06의 V3·V4는 개발 DB에 적용됐고, T07의 최종본은 승인·게시됐다. **적재 완료만으로 기존 API의 조회 대상이 바뀌지는 않는다.** 기존 Entity·Repository·DTO·입력 검사와 테스트를 함께 수정해야 한다.

| 현재 개발 DB 확인값 | 값 |
| --- | --- |
| 게시 키 | NATIONWIDE_SELECTION |
| 지역 버전 | national-202609-sejong-hint-v1 |
| 지역 release_id / revision | 5 / 2 |
| 연결 업종 classification_id | 3 |
| 업종 버전 | semas-202609-service-v1 |
| 선택 대상 | 시·도 16 / 그룹 256 / 읍면동 3,565 |
| 초기 제외 | 출장소 관련 76개 보존, 선택 불가 |

위 ID·건수는 이번 게시본의 검수값이다. 운영 코드·일반 테스트에 상수로 넣지 않는다. 공식 노드가 존재하는 것과 화면 선택 가능 상태는 다르다.

## 2. 수정할 코드와 유지할 공통 처리

기준 패키지는 `src/main/java/com/pickplace/backend/`다.

| 위치 | 확인·수정 내용 |
| --- | --- |
| region/DistrictController.java | 기존 5자리 검사·서울 경로를 새 계약과 대조. H10 형식 검사는 DB 트랜잭션 진입 전에 수행 |
| region/DistrictService.java | district 조회를 게시본 기준 시·도/그룹/읍면동 조회로 전환 |
| region/DistrictRepository.java · AdministrativeDongRepository.java | 기존 서울 Entity 조회 대신 게시·공식 노드·선택 계층 조인 구현 |
| region/District.java · AdministrativeDong.java | 기존 서울 테이블 매핑이다. 같은 Entity의 ID만 H10으로 바꿔 기존 통계 FK를 깨뜨리지 않음 |
| region/DistrictItem.java · AdministrativeDongItem.java | 5/8자리·서울 전용 필드와 LEGACY 노출 정책을 새 DTO로 분리 또는 명시적으로 개정 |
| businesstype/BusinessTypeService.java · BusinessTypeRepository.java | business_type 조회 대신 게시본에 연결된 승인 소진공 분류본 조회 |
| businesstype/BusinessTypeItem.java | CS 코드 예시·표시명을 소진공 코드/명칭·계약으로 개정 |
| common/response · common/error | data.items, meta.requestId, X-Request-Id 및 기존 예외 처리는 재사용 |
| Controller의 API 명세와 springdoc 설정 | 새 경로·H10·오류·지원 코드 예시 반영. 존재하지 않는 설정 클래스 이름을 전제로 작업하지 않음 |

기존 도메인 패키지를 활용하고, 새 조회 DTO와 조회 전용 Repository를 분리하는 수준이면 충분하다. 복합 PK JPA 매핑이 지나치게 복잡해지면 조회 전용 JDBC도 선택 가능하다. 프로젝트 전체 폴더 재편은 이 작업에 포함하지 않는다.

## 3. 반드시 지킬 조회 기준

### 지역: 게시 포인터 → 승인 버전 → 선택 계층

1. `region_publication.publication_key = 'NATIONWIDE_SELECTION'`에서 현재 `release_id`를 읽는다.
2. 같은 `release_id`의 `region_release`가 APPROVED인지 확인한다.
3. `region_navigation`과 `official_region_node`를 **release_id와 region_code 모두**로 조인한다.
4. 선택 목록은 `selectable = TRUE`, `review_status = 'VERIFIED'`, 공식 상태 CURRENT 및 유효한 날짜 품질 조건을 만족하는 노드로 제한한다.
5. 시·도는 `selection_role = 'PROVINCE'`, 그룹은 GROUP, 읍면동은 LOCALITY로 구분한다.
6. 하위 선택 목록은 `selection_parent_code`를 사용한다. `hierarchy_parent_code`는 공식 계층으로, 화면 선택 계층과 다를 수 있다.
7. 이름순·동일 이름 코드순 등 정렬 규칙을 명세에 고정하고 테스트한다.

코드 앞자리 자르기, 이름 유사도, 임의의 8→10자리 0 채우기로 부모·대응을 만들지 않는다. source_region_reference·region_stat_mapping은 통계 원천 대응용이며 화면 목록의 직접 마스터가 아니다.

출장소 76개는 삭제하지 않는다. BLOCKED/NONE/선택 불가 상태를 목록에서 제외한다. 기존 서울 LEGACY 3개를 전국 현행 선택 목록에 합치지 않는다.

### 업종: 지역 게시본에 연결된 분류본 사용

현재는 별도 업종 게시 포인터가 없다. `region_release.validation_report`의 **classificationId**를 읽어 `national_industry_release`와 연결한다. JSON은 Java에서 파싱하거나 검증한 조회 표현식을 사용한다.

- 분류본 상태 APPROVED 확인.
- 같은 classification_id의 `national_industry_code` 중 SMALL, is_supported=TRUE, support_release_status=APPROVED만 반환.
- 정렬은 display_order, industry_code 순.
- 화면명은 display_name 사용. 원천명 industry_name과 구분.
- MAX(classification_id), 최신 수집일, 전체 승인 분류본 합집합으로 선택하지 않는다.
- classificationId 누락·깨진 참조를 다른 분류본으로 조용히 대체하지 않는다.
- 승인된 분류본의 지원값을 API에서 UPDATE하지 않는다. 지원 정책 변경은 새 분류본·승인·게시 절차로 처리한다.

| 소진공 소분류 코드 | 현재 게시본 지원명 |
| --- | --- |
| I21201 | 카페 |
| I21007 | 김밥/만두/분식 |
| I21001 | 빵/도넛 |

기존 CS100010/CS100008/CS100005와 동일 코드 체계가 아니다. 통계·AI 요청에 옛 코드를 그대로 전달하거나 일대일 대응을 추정하지 않는다. 목록 제공은 해당 업종의 전국 통계·AI 분석 제공 완료를 뜻하지 않는다.

### 한 응답에서 같은 버전 사용

요청 시작에 선택한 release_id와 classification_id를 조회 흐름 전체에 전달한다. 여러 쿼리가 각각 현재 포인터를 다시 읽어 서로 다른 버전을 섞지 않도록 한다. 서로 다른 요청의 버전 일치는 보장하지 않는다. 현재 H10의 역할·소속·상태를 매 요청 검증하며 공개 버전 토큰·불일치 409를 추가하지 않는다.

## 4. 확정된 API 계약과 남은 구현 세부

지역 목록은 [전국 지역 선택 API 계약 v0.3](https://app.notion.com/p/4122114f823a81988d0ee0c9bf88c5bb)과 [H10 합의 기록](https://app.notion.com/p/4122114f823a81568c42e7fa899192ef)을 따른다. **아래 확정 사항을 다시 협의하거나 다른 경로로 구현하지 않는다.**

| 확정 항목 | 적용 기준 |
| --- | --- |
| 시·도 목록 | GET /api/regions/provinces |
| 그룹 목록 | GET /api/regions/groups?provinceCode={H10} |
| 읍면동 목록 | GET /api/regions/localities?groupCode={H10} |
| 공개 지역 필드 | regionCode: 숫자 10자리 문자열 / regionName: 표시명 |
| 공통 응답 | data.items + meta.requestId, X-Request-Id 유지 |
| 공개 선택 버전 | regionVersion·selectionRegionVersion 요청·응답은 도입 보류. 버전 불일치 409·재선택 강제 제외 |
| 내부 버전 | 게시 release_id/release_version·원천 대응·승인·게시 이력 유지 |
| 현행·과거 | 기본 목록은 현행만. LEGACY는 별도 과거 조회에서 경고 후 선택·통계 허용 |
| 통계 없는 지역 | 지역 선택 허용. 실제 통계·AI 지원 여부는 별도 판정 |
| 기존 서울 경로 | FE 전환 후 공통 경로로 대체. 영구 이중 서비스 구조로 유지하지 않음 |
| 출장소 | 확정된 76개 노드는 보존하고 초기 선택 목록에서 제외 |

provinceCode와 groupCode는 **쿼리 파라미터**다. 이 인계서의 이전 중첩 경로 제안은 잘못된 기록으로 대체했다. 내부 게시 버전이 필요하다는 이유로 FE에 버전 토큰을 요구하거나 응답에 공개 regionVersion을 추가하지 않는다.

| 아직 남은 세부 항목 | 처리 기준 |
| --- | --- |
| 보조 응답 필드 | 기존 계약의 selectionLevel·parentCode·autoSelect 및 출처 meta 구성을 검토. parentRegionCode·selectionRole 등 별도 공개 필드를 임의로 새로 만들지 않음 |
| 입력·오류 세부 | v0.3의 누락·중복·미정의 쿼리 처리와 REFERENCE_DATA_NOT_READY 신규 오류안을 기준으로 T05/T02와 최종 반영 확인 |
| 자동 선택 동작 | auto_select_group을 화면에서 적용할 조건과 동작 검수 |
| 과거 조회 | 별도 경로·검색·말소된 부모 처리 상세 |
| 전환 배포 | 기존 서울 API 폐기 원칙은 확정. 실제 배포일·전환 기간·폐기 응답은 구현 계획에서 정함 |
| 업종 코드 전환 | 기존 H10 합의의 T10은 CS 코드 유지·회귀 검사였으나, 이후 전국 원천 설계와 게시 DB에는 소진공 분류본이 추가됨. DB 게시가 공개 업종 계약 승인까지 의미하지 않음 |

**T10 주의:** 게시된 소진공 분류본을 읽는 구현안은 준비할 수 있지만, /api/business-types의 반환 코드를 CS → I 코드로 바꾸는 것은 별도 호환성 변경이다. 최신 T02·FE/AI 소비 계약과 대조해 전환을 확정한 뒤 공개한다. H10 전환만을 근거로 업종 코드를 바꾸거나 두 체계의 일대일 대응을 추정하지 않는다.

## 5. 구현 순서와 PR 범위

| 순서 | 작업 | 완료 기준 |
| --- | --- | --- |
| 1 | 확정 v0.3 적용 및 남은 세부 확인 | 확정 경로·H10·공개 버전 보류 적용. 보조 필드·오류·T10 코드 전환만 확인 |
| 2 | 게시본 조회 공통 코드 | 승인 release_id·classification_id 읽기 및 준비 상태 구분 |
| 3 | T08 시·도·그룹 조회 | 선택 계층·정렬·제외 정책 반영 |
| 4 | T09 읍면동 조회 | H10 사전 검사·부모 존재/역할·소속 검증 |
| 5 | T10 업종 계약 대조 후 조회 구현 | 공개 코드 전환 확정 시 게시 분류본의 승인 SMALL 지원 업종 반환 |
| 6 | 회귀·통합 테스트 및 문서 | 아래 검수표 통과·Swagger/FE 안내 동기화 |

T06/T07 변경이 develop에 아직 없으면 먼저 해당 PR 병합 상태를 확인한다. 그 후 최신 develop에서 예: `feature/nationwide-reference-api` 브랜치를 만든다. 미병합 기능 브랜치 내용을 중복 복사하지 않는다. 이 문서 작성 시점에 새 API 코드 수정·PR 생성·병합은 수행하지 않았다.

## 6. 검수표

| 구분 | 필수 사례 |
| --- | --- |
| 게시 기준 | 미게시 READY가 최신이어도 노출 안 됨, 여러 승인본 중 실제 게시본만 사용 |
| 지역 필터 | 선택 불가·미검토·출장소·LEGACY 제외, 다른 release의 노드 혼합 방지 |
| 계층 | 공식 부모와 선택 부모가 다른 사례, 중복 명칭, 시 경유 그룹, 자동 선택 그룹 |
| 입력 | 9/11자리·문자·공백 H10 → DB 호출 전 400. DB 장애여도 잘못된 형식은 400 |
| 부모·빈 결과 | 없는/비선택/잘못된 역할 부모와 정상 부모의 하위 0건 구분 |
| 업종 | 다른 승인 분류본·미지원·대/중분류 제외, 실제 게시본의 3개 및 정렬 확인 |
| 게시 전환 | 게시본 변경/롤백 후 새 요청 반영, 한 응답의 버전 혼합 방지 |
| 장애·공통 | DB 오류는 503, 공통 래퍼·요청 ID 일치, 405 Allow 등 기존 처리 회귀 |
| 회귀 | 기존 health·서울 API의 유지/폐기 계약에 맞는 동작, V1~V4 및 기존 데이터 보존 |
| 문서 | Swagger 실제 경로·필드·400/404/503 및 합의한 준비 상태 오류, FE 예시 일치 |

기존 테스트 출발점: DistrictApiTests, BusinessTypeApiTests, BusinessTypeDatabaseFailureTests. 전국 조회 테스트는 격리 MySQL에서 게시본·후보본·타 분류본을 함께 준비해 필터 누락을 잡는다. 일반 테스트는 작은 fixture로 작성하고, 16/256/3565·3개는 별도 실제 스냅샷 검수에 사용한다.

코드 수정 후 전체 `gradlew.bat clean check bootJar` 실행. T07과 비교하는 최신 기준은 Java 234건·Python 16건이지만, 새 테스트가 늘면 실제 실행 건수를 기록한다. 테스트만으로 FE 연결 완료를 표시하지 않는다.

## 7. 범위와 근거

**이번 범위:** 기준정보 목록 조회와 계약·문서·테스트. 통계 적재, 월별 지표, 지도 경계/마커, AI 분석은 별도 작업이다. 지역 선택 가능 여부와 특정 월·업종의 분석 데이터 존재 여부는 분리한다.

- [현재 서울 API](reference-api.md)
- [현재 FE 지원 안내](frontend-api-guide.md)
- [공통 응답](common-response.md)
- [T06 전국 기준정보 구조](../database/nationwide-reference-schema.md)
- [T07 최종 승인·게시 근거](../data/national-reference-completion.md)
- [T07 쓰기 경로](../../src/main/java/com/pickplace/backend/reference/nationwide/NationalReferencePublisher.java)
