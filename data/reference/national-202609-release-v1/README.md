# 전국 기준정보 개발용 적재 묶음

> 새 개발 환경은 [세종 권고를 반영한 최신 묶음](../national-202609-sejong-hint-v1/README.md)을 사용한다. 이 v1은 자동 선택 권고 변경 전의 원본과 재현·비교 이력으로 보존한다.

검증된 `national-202609-release-v1`을 팀원이 동일하게 적재하기 위한 파일이다. 저장소를 클론하면 함께 내려받는다. JAR에는 포함하지 않으며 저장소 루트에서 파일 경로로 전달한다.

| 파일 | 용도 |
| --- | --- |
| snapshot.json | 실제 적재 입력. 공식 지역·선택 계층·원천 대응·소진공 업종 분류 |
| manifest.json | 버전·바이트 수·SHA-256·범위 |
| preparation-report.json | 묶음 생성 당시의 보고서. approved/published=false는 생성 당시 상태이며 각 DB의 실행 결과가 아님 |

SHA-256: `f9b3815bcb4b5f9362dd467191840b8b77b4eed67399fa4ac5ba895f1e9cb371` / 13,931,580 bytes.

인구 수치·개별 업소·원본 ZIP/HWPX·비밀번호는 포함하지 않는다. 원본 파일의 수집 경로와 storage key는 출처 추적 정보다. 기존 JSON 적재에는 원본 재다운로드, Python 또는 원본 보관 폴더가 필요하지 않다. 원본에서 새 버전을 재생성할 때만 별도 원본과 생성 도구가 필요하다.

## 팀원 로컬 실행 — Windows CMD

JDK 25·본인 MySQL·프로젝트의 local 설정과 MYSQL_PASSWORD 환경변수를 준비한다. 아래 명령은 연결된 DB를 변경하므로 본인 로컬 DB 주소·스키마를 먼저 확인한다. 일반 서버 실행만으로 기준정보가 자동 적재되지는 않는다.

```cmd
certutil -hashfile data\reference\national-202609-release-v1\snapshot.json SHA256
gradlew.bat bootJar
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=import --snapshot=data\reference\national-202609-release-v1\snapshot.json --sha256=f9b3815bcb4b5f9362dd467191840b8b77b4eed67399fa4ac5ba895f1e9cb371
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=approve --version=national-202609-release-v1 --actor=local-developer
```

기존 적재 버전과 충돌하면 덮어쓰거나 DB를 초기화하지 말고 버전·해시를 확인한다. 최초 import는 READY로 적재한다. 동일 내용 재적재는 신규 INSERT 없이 기존 결과를 재사용한다. 개발 DB의 release_id=4·classification_id=3은 각 로컬 DB에서 달라질 수 있다.

MySQL에서 현재 게시 상태를 먼저 확인한다.

```sql
SELECT publication_key, release_id, revision FROM region_publication
WHERE publication_key = 'NATIONWIDE_SELECTION';
```

**조회 결과가 0행인 본인 로컬 DB에서 최초 게시하는 경우만** 다음 명령을 사용한다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=publish --version=national-202609-release-v1 --expected-revision=0 --actor=local-developer --reason=local-reference-setup
```

게시 행이 있으면 위 0을 그대로 사용하지 않는다. 현재 release·revision과 필요한 전환 여부를 확인하고 [게시·복구 안내](../../../docs/data/national-reference-import.md)를 따른다.

## 적재 후 확인

```sql
SELECT r.release_version, r.status, p.revision,
       JSON_UNQUOTE(JSON_EXTRACT(r.validation_report, '$.classificationId')) AS classification_id
FROM region_publication p
JOIN region_release r ON r.release_id = p.release_id
WHERE p.publication_key = 'NATIONWIDE_SELECTION';

SELECT n.selection_role, COUNT(*) AS selectable_count
FROM region_publication p
JOIN region_navigation n ON n.release_id = p.release_id
WHERE p.publication_key = 'NATIONWIDE_SELECTION' AND n.selectable = TRUE
GROUP BY n.selection_role;

SELECT c.industry_code, c.display_name
FROM region_publication p
JOIN region_release r ON r.release_id = p.release_id
JOIN national_industry_code c
  ON c.classification_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(r.validation_report, '$.classificationId')) AS UNSIGNED)
WHERE p.publication_key = 'NATIONWIDE_SELECTION' AND c.is_supported = TRUE
ORDER BY c.display_order, c.industry_code;
```

이 버전의 정상 결과는 APPROVED, PROVINCE 16 / GROUP 256 / LOCALITY 3,565, 지원 업종 I21201 / I21007 / I21001이다. 출장소 관련 76개는 보존하지만 선택 대상에서 제외한다.

## 출처·변경 정책

행정안전부 공식 현행/말소 행정기관 코드 및 소상공인시장진흥공단 분류를 바탕으로 PickPlace에서 계층·대응·화면 지원 정책을 가공했다. [공식 출처와 이용조건](../../../docs/data/national-reference-usage.md), [이용 근거 manifest](../../../docs/data/national-reference-usage.json), [승인·게시 검증](../../../docs/data/national-reference-completion.md)을 함께 참고한다.

고정 버전 JSON을 직접 편집하지 않는다. 원천이나 정책 변경은 새 버전·해시·검증 기록을 추가한다. 초기 지원 플래그는 DB 설정이며 CS 코드에서 소진공 코드로의 공개 API 계약 변경을 대신하지 않는다.
