"""보유한 서울 분기별 상주인구 원본의 적재 명세를 만든다. DB에는 쓰지 않는다."""

import argparse
import json
import os
from pathlib import Path

from prepare_seoul_detail import collected, read, receipt, save, sha


def generate(data: Path, reference: Path, output: Path):
    snapshot = read(reference)
    nodes = {node["code"]: node for node in snapshot["nodes"]}
    regions = []
    seen = set()
    for mapping in snapshot["mappings"]:
        node = nodes[mapping["targetCode"]]
        if mapping["system"] != "SEMAS_STORE" or mapping["reviewStatus"] != "VERIFIED" or node["province"] != "서울특별시":
            continue
        if mapping["sourceCode"] in seen:
            raise ValueError("중복된 원천 지역 코드")
        seen.add(mapping["sourceCode"])
        regions.append({"sourceCode": mapping["sourceCode"], "sourceName": node["locality"],
                        "targetCode": node["code"], "evidence": json.dumps(
                            {"referenceMapping": mapping, "rule": "EXISTING_VERIFIED_MAPPING_AND_OFFICIAL_LOCALITY_NAME"}, ensure_ascii=False)})
    if not regions:
        raise ValueError("검토된 서울 지역 대응이 없습니다")
    output.mkdir(parents=True, exist_ok=True)
    mapping_file = output / "region-mappings.json"
    save(mapping_file, {"referenceVersion": snapshot["version"], "referenceSha256": sha(reference), "regions": regions})
    paths = sorted((data / "분기이력JSON").glob("resident-*.json"))
    paths = [p for p in paths if not p.name.endswith((".meta.json", ".metadata.json"))]
    paths.append(data / "분기별JSON" / "resident-20262.json")
    files = []
    periods_seen = set()
    for path in paths:
        meta = receipt(path)
        raw = read(path)
        rows = raw.get("DATA")
        if not isinstance(rows, list) or not rows:
            raise ValueError(f"빈 DATA: {path}")
        periods = {str(row.get("stdr_yyqu_cd", "")) for row in rows}
        if len(periods) != 1 or not all(len(p) == 5 and p[:4].isdigit() and p[4] in '1234' for p in periods):
            raise ValueError(f"잘못된 분기: {path}")
        period = next(iter(periods))
        if period in periods_seen or period not in path.stem:
            raise ValueError(f"중복 또는 파일명과 다른 분기: {path}")
        periods_seen.add(period)
        if len(rows) != int(meta.get("expectedRows", meta.get("downloadedRows", len(rows)))):
            raise ValueError(f"원본 건수 불일치: {path}")
        aliases = {}
        for key, label in raw["DESCRIPTION"].items():
            for alias in (key, key.lower(), label):
                if alias in aliases and aliases[alias] != key.lower():
                    raise ValueError(f"모호한 필드: {alias}")
                aliases[alias] = key.lower()
        if "tot_repop_co" not in aliases.values():
            raise ValueError(f"총 상주인구 필드 없음: {path}")
        manifest = {"version": "regional-detail-manifest-v1", "sourceSystem": "SEOUL_COMMERCIAL",
                    "datasetCode": "RESIDENT", "adapter": "seoul-dong-v1", "periodType": "QUARTER",
                    "periods": [period], "regionScheme": "SEOUL_ADSTRD8", "industryScheme": "NONE",
                    "referenceVersion": snapshot["version"], "referenceSha256": sha(reference),
                    "mappingFile": mapping_file.name, "mappingSha256": sha(mapping_file),
                    "sourceUrl": "https://data.seoul.go.kr/dataList/OA-22183/S/1/datasetView.do",
                    "collectedAt": collected(meta), "rawFile": os.path.relpath(path.resolve(), output.resolve()).replace("\\", "/"),
                    "rawSha256": sha(path), "encoding": "UTF-8", "expectedRows": len(rows), "aliases": aliases,
                    "metrics": [{"field": "tot_repop_co", "unit": "PERSON", "aggregation": "SOURCE_DEFINED",
                                 "definitionStatus": "SOURCE_ONLY"}], "metadata": meta}
        name = f"resident-{period}.manifest.json"
        save(output / name, manifest)
        files.append({"manifest": name, "dataset": "resident", "periods": [period], "rows": len(rows), "sha256": manifest["rawSha256"]})
    catalog = {"reference": str(reference.resolve()), "referenceSha256": sha(reference), "mappingCount": len(regions), "files": files}
    save(output / "catalog.json", catalog)
    return {"files": len(files), "sourceRows": sum(file["rows"] for file in files), "mappingCount": len(regions)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.data_dir, args.reference, args.output), ensure_ascii=False))
