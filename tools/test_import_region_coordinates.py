import copy
import unittest

from import_region_coordinates import validate_input


def fixture():
    return {
        "status": "REVIEW_ONLY", "outputCrs": "EPSG:4326", "snapshotSha256": "a" * 64,
        "boundarySha256": "b" * 64, "boundaryDate": "2026-10-01", "sourceUrl": "https://example.test",
        "licenseUrl": "https://example.test/license", "attribution": "source", "method": "interior",
        "counts": {"BOUNDARY_INTERIOR": 1, "MISSING_BOUNDARY": 1},
        "records": [
            {"regionCode": "1111051500", "name": "청운효자동", "status": "BOUNDARY_INTERIOR", "latitude": 37.5, "longitude": 126.9},
            {"regionCode": "4122067000", "name": "고덕1동", "status": "MISSING_BOUNDARY", "latitude": None, "longitude": None},
        ],
    }


class ImportValidationTests(unittest.TestCase):
    def test_accepts_interior_and_missing(self):
        self.assertEqual(len(validate_input(fixture())), 2)

    def test_rejects_duplicate_and_inconsistent_count(self):
        data = fixture()
        data["records"].append(copy.deepcopy(data["records"][0]))
        with self.assertRaises(ValueError):
            validate_input(data)
        data = fixture()
        data["counts"]["BOUNDARY_INTERIOR"] = 2
        with self.assertRaises(ValueError):
            validate_input(data)

    def test_rejects_invalid_coordinate_and_missing_with_value(self):
        data = fixture()
        data["records"][0]["latitude"] = float("nan")
        with self.assertRaises(ValueError):
            validate_input(data)
        data = fixture()
        data["records"][1]["latitude"] = 37.0
        with self.assertRaises(ValueError):
            validate_input(data)

    def test_rejects_unreviewed_status_and_bad_hash(self):
        data = fixture()
        data["status"] = "PUBLISHED"
        with self.assertRaises(ValueError):
            validate_input(data)
        data = fixture()
        data["boundarySha256"] = "bad"
        with self.assertRaises(ValueError):
            validate_input(data)


if __name__ == "__main__":
    unittest.main()
