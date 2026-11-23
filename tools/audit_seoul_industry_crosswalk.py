"""Build a review queue from official Seoul CS definitions and one SEMAS Seoul snapshot."""

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

from pypdf import PdfReader


def seoul_codes(pdf):
    text = "\n\f\n".join(page.extract_text() for page in PdfReader(pdf).pages)
    markers = list(re.finditer(r"^[ \t]*\((CS[0-9]{6})\)[ \t]*$", text, re.M))
    definitions = defaultdict(set)
    for index, marker in enumerate(markers):
        end = markers[index + 1].start() if index + 1 < len(markers) else len(text)
        definition = re.split(r"^\s*·", text[marker.end():end], maxsplit=1, flags=re.M)[0]
        codes = re.findall(r"\(([0-9]{5})\)", definition)
        if len(codes) != 1:
            raise ValueError(f"Check official KSIC extraction for {marker[1]}: {codes}")
        definitions[marker[1]].add(codes[0])
    if len(definitions) != 100:
        raise ValueError(f"Expected 100 Seoul definitions, parsed {len(definitions)}")
    return definitions


def semas_counts(archive, approved_codes):
    counts = defaultdict(Counter)
    totals = Counter()
    with zipfile.ZipFile(archive) as source:
        members = [name for name in source.namelist() if re.search(r"_서울_[0-9]{6}\.csv$", name)]
        if len(members) != 1:
            raise ValueError("A single Seoul member is required")
        with io.TextIOWrapper(source.open(members[0]), encoding="utf-8-sig", newline="") as rows:
            for row in csv.DictReader(rows):
                code = row["상권업종소분류코드"]
                if code not in approved_codes:
                    raise ValueError(f"Unregistered SEMAS small code: {code}")
                field = row["표준산업분류코드"].strip()
                ksic = field[-5:] if re.fullmatch(r"[A-Z]?[0-9]{5}", field) else ""
                counts[code][ksic] += 1
                totals[ksic] += 1
    return counts, totals


def audit(snapshot, definitions, counts, totals):
    results = []
    for code, name in sorted(snapshot.items()):
        frequency = counts.get(code, {})
        total = sum(frequency.values())
        candidates = []
        for cs_code, ksic_codes in definitions.items():
            shared = sum(frequency.get(ksic, 0) for ksic in ksic_codes)
            if not shared:
                continue
            observed_cs_scope = sum(totals[ksic] for ksic in ksic_codes)
            # These are observed rows with a shared KSIC, never a match or approval by themselves.
            candidates.append((shared, observed_cs_scope, cs_code, "|".join(sorted(ksic_codes))))
        candidates.sort(key=lambda row: (-row[0], -(row[0] / row[1]), row[2]))
        row = {"semasCode": code, "semasName": name, "semasRows": total,
               "missingKsicRows": frequency.get("", 0), "candidateCount": len(candidates)}
        if candidates:
            shared, observed_cs_scope, cs_code, ksic = candidates[0]
            priority = shared >= 30 and 5 * shared >= 4 * total and 5 * shared >= 4 * observed_cs_scope
            row.update(reviewStatus="PRIORITY_REVIEW" if priority else "REVIEW",
                       candidateCs=cs_code, candidateKsic=ksic, overlappingRows=shared,
                       rowsWithCandidateKsic=observed_cs_scope, semasPercent=round(100 * shared / total, 2),
                       ksicPercent=round(100 * shared / observed_cs_scope, 2),
                       otherCs="|".join(candidate[2] for candidate in candidates[1:]))
        else:
            row["reviewStatus"] = "NO_SHARED_KSIC" if total else "NO_SEOUL_ROWS"
        results.append(row)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--seoul-pdf", type=Path, required=True)
    parser.add_argument("--semas-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    small = {row["code"]: row["name"] for row in snapshot["industries"] if row["level"] == "SMALL"}
    if len(small) != 247:
        raise ValueError(f"Expected 247 approved SEMAS small codes in this snapshot, found {len(small)}")
    with args.semas_zip.open("rb") as source:
        semas_hash = hashlib.file_digest(source, "sha256").hexdigest()
    if semas_hash != snapshot["classificationSourceSha256"]:
        raise ValueError("SEMAS archive hash does not match the published classification")
    definitions = seoul_codes(args.seoul_pdf)
    counts, totals = semas_counts(args.semas_zip, small)
    results = audit(small, definitions, counts, totals)
    fields = ("semasCode", "semasName", "reviewStatus", "semasRows", "missingKsicRows", "candidateCount",
              "candidateCs", "candidateKsic", "overlappingRows", "rowsWithCandidateKsic",
              "semasPercent", "ksicPercent", "otherCs")
    with args.output.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(results)
    classifications = Counter(row["reviewStatus"] for row in results)
    print(json.dumps({"classification": snapshot["classificationVersion"], "smallCodes": len(small),
                      "seoulDefinitions": len(definitions), "seoulRows": sum(totals.values()),
                      "semasSha256": semas_hash, "seoulPdfSha256": hashlib.sha256(args.seoul_pdf.read_bytes()).hexdigest(),
                      "results": classifications}, ensure_ascii=False))


if __name__ == "__main__":
    main()
