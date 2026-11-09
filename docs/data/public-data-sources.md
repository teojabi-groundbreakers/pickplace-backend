# 공공데이터 수집 대상·다운로드 경로

> 아래는 서울 분기 자료 수집 이력이다. 전국 인구·업소 원본의 현재 경로·기간·검증은 [T12 전국 수집](national-data-download.md)을 따른다.

> T12 1단계 완료 · 확인일 2026-11-05 · 초기 대상 분기 20262
>
> 초기 수집 설정과 실제 요청 근거를 정리한 문서다. 핵심 4종 다운로드는 [공공데이터 수동 CLI](public-data-command.md)에서 실행한다.

## 수집 대상

| 데이터 | 공식 원천 | 고유 단위 | 초기 수집 경로 | 이번 확인 |
|---|---|---|---|---|
| 점포 | [OA-22172](https://data.seoul.go.kr/dataList/OA-22172/S/1/datasetView.do) | 행정동×업종×분기 | 업종별 분할 JSON | 대표 3업종 경로 확인. 전체 100업종 수집은 미수행 |
| 상주인구 | [OA-22183](https://data.seoul.go.kr/dataList/OA-22183/S/1/datasetView.do) | 행정동×분기 | 분기 필터 JSON | 425행 다운로드·조회 총건수·고유키 일치 |
| 직장인구 | [OA-22184](https://data.seoul.go.kr/dataList/OA-22184/S/1/datasetView.do) | 행정동×분기 | 분기 필터 JSON | 414행 다운로드·조회 총건수·고유키 일치 |
| 집객시설 | [OA-22169](https://data.seoul.go.kr/dataList/OA-22169/S/1/datasetView.do) | 행정동×분기 | 분기 필터 JSON | 425행 다운로드·조회 총건수·고유키 일치 |

- 초기 분기는 **20262(2026년 2분기)**로 고정한다. 이번 정렬된 최신 미리보기에서 4종 모두 20262가 반환됐다. 실행 시점의 최신 분기는 별도 재확인한다.
- 매출·길단위인구, 최근 8분기 전체 수집은 이번 첫 연결 범위에 포함하지 않는다.
- 점포 수집의 원천 업종은 전체 100개다. 화면에서 선택하는 지원 업종 3개로 수집 대상을 제한하지 않는다.
- 직장인구는 공식 조회부터 414행이다. 행정동 기준정보 425개에 맞추기 위해 없는 행을 만들거나 0으로 채우지 않는다.

## 확정 파일

| 파일 | 용도 |
|---|---|
| [수집 경로 카탈로그](public-data-source-catalog.json) | 데이터셋·요청 조건·수집 방식·완료 판정의 참조 설정 |
| [실제 요청 검증 기록](public-data-source-verification.json) | URL·요청 조건·UTC 수집 시각·HTTP 상태·크기·SHA-256·분기·행 수 |
| [기준정보 스냅샷](../../src/main/resources/reference-data/seoul-reference.json) | 점포 분할 대상인 전체 업종 코드의 초기 목록 |
| [기존 원천 manifest](reference-source-manifest.json) | 2026-10-15~16 기준정보 검증의 고정 근거 |

카탈로그는 **1단계 조사 기준을 고정한 JSON**이다. 2단계 CLI는 여기서 필요한 값을 추린 [실행 카탈로그](../../src/main/resources/data-download/source-catalog.json)를 읽는다. 실제 명령과 현재 범위는 [수동 CLI 안내](public-data-command.md)를 참고한다.

## 점포의 수집 단위

| 업종 코드 | 이름 | 이번 20262 응답 행 수 | 대상 분기 고유키 중복 |
|---|---|---:|---:|
| CS100010 | 커피·음료 | 425 | 0 |
| CS100008 | 분식 | 425 | 0 |
| CS100005 | 제과점 | 421 | 0 |

이번 공식 분기 조회 총건수는 **35,301행**이다. 이번에 확인한 것은 3개 분할 응답의 합계 1,271행이므로 전체 수집 완료로 기록하지 않는다.

분할 응답에는 20251~20262의 여러 분기가 포함됐다. 원본은 받은 바이트 그대로 보관하고, 대상 분기 추출은 검사/T13에서 수행한다. 초기 완료 판정은 100개 원천 업종을 모두 받은 뒤 20262의 합계 행 수·고유키 수·분할 업종·기준 분기를 공식 조회 결과와 대조하는 것이다. 총건수 35,301은 이번 관찰값이며 향후 실행에 상수로 고정하지 않는다.

<details>
<summary><strong>JSON 다운로드 요청</strong></summary>

공통 경로:

```text
POST https://datafile.seoul.go.kr/bigfile/iot/sheet/json/download.do
Content-Type: application/x-www-form-urlencoded
```

| 공통 항목 | 값 |
|---|---|
| infId | 각 데이터셋의 OA 식별자 |
| srvType / serviceKind / pageNo | S / 0 / 1 |
| ssUserId | SAMPLE_VIEW |
| strWhere | 빈 문자열 |

| 요청 | filterCol | txtFilter | strOrderby |
|---|---|---|---|
| 상주·직장인구·시설 | STDR_YYQU_CD | 20262 | STDR_YYQU_CD DESC, ADSTRD_CD |
| 점포 분할 | SVC_INDUTY_CD | 업종 코드 | STDR_YYQU_CD DESC, ADSTRD_CD, SVC_INDUTY_CD |

인구·시설 요청의 gridTotalCnt는 해당 수집 실행에서 조회한 분기 총건수로 넣는다. 실제 파라미터는 카탈로그와 검증 기록에 남겼다. 비공개 인증키를 사용하지 않고 공개 SHEET 요청의 SAMPLE_VIEW 값으로 확인했다.

이 경로는 열린데이터광장 화면이 사용하는 다운로드 경로다. 문서화된 인증키 기반 Open API와 동일한 계약이라고 가정하지 않는다. 경로·응답이 달라지면 실패로 기록하고 설정과 근거를 다시 확인한다.

</details>

<details>
<summary><strong>조회 총건수·응답 형식 검사</strong></summary>

분기 총건수와 최신 미리보기는 다음 경로로 확인했다.

```text
POST https://data.seoul.go.kr/dataList/dataView.do
```

분기 필터에 STDR_YYQU_CD=20262, onepagerow=1을 전달하고 result·page.totalCount·list의 기준 분기를 확인했다. 최신 미리보기는 분기 필터 없이 STDR_YYQU_CD 내림차순으로 요청했다. 미리보기의 일부 행을 전체 다운로드로 사용하지 않는다.

**구분해야 할 실제 형식**

| 응답 | 실제 관찰 | 구현 시 검사 |
|---|---|---|
| 다운로드 JSON | DESCRIPTION 객체·DATA 배열, DATA 필드는 소문자 | 필수 키·분기·업종·고유키·총건수 |
| 다운로드 Content-Type | application/x-msdownload;charset=UTF-8 | application/json이 아니라는 이유만으로 거부하지 않음. 본문 확인 |
| 미리보기/총건수 | 키 인용부호가 빠진 JavaScript 객체 표기와 끝 쉼표 포함 | 일반 JSON과 구분. 원문 보존·허용 문법을 제한한 파싱 필요 |
| CSV/ZIP | 다운로드 경로·파일 구조가 JSON과 다름 | ZIP 식별·요청 연도·CSV 헤더·인코딩 확인 |

미리보기 원문은 엄격한 JSON이 아니므로 일반 JSON 파서에 그대로 넣거나 응답을 코드로 실행하지 않는다. 이번 조사에서는 기존 검증용 정규화를 사용했으며, 운영 코드의 파싱·오류 테스트는 후속 단계에서 구현한다.

JSON 다운로드는 UTF-8 엄격 디코딩을 확인했다. HTTP 200만으로 성공 처리하지 않고 HTML 오류 페이지·DATA 부재·잘못된 분기 등을 차단해야 한다.

</details>

<details>
<summary><strong>과거 연도 점포 ZIP 경로</strong></summary>

현재 공식 점포 페이지에 2024·2025년 ZIP이 게시돼 있다.

```text
POST https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?&useCache=false
```

요청 연도가 적힌 파일명을 찾고 페이지의 frmFile 입력과 downloadFile 식별자를 읽어 요청한다. seq·infSeq는 현재 페이지에서 발견하는 값이며 상수로 고정하지 않는다.

| 항목 | 이번 확인 |
|---|---|
| 2025 ZIP | 재다운로드 성공 · 1,073,668바이트 · CSV 1개 · 압축 해제 크기 11,525,299바이트 |
| CSV 헤더 | 기준_년분기_코드·행정동_코드·서비스_업종_코드·점포_수 등 확인 |
| CSV 인코딩 | 엄격 UTF-8 해석 실패, EUC-KR 호환 디코딩 성공. 정확한 EUC-KR/MS949 구분은 단정하지 않음 |
| 2024 ZIP | 현재 페이지 게시와 2026-10-16의 과거 검증 기록 확인. 이번에는 재다운로드하지 않음 |
| 용도 | 향후 2024·2025 분기의 경로. 2026년 분기 대신 사용하지 않음 |

CSV의 점포_수는 일반 점포 STOR_CO, 유사_업종_점포_수는 전체 점포 SIMILR_INDUTY_STOR_CO다. 이름만 보고 뒤집지 않는다. 원본 ZIP·CSV 바이트는 보존하고 분기 추출·수치 검사는 T13에서 수행한다.

</details>

<details>
<summary><strong>원본 근거와 재현 범위</strong></summary>

원본·요청 메타데이터는 이 PC의 작업공간 기준 다음 위치에 보관했다.

```text
data-validation/2026-11-05/t12-source-routes/
├─ probe.mjs
├─ route-probe-report.json
└─ 수집 실행 ID/
   ├─ 원본 응답
   └─ 응답별 meta.json
```

이 경로는 팀 공용 다운로드 링크가 아니다. 저장소에는 원본 전체 대신 공개 요청 조건과 해시를 담은 검증 기록만 포함한다. 요청 조건으로 재확인할 수 있지만 공급기관 정정·정렬 변경으로 미래 해시가 달라질 수 있다.

probe.mjs는 수집 경로 조사용이며 Spring 수집 기능을 대체하지 않는다. 실패·타임아웃·재시도·부분 파일 복구에 대한 운영 기능 테스트나 최근 8분기 전체 수집도 이번에 완료한 것은 아니다.

</details>

## 다음 단계의 입력

- 수동 명령에 데이터 종류·분기·원본 저장 경로를 지정한다.
- DB가 꺼져 있어도 다운로드 명령을 실행할 수 있도록 구성한다.
- JSON·점포 분할·연도별 ZIP 전략을 구분하고 저장·메타데이터·완료 판정은 공통으로 둔다.
- data/raw는 Git에서 제외한다. 실제 다운로드 시 원본·메타데이터를 함께 보관한다.
- 숫자 정제·합계 검사는 T13, DB 배치 이력·적재·공개 전환은 T14에서 구현한다.
