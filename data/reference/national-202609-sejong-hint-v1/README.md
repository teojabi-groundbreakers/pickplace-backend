# 전국 기준정보 · 세종 자동 선택 권고

버전: `national-202609-sejong-hint-v1` · 기준 정책 결정일: 2026-11-09.

기존 [national-202609-release-v1](../national-202609-release-v1/README.md)을 보존하고, **세종 그룹 3611000000의 autoSelect만 true로 권고**하는 새 버전이다. 시·도 3600000000과 중간 그룹 H10은 유지한다. 그 외 그룹으로 정책을 확대하지 않는다.

- true: 추가 클릭 없이 해당 항목을 적용하도록 BE가 권고한다.
- false: BE의 별도 권고가 없다는 뜻이며 자동 선택 금지가 아니다.
- 실제 선택 적용·기존 선택 복원·중간 칸 표시·후속 API 요청은 FE 정책이다. DB 게시만으로 FE 동작 완료를 뜻하지 않는다.

## 포함 파일과 변경 범위

| 파일 | 용도 |
| --- | --- |
| snapshot.json | 원본과 동일한 지역·원천·업종 및 세종 권고를 담은 새 적재 입력 |
| manifest.json | 해시·바이트 수·기반 버전·허용한 변경 필드 |

SHA-256: `0b5430750eed5ea951ee9161589e92e07df5fb5beea6c0cc36e651d5982bc1a9` / 13,931,823 bytes.

변경은 snapshot version, 세종 그룹의 autoSelect 및 review evidence에 권고 정책 근거를 추가한 것뿐이다. 원천 기준일 null·수집 시점·분류본·지원3업종·출장소 제외76개·시점 보류·이전 버전은 변경하지 않는다. exclusion policyVersion은 기존 확정 제외 정책을 그대로 가리키며, 새 권고 정책은 세종 evidence의 sejong-auto-select-hint-v1에 기록한다.

## 로컬 적재

프로젝트 루트에서 [환경 설정과 DB 확인](../national-202609-release-v1/README.md)을 마친 뒤 아래 명령을 사용한다. 대상은 본인 로컬 DB인지 확인한다. 원본 재다운로드·Python은 필요하지 않다.

```cmd
certutil -hashfile data\reference\national-202609-sejong-hint-v1\snapshot.json SHA256
gradlew.bat bootJar
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=import --snapshot=data\reference\national-202609-sejong-hint-v1\snapshot.json --sha256=0b5430750eed5ea951ee9161589e92e07df5fb5beea6c0cc36e651d5982bc1a9
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=approve --version=national-202609-sejong-hint-v1 --actor=local-developer
```

게시 포인터가 아직 없는 본인 DB의 최초 게시만 다음 명령을 사용한다.

```cmd
java -jar build\libs\pickplace-backend-0.2.0.jar --spring.profiles.active=local,national-reference --confirm=true --action=publish --version=national-202609-sejong-hint-v1 --expected-revision=0 --actor=local-developer --reason=local-sejong-auto-select-recommendation
```

기존 게시본이 있으면 region_publication의 현재 version·revision을 확인하고 그 revision을 전달한다. 개발 DB의 revision/숫자 ID를 다른 DB에 그대로 복사하지 않는다. 승인된 기존 행을 UPDATE하지 않고 새 버전으로 게시한다. 기존 버전으로의 복구는 [게시·복구 안내](../../../docs/data/national-reference-import.md)를 따른다.

## 데이터 생성·검증 근거

원본부터 다시 만드는 작업이 아니라, 해시가 고정된 기존 최종본에 좁은 정책 변경만 적용한다.

```cmd
python -B scripts\prepare_sejong_auto_select.py --input data\reference\national-202609-release-v1\snapshot.json --output-dir 새로운_출력_폴더
```

출력 폴더가 이미 있으면 덮어쓰지 않는다. [실행 결과](../../../docs/data/sejong-auto-select-release.md)·[기존 출처와 이용조건](../../../docs/data/national-reference-usage.md)을 함께 참고한다. 자료 출처는 행정안전부 공식 행정기관 코드 및 소상공인시장진흥공단 분류이며, 선택 계층과 자동 선택 권고는 PickPlace 가공 정책이다.
