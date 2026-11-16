"""선택 가능한 행정동의 지도 표시용 내부점을 생성한다. DB에는 쓰지 않는다."""

import argparse
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

from pyproj import Transformer
from shapely import transform
from shapely.errors import GEOSException
from shapely.geometry import Point, shape


TO_METERS = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True).transform
TO_WGS84 = Transformer.from_crs("EPSG:5179", "EPSG:4326", always_xy=True).transform


def same_locality_name(reference, boundary_name):
    def normalize(name):
        return re.sub(r"[·.,\s]", "", name)

    actual = normalize(boundary_name.split()[-1])
    return actual in (normalize(reference), normalize(re.sub(r"제(?=\d)", "", reference)))


def build_records(snapshot, boundary):
    if boundary.get("type") != "FeatureCollection" or not isinstance(boundary.get("features"), list):
        raise ValueError("경계 파일은 GeoJSON FeatureCollection이어야 합니다.")
    crs = boundary.get("crs", {}).get("properties", {}).get("name")
    if crs and crs not in ("urn:ogc:def:crs:OGC:1.3:CRS84", "EPSG:4326", "urn:ogc:def:crs:EPSG::4326"):
        raise ValueError(f"지원하지 않는 경계 좌표계: {crs}")
    regions = [r for r in snapshot["navigation"]
               if r["kind"] in ("DONG", "EUP", "MYEON") and r["selectable"]]
    if not regions or len({r["code"] for r in regions}) != len(regions):
        raise ValueError("선택 지역이 없거나 지역 코드가 중복됩니다.")

    features = defaultdict(list)
    for feature in boundary["features"]:
        code = feature.get("properties", {}).get("adm_cd2")
        if code:
            features[str(code)].append(feature)

    records = []
    for region in regions:
        code = region["code"]
        if len(code) != 10 or not code.isascii() or not code.isdigit():
            raise ValueError(f"잘못된 H10 코드: {code}")
        row = {"regionCode": code, "name": region["displayName"], "latitude": None, "longitude": None}
        candidates = features[code]
        if len(candidates) != 1:
            row["status"] = "MISSING_BOUNDARY" if not candidates else "DUPLICATE_BOUNDARY"
        else:
            properties = candidates[0].get("properties", {})
            boundary_name = properties.get("adm_nm")
            row["boundaryName"] = boundary_name
            if ((properties.get("sgg") and properties["sgg"] != code[:5])
                    or (properties.get("sido") and properties["sido"] != code[:2])
                    or (boundary_name and not same_locality_name(region["displayName"], boundary_name))):
                row["status"] = "REGION_MISMATCH"
                records.append(row)
                continue
            try:
                geometry = shape(candidates[0]["geometry"])
                if geometry.geom_type not in ("Polygon", "MultiPolygon") or geometry.is_empty or not geometry.is_valid:
                    raise ValueError("유효하지 않은 폴리곤")
                projected = transform(geometry, TO_METERS, interleaved=False)
                point = projected.representative_point()
                lon, lat = TO_WGS84(point.x, point.y)
                if (not math.isfinite(lat) or not math.isfinite(lon)
                        or not 32 <= lat <= 39.5 or not 124 <= lon <= 132
                        or not geometry.covers(Point(lon, lat))):
                    raise ValueError("좌표 범위 또는 경계 내부 검증 실패")
                row.update(status="BOUNDARY_INTERIOR", latitude=lat, longitude=lon)
            except (KeyError, TypeError, ValueError, OverflowError, GEOSException):
                row["status"] = "INVALID_BOUNDARY"
        records.append(row)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--boundary", type=Path, required=True)
    parser.add_argument("--boundary-date", required=True, help="경계 기준일 (YYYY-MM-DD)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    from datetime import date
    date.fromisoformat(args.boundary_date)
    if args.output.resolve() in (args.snapshot.resolve(), args.boundary.resolve()):
        parser.error("출력 파일은 입력 파일과 달라야 합니다.")
    source_bytes = args.boundary.read_bytes()
    snapshot_bytes = args.snapshot.read_bytes()
    snapshot = json.loads(snapshot_bytes.decode("utf-8-sig"))
    records = build_records(snapshot, json.loads(source_bytes.decode("utf-8-sig")))
    result = {
        "status": "REVIEW_ONLY", "regionRelease": snapshot["version"],
        "snapshotSha256": hashlib.sha256(snapshot_bytes).hexdigest(),
        "boundaryDate": args.boundary_date, "boundarySha256": hashlib.sha256(source_bytes).hexdigest(),
        "sourceCrs": "EPSG:4326", "calculationCrs": "EPSG:5179", "outputCrs": "EPSG:4326",
        "method": "projected_polygon_representative_point", "counts": dict(Counter(r["status"] for r in records)),
        "attribution": "SGIS; boundary processing: vuski/admdongkor; coordinate calculation: PickPlace",
        "sourceUrl": "https://github.com/vuski/admdongkor", "licenseUrl": "https://github.com/vuski/admdongkor/blob/master/LICENSE-DATA",
        "records": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["counts"], ensure_ascii=False))


if __name__ == "__main__":
    main()
