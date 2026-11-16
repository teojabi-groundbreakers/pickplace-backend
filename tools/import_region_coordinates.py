"""검토용 지도 좌표를 현재 게시된 지역 기준정보에 연결해 저장한다."""

import argparse
import json
import math
import os
import re
from collections import Counter
from pathlib import Path

import pymysql


MISSING = {"MISSING_BOUNDARY", "DUPLICATE_BOUNDARY", "INVALID_BOUNDARY", "REGION_MISMATCH"}


def validate_input(data):
    if data.get("status") != "REVIEW_ONLY" or data.get("outputCrs") != "EPSG:4326":
        raise ValueError("검토용 WGS84 자료만 적재할 수 있습니다.")
    for key in ("snapshotSha256", "boundarySha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", data.get(key, "")):
            raise ValueError(f"해시가 올바르지 않습니다: {key}")
    if not all(isinstance(data.get(k), str) and data[k].strip() for k in
               ("boundaryDate", "sourceUrl", "licenseUrl", "attribution", "method")):
        raise ValueError("자료 출처 또는 계산 정보가 없습니다.")
    rows = data.get("records")
    if not isinstance(rows, list) or not rows:
        raise ValueError("지역 좌표가 없습니다.")
    codes = set()
    for row in rows:
        code, name, status = row.get("regionCode"), row.get("name"), row.get("status")
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{10}", code) or code in codes:
            raise ValueError(f"잘못되거나 중복된 지역 코드: {code}")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"지역명이 없습니다: {code}")
        codes.add(code)
        lat, lon = row.get("latitude"), row.get("longitude")
        if status == "BOUNDARY_INTERIOR":
            if (isinstance(lat, bool) or isinstance(lon, bool) or not isinstance(lat, (float, int))
                    or not isinstance(lon, (float, int)) or not math.isfinite(lat) or not math.isfinite(lon)
                    or not 32 <= lat <= 39.5 or not 124 <= lon <= 132):
                raise ValueError(f"좌표가 올바르지 않습니다: {code}")
        elif status not in MISSING or lat is not None or lon is not None:
            raise ValueError(f"좌표 상태가 올바르지 않습니다: {code}")
    if data.get("counts") != dict(Counter(row["status"] for row in rows)):
        raise ValueError("좌표 상태별 건수가 일치하지 않습니다.")
    return rows


def import_coordinates(connection, data):
    rows = validate_input(data)
    with connection.cursor() as cursor:
        cursor.execute("SELECT release_id FROM region_publication WHERE publication_key = 'NATIONWIDE_SELECTION' FOR UPDATE")
        publication = cursor.fetchone()
        if not publication:
            raise ValueError("게시된 지역 기준정보가 없습니다.")
        release_id = publication[0]
        cursor.execute("""SELECT region_code, display_name FROM region_navigation
                          WHERE release_id = %s AND selectable = TRUE AND node_kind IN ('DONG','EUP','MYEON')""", (release_id,))
        published = dict(cursor.fetchall())
        incoming = {row["regionCode"]: row["name"] for row in rows}
        if published != incoming:
            raise ValueError(f"게시된 지역 코드·이름과 입력이 다릅니다 (DB {len(published)}건, 입력 {len(incoming)}건).")
        cursor.execute("""SELECT batch_id FROM region_map_coordinate_batch
                          WHERE region_release_id=%s AND snapshot_sha256=%s AND boundary_sha256=%s""",
                       (release_id, data["snapshotSha256"], data["boundarySha256"]))
        if cursor.fetchone():
            raise ValueError("같은 지역 게시본과 원본의 좌표 배치가 이미 있습니다. 기존 배치를 확인하세요.")
        cursor.execute("""INSERT INTO region_map_coordinate_batch
            (region_release_id, snapshot_sha256, boundary_sha256, boundary_date, source_url, license_url,
             attribution, coordinate_crs, calculation_method, status, created_at)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'REVIEW_ONLY',UTC_TIMESTAMP(6))""",
            (release_id, data["snapshotSha256"], data["boundarySha256"], data["boundaryDate"],
             data["sourceUrl"], data["licenseUrl"], data["attribution"], data["outputCrs"], data["method"]))
        batch_id = cursor.lastrowid
        cursor.executemany("""INSERT INTO region_map_coordinate
            (batch_id, region_release_id, region_code, status, latitude, longitude)
            VALUES (%s,%s,%s,%s,%s,%s)""", [
                (batch_id, release_id, row["regionCode"], row["status"], row["latitude"], row["longitude"])
                for row in rows
            ])
        cursor.execute("SELECT status, COUNT(*) FROM region_map_coordinate WHERE batch_id=%s GROUP BY status", (batch_id,))
        if dict(cursor.fetchall()) != data["counts"]:
            raise ValueError("저장된 좌표 건수가 입력과 다릅니다.")
    connection.commit()
    return batch_id, release_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3307)
    parser.add_argument("--database", default="pickplace")
    parser.add_argument("--user", default="pickplace_app")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        parser.error("MYSQL_PASSWORD 환경 변수가 필요합니다.")
    data = json.loads(args.input.read_text(encoding="utf-8-sig"))
    connection = pymysql.connect(host=args.host, port=args.port, user=args.user, password=password,
                                 database=args.database, charset="utf8mb4", autocommit=False)
    try:
        batch_id, release_id = import_coordinates(connection, data)
        print(f"검토용 좌표 적재 완료: batch={batch_id}, region_release={release_id}, counts={data['counts']}")
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
