"""Prepare separate June and September national store aggregate imports for T23."""
import argparse
import csv
import io
import json
import os
import re
import zipfile
from collections import Counter
from datetime import datetime
from pathlib import Path

from prepare_seoul_detail import read, receipt, save, sha


def jurisdictions(path, date):
    result = {}
    with zipfile.ZipFile(path) as archive:
        member = next(name for name in archive.namelist() if Path(name).name.lower() == f"kikmix.{date}")
        for line in archive.read(member).splitlines()[1:]:
            if not line.strip():
                continue
            match = re.match(rb"^(\d{10}) .{30} .{30} .{30} (\d{10}) ", line)
            if not match:
                raise ValueError(f"행정안전부 관할표 형식 오류: {path}")
            region, legal = (part.decode("ascii") for part in match.groups())
            result.setdefault(region, set()).add(legal)
    return result


def candidates(snapshot, before, after):
    nodes = {node["code"]: node for node in snapshot["nodes"]}
    sources = {(source["code"], source["version"]): source for source in snapshot["sources"]
               if source["system"] == "SEMAS_STORE" and source["referenceOn"] == "2026-09-30"}
    result = {}
    for mapping in snapshot["mappings"]:
        if (mapping["system"] != "SEMAS_STORE" or mapping["referenceOn"] != "2026-09-30"
                or mapping["reviewStatus"] != "VERIFIED"):
            continue
        code, target = mapping["sourceCode"], mapping["targetCode"]
        if target not in before or before[target] != after.get(target):
            continue
        node = nodes[target]
        source = sources[code, mapping["sourceVersion"]]
        if code in result:
            raise ValueError(f"검증 대응표 중복: {code}")
        result[code] = (node, source)
    return result


def count_stores(path, month, regions, industries):
    ids, covered, counts = set(), set(), Counter()
    fields = ("상가업소번호", "행정동코드", "행정동명", "시군구코드",
              "상권업종대분류코드", "상권업종중분류코드", "상권업종소분류코드", "상권업종소분류명")
    with zipfile.ZipFile(path, metadata_encoding="cp949") as archive:
        members = [member for member in archive.infolist() if member.filename.lower().endswith(".csv")]
        if not members or any(not item.filename.endswith(f"_{month}.csv") for item in members):
            raise ValueError(f"월별 CSV 목록 불일치: {path}")
        for member in members:
            with archive.open(member) as entry, io.TextIOWrapper(entry, encoding="utf-8-sig", newline="") as stream:
                reader = csv.reader(stream, strict=True)
                header = next(reader)
                indexes = [header.index(name) for name in fields]
                for row in reader:
                    if len(row) != len(header):
                        raise ValueError(f"열 개수 불일치: {member.filename}, {reader.line_num}")
                    store, code, name, district, large, middle, small, industry_name = (row[index] for index in indexes)
                    if not store or store in ids or not re.fullmatch(r"[0-9]{8}", code) or not small:
                        raise ValueError(f"업소 중복 또는 원천 키 오류: {member.filename}, {reader.line_num}")
                    ids.add(store)
                    if code not in regions:
                        continue
                    source = regions[code][1]
                    if (name, district) != (source["name"], source["districtCode"]):
                        raise ValueError(f"승인 행정동 명칭·상위 지역 불일치: {code}")
                    covered.add(code)
                    if small in industries:
                        if (large, middle, industry_name) != industries[small]:
                            raise ValueError(f"업종 계층·명칭 불일치: {small}")
                        counts[code, small] += 1
    return len(ids), covered, counts


def prepare(args):
    inputs = [("202606", "20262", args.june), ("202609", "20263", args.september)]
    receipts = [receipt(path) for _, _, path in inputs]
    for (month, _, _), meta in zip(inputs, receipts):
        if not meta["datasetInfo"].endswith(month + "30"):
            raise ValueError("원본 기준일이 명세와 다릅니다.")
    snapshot = read(args.reference)
    if receipts[1]["sha256"] != snapshot["classificationSourceSha256"]:
        raise ValueError("9월 원본과 승인 업종 분류가 일치하지 않습니다.")
    before, after = jurisdictions(args.june_regions, "20260325"), jurisdictions(args.september_regions, "20260930")
    regions = candidates(snapshot, before, after)
    classes = {item["code"]: item for item in snapshot["industries"]}
    industries = {code: (classes[item["parentCode"]]["parentCode"], item["parentCode"], item["name"])
                  for code, item in classes.items() if item["level"] == "SMALL" and item["supported"]}
    scans = [count_stores(path, month, regions, industries) for month, _, path in inputs]
    common = sorted(scans[0][1] & scans[1][1])
    if not common or not industries:
        raise ValueError("공통 수록 지역 또는 지원 업종이 없습니다.")
    ref_hash = sha(args.reference)
    official_hashes = {"20260325": sha(args.june_regions), "20260930": sha(args.september_regions)}
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    mapping_file = out / "region-mappings.json"
    mappings = [
        {"sourceCode": code, "sourceName": regions[code][0]["locality"], "targetCode": regions[code][0]["code"],
         "evidence": json.dumps({"rule": "SAME_OFFICIAL_JURISDICTION_AND_BOTH_SOURCE_COVERAGES",
                                 "officialJurisdictionSha256": official_hashes})}
        for code in common
    ]
    save(mapping_file, {"referenceVersion": snapshot["version"], "referenceSha256": ref_hash, "regions": mappings})
    catalog = []
    for (month, quarter, path), meta, (source_rows, _, counts) in zip(inputs, receipts, scans):
        raw = out / f"semas-{month}.jsonl"
        with raw.open("w", encoding="utf-8", newline="\n") as stream:
            for code in common:
                for industry in sorted(industries):
                    row = {"stdr_yyqu_cd": quarter, "adstrd_cd": code, "adstrd_cd_nm": regions[code][0]["locality"],
                           "svc_induty_cd": industry, "listed_store_count": counts[code, industry]}
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
        digest = sha(raw)
        name = f"semas-{month}.manifest.json"
        rows = len(common) * len(industries)
        manifest = {
            "version": "regional-detail-manifest-v1", "sourceSystem": "SEMAS_STORE", "datasetCode": "STORE_SNAPSHOT",
            "adapter": "semas-store-quarter-v1", "periodType": "QUARTER", "periods": [quarter],
            "regionScheme": "SEMAS_ADSTRD8", "industryScheme": "SEMAS_SMALL",
            "referenceVersion": snapshot["version"], "referenceSha256": ref_hash,
            "mappingFile": mapping_file.name, "mappingSha256": sha(mapping_file),
            "sourceUrl": meta["url"], "collectedAt": datetime.fromisoformat(meta["downloadedAt"]).isoformat().replace("+00:00", "Z"),
            "rawFile": raw.name, "rawSha256": digest, "encoding": "UTF-8", "expectedRows": rows, "aliases": {},
            "metrics": [{"field": "listed_store_count", "unit": "COUNT", "aggregation": "DISTINCT_STORE_ID", "definitionStatus": "VERIFIED"}],
            "metadata": {"original": {"path": os.path.relpath(path.resolve(), out.resolve()), **meta}, "originalRows": source_rows,
                         "officialJurisdictionSha256": official_hashes, "comparisonInputSha256": [item["sha256"] for item in receipts],
                         "comparisonRegionCount": len(common), "industryCodes": sorted(industries)},
        }
        save(out / name, manifest)
        catalog.append({"manifest": name, "dataset": "STORE_SNAPSHOT", "periods": [quarter], "rows": rows, "sha256": digest})
    save(out / "catalog.json", {"reference": str(args.reference.resolve()), "referenceSha256": ref_hash,
                               "mappingCount": len(common), "files": catalog})
    print(json.dumps({"comparisonRegions": len(common), "supportedIndustries": len(industries),
                      "originalRows": [scan[0] for scan in scans], "aggregateRowsPerQuarter": len(common) * len(industries)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("june", "september", "june-regions", "september-regions", "reference", "output"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    prepare(parser.parse_args())
