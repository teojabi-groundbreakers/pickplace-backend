import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from prepare_seoul_resident import generate


class ResidentManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "input"
        (self.data / "분기이력JSON").mkdir(parents=True)
        (self.data / "분기별JSON").mkdir()
        self.reference = self.root / "snapshot.json"
        self.reference.write_text(json.dumps({"version": "test-v1", "nodes": [{"code": "1111051500", "province": "서울특별시", "locality": "청운효자동"}],
            "mappings": [{"system": "SEMAS_STORE", "reviewStatus": "VERIFIED", "sourceCode": "11110515", "targetCode": "1111051500"}]}), encoding="utf8")
        self.write_source("20261", self.data / "분기이력JSON" / "resident-20261.json")
        self.latest = self.data / "분기별JSON" / "resident-20262.json"
        self.write_source("20262", self.latest)

    def write_source(self, period, path):
        value = {"DESCRIPTION": {"STDR_YYQU_CD": "기준_년분기_코드", "ADSTRD_CD": "행정동_코드", "ADSTRD_CD_NM": "행정동_코드_명", "TOT_REPOP_CO": "총_상주인구_수"},
                 "DATA": [{"stdr_yyqu_cd": period, "adstrd_cd": "11110515", "adstrd_cd_nm": "청운효자동", "tot_repop_co": 0}]}
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf8")
        metadata = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "downloadedAt": "2026-11-19T00:00:00Z", "expectedRows": 1}
        path.with_name(path.name + ".meta.json").write_text(json.dumps(metadata), encoding="utf8")

    def test_preserves_source_and_marks_definition_as_source_only(self):
        result = generate(self.data, self.reference, self.root / "out")
        self.assertEqual(result, {"files": 2, "sourceRows": 2, "mappingCount": 1})
        manifest = json.loads((self.root / "out" / "resident-20262.manifest.json").read_text(encoding="utf8"))
        self.assertEqual(manifest["metrics"], [{"field": "tot_repop_co", "unit": "PERSON", "aggregation": "SOURCE_DEFINED", "definitionStatus": "SOURCE_ONLY"}])
        self.assertEqual(manifest["industryScheme"], "NONE")

    def test_rejects_changed_source(self):
        self.latest.write_text("{}", encoding="utf8")
        with self.assertRaisesRegex(ValueError, "해시"):
            generate(self.data, self.reference, self.root / "out")

    def test_rejects_mismatched_filename_and_period(self):
        self.write_source("20261", self.latest)
        with self.assertRaises(ValueError):
            generate(self.data, self.reference, self.root / "out")


if __name__ == "__main__":
    unittest.main()
