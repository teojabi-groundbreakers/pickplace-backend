# 행정동 지도 좌표 검토

`prepare_region_coordinates.py`는 선택 가능한 H10 지역과 동일 코드의 경계를 연결해 지도 표시용 내부점을 만든다. 원본 GeoJSON의 경위도(CRS84)를 EPSG:5179로 변환해 내부점을 계산하고 EPSG:4326 위·경도로 되돌린다. 중심점이나 특정 상권의 위치를 보장하지 않는다.

```powershell
python -m pip install -r tools/requirements-region-coordinates.txt
python tools/prepare_region_coordinates.py `
  --snapshot data/reference/national-202609-release-v1/snapshot.json `
  --boundary <경계-GeoJSON-경로> `
  --boundary-date 2026-10-01 `
  --output data/validated/region-coordinates-review.json
python -m unittest discover -s tools -p test_region_coordinates.py
```

원본은 [vuski/admdongkor의 2026-10-01 경계](https://github.com/vuski/admdongkor/tree/master/ver20261001)를 사용했다. 원본과 결과는 저장소에 포함하지 않는다. 결과에는 경계와 기준정보 파일의 SHA-256, 기준일, 좌표계, 계산 방법, 출처를 기록한다. GeoJSON에 좌표계 선언이 없으면 표준 GeoJSON 경위도로 처리하며, 그 밖의 좌표계는 거부한다.

경계가 없거나 중복·오류인 지역은 좌표를 비워 둔다. 경계에 지역명이나 상위 코드가 있을 경우 기준정보와 다르면 결과를 비워 재검토한다. 결과는 `REVIEW_ONLY`이며 자동으로 DB에 저장하거나 게시하지 않는다. 2026-10-01 경계에서는 분동된 평택 고덕1·2동과 강원 접경 5개 면이 누락된다. 후속 경계 확보 전에는 시설 좌표나 다른 지역 좌표로 임의 대체하지 않는다.

제공처는 원자료 SGIS 출처와 가공자 `vuski/admdongkor` 표시, 가공 부분의 CC BY 4.0 조건을 안내한다. 상세 조건은 [제공처 데이터 이용 조건](https://github.com/vuski/admdongkor/blob/master/LICENSE-DATA)을 확인한다. [SGIS 저작권 정책](https://sgis.mods.go.kr/jsp/member/copyright.jsp)은 해당 자료에 공공누리 표시가 부착됐는지 확인할 것을 요구한다. 원본 경계 파일의 개별 표시는 아직 직접 확인하지 못했다. 서비스에 쓰기 전에 원천 이용 조건 확인 및 출처 표기가 필요하다.

행정안전부의 최신 행정동 경계를 포함하는 [구역의 도형](https://www.data.go.kr/data/15050416/fileData.do)은 별도 신청과 이용목적 심사를 거쳐 제공된다. 고덕1·2동 경계를 이 자료로 확인하려면 자료 확보와 이용 조건 검토가 필요하다.
