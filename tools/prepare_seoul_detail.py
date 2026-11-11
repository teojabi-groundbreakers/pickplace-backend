"""보유 원본과 metadata를 검증해 T20 적재 명세를 만든다. 다운로드나 DB 쓰기는 하지 않는다."""
import argparse
import csv
import hashlib
import io
import json
import os
import pathlib
import zipfile


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def receipt(path):
    candidates = [path.with_name(path.name + suffix) for suffix in (".metadata.json", ".meta.json")]
    candidates.append(path.with_name(path.stem.split("-")[0] + ".meta.json"))
    for candidate in candidates:
        if candidate.exists():
            value = read(candidate)
            if value.get("sha256") != sha(path):
                raise ValueError(f"원본 해시 불일치: {path}")
            return value
    raise ValueError(f"metadata가 없습니다: {path}")


def collected(meta):
    for field in ("downloadedAt", "fetchedAt", "collectedAt", "retrievedAt"):
        if meta.get(field):
            return meta[field]
    raise ValueError("원천 수집 시각이 없습니다")


def generate(data, reference, out):
    out.mkdir(parents=True, exist_ok=True)
    snapshot = read(reference)
    nodes = {n["code"]: n for n in snapshot["nodes"]}
    # 새로운 코드 치환 규칙을 만들지 않고, 이미 검증한 원천 대응과 명칭을 교차 확인한다.
    regions = []
    seen = set()
    for mapping in snapshot["mappings"]:
        node = nodes[mapping["targetCode"]]
        if mapping["system"] != "SEMAS_STORE" or mapping["reviewStatus"] != "VERIFIED" or node["province"] != "서울특별시":
            continue
        if mapping["sourceCode"] in seen:
            raise ValueError("중복된 서울 원천 지역 대응")
        seen.add(mapping["sourceCode"])
        regions.append(dict(sourceCode=mapping["sourceCode"], sourceName=node["locality"], targetCode=node["code"],
                            evidence=json.dumps({"referenceMapping": mapping, "rule": "EXISTING_VERIFIED_MAPPING_AND_OFFICIAL_LOCALITY_NAME"}, ensure_ascii=False)))
    mapping_file = out / "region-mappings.json"
    save(mapping_file, dict(referenceVersion=snapshot["version"], referenceSha256=sha(reference), regions=regions))
    catalog = []
    ids = dict(sales="OA-22175", store="OA-22172", flow="OA-22178", worker="OA-22184", facility="OA-22169")
    for dataset in ids:
        template = read(data / "분기별JSON" / f"{dataset}-20262.json")
        aliases = {}
        for key, label in template["DESCRIPTION"].items():
            canonical = key.lower()
            for alias in (key, canonical, label):
                if alias in aliases and aliases[alias] != canonical:
                    raise ValueError(f"모호한 헤더 설명: {alias}")
                aliases[alias] = canonical
        fields = sorted(set(aliases.values()) - {"stdr_yyqu_cd", "adstrd_cd", "adstrd_cd_nm", "svc_induty_cd", "svc_induty_cd_nm"})
        if dataset == "store":
            aliases.update({"점포_수": "stor_co", "유사_업종_점포_수": "similr_induty_stor_co"})
        metrics = []
        for field in fields:
            unit = "KRW" if dataset == "sales" and field.endswith("_amt") else "PERCENT" if field.endswith("_rt") else "PERSON" if dataset == "worker" else "FACILITY" if dataset == "facility" else "SOURCE_COUNT" if dataset == "flow" else "COUNT"
            metrics.append(dict(field=field, unit=unit, aggregation="SOURCE_DEFINED",
                                definitionStatus="SOURCE_ONLY" if dataset in ("sales", "flow") or field.endswith("_rt") else "VERIFIED"))
        files = []
        if dataset in ("sales", "store"):
            files.extend(sorted((data / "연도별ZIP").glob(f"{dataset}-*.zip")))
        else:
            files.extend(sorted(p for p in (data / "분기이력JSON").glob(f"{dataset}-*.json") if not p.name.endswith(".meta.json")))
        files.extend(sorted(p for p in (data / "분기별JSON").glob(f"{dataset}-*.json") if not p.name.endswith(".meta.json")))
        # 분할 원본에서는 2026Q1만 추출해 연도별 원본과 중복 적재하지 않는다.
        if dataset == "store":
            parents, rows = [], []
            for path in sorted((data / "점포업종별JSON").glob("CS*.json")):
                if path.name.endswith(".meta.json"):
                    continue
                meta = receipt(path)
                for number, row in enumerate(read(path)["DATA"], 1):
                    if str(row["stdr_yyqu_cd"]) == "20261":
                        rows.append({**row, "_source_artifact": path.name, "_source_row_number": number})
                parents.append(dict(path=str(path.resolve()), sha256=sha(path), metadata=meta))
            if rows:
                path = out / "store-20261.json"
                save(path, {"DESCRIPTION": template["DESCRIPTION"], "DATA": rows})
                save(path.with_name(path.name + ".meta.json"), dict(sha256=sha(path), collectedAt=max(collected(p["metadata"]) for p in parents),
                     expectedRows=len(rows), derivation="FILTER_20261_FROM_ALL_INDUSTRY_PARTITIONS", parents=parents))
                files.append(path)
                aliases.update({"_source_artifact": "_source_artifact", "_source_row_number": "_source_row_number"})
        for path in files:
            meta = receipt(path)
            if path.suffix == ".zip":
                with zipfile.ZipFile(path) as archive:
                    members = [m for m in archive.infolist() if not m.is_dir()]
                    if len(members) != 1 or not members[0].filename.lower().endswith(".csv"):
                        raise ValueError(f"단일 CSV ZIP이 아닙니다: {path}")
                    with io.TextIOWrapper(archive.open(members[0]), encoding="cp949") as stream:
                        periods, count = set(), 0
                        reader = csv.DictReader(stream)
                        unknown = set(reader.fieldnames) - aliases.keys()
                        if unknown:
                            raise ValueError(f"미정의 CSV 헤더: {path}: {unknown}")
                        for row in reader:
                            periods.add(row["기준_년분기_코드"])
                            count += 1
                encoding = "MS949"
            else:
                rows = read(path)["DATA"]
                periods, count = {str(r["stdr_yyqu_cd"]) for r in rows}, len(rows)
                encoding = "UTF-8"
            if count <= 0 or count != int(meta.get("expectedRows", meta.get("downloadedRows", count))):
                raise ValueError(f"건수 불일치: {path}")
            name = f"{path.stem}.manifest.json"
            manifest = dict(version="regional-detail-manifest-v1", sourceSystem="SEOUL_COMMERCIAL", datasetCode=dataset.upper(),
                 adapter="seoul-dong-v1", periodType="QUARTER", periods=sorted(periods), regionScheme="SEOUL_ADSTRD8",
                 industryScheme="SEOUL_CS100" if dataset in ("sales", "store") else "NONE",
                 referenceVersion=snapshot["version"], referenceSha256=sha(reference), mappingFile="region-mappings.json", mappingSha256=sha(mapping_file),
                 sourceUrl=f"https://data.seoul.go.kr/dataList/{ids[dataset]}/S/1/datasetView.do", collectedAt=collected(meta),
                 rawFile=os.path.relpath(path.resolve(), out.resolve()).replace("\\", "/"), rawSha256=sha(path), encoding=encoding,
                 expectedRows=count, aliases=aliases, metrics=metrics, metadata=meta)
            save(out / name, manifest)
            catalog.append(dict(manifest=name, dataset=dataset, periods=sorted(periods), rows=count, sha256=manifest["rawSha256"]))
    save(out / "catalog.json", dict(reference=str(reference.resolve()), referenceSha256=sha(reference), mappingCount=len(regions), files=catalog))
    print(json.dumps(dict(files=len(catalog), sourceRows=sum(f["rows"] for f in catalog), mappingCount=len(regions))))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", required=True, type=pathlib.Path)
    parser.add_argument("--reference", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    generate(args.data_dir, args.reference, args.output)
