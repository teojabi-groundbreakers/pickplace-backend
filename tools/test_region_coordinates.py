import unittest

from prepare_region_coordinates import build_records


def region(code):
    return {"code": code, "displayName": code, "kind": "DONG", "selectable": True}


def feature(code, coordinates):
    return {"type": "Feature", "properties": {"adm_cd2": code},
            "geometry": {"type": "Polygon", "coordinates": [coordinates]}}


class CoordinatesTests(unittest.TestCase):
    def test_valid_missing_duplicate_and_invalid(self):
        square = [[126.9, 37.5], [127.0, 37.5], [127.0, 37.6], [126.9, 37.6], [126.9, 37.5]]
        crossed = [[126.9, 37.5], [127.0, 37.6], [127.0, 37.5], [126.9, 37.6], [126.9, 37.5]]
        codes = ["1111111111", "2222222222", "3333333333", "4444444444"]
        result = build_records({"navigation": [region(c) for c in codes]},
                               {"type": "FeatureCollection", "features": [feature(codes[0], square),
                                feature(codes[2], square), feature(codes[2], square), feature(codes[3], crossed)]})
        self.assertEqual([r["status"] for r in result],
                         ["BOUNDARY_INTERIOR", "MISSING_BOUNDARY", "DUPLICATE_BOUNDARY", "INVALID_BOUNDARY"])
        self.assertTrue(37.5 <= result[0]["latitude"] <= 37.6)
        self.assertTrue(126.9 <= result[0]["longitude"] <= 127.0)
        self.assertTrue(all(r["latitude"] is None for r in result[1:]))

    def test_duplicate_reference_codes_fail(self):
        with self.assertRaises(ValueError):
            build_records({"navigation": [region("1111111111"), region("1111111111")]},
                          {"type": "FeatureCollection", "features": []})

    def test_outside_korea_is_invalid(self):
        polygon = [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]
        result = build_records({"navigation": [region("1111111111")]},
                               {"type": "FeatureCollection", "features": [feature("1111111111", polygon)]})
        self.assertEqual(result[0]["status"], "INVALID_BOUNDARY")

    def test_projected_source_crs_is_rejected(self):
        with self.assertRaises(ValueError):
            build_records({"navigation": [region("1111111111")]},
                          {"type": "FeatureCollection", "features": [],
                           "crs": {"properties": {"name": "EPSG:5179"}}})

    def test_inconsistent_region_name_and_parent_is_not_used(self):
        square = [[126.9, 37.5], [127, 37.5], [127, 37.6], [126.9, 37.6], [126.9, 37.5]]
        item = feature("1111111111", square)
        item["properties"].update(adm_nm="서울특별시 다른동", sgg="11111")
        result = build_records({"navigation": [{**region("1111111111"), "displayName": "사직동"}]},
                               {"type": "FeatureCollection", "features": [item]})
        self.assertEqual(result[0]["status"], "REGION_MISMATCH")
        self.assertIsNone(result[0]["latitude"])

    def test_locality_name_formatting(self):
        from prepare_region_coordinates import same_locality_name
        self.assertTrue(same_locality_name("홍제제1동", "서울특별시 서대문구 홍제1동"))
        self.assertTrue(same_locality_name("종로1.2.3.4가동", "서울특별시 종로구 종로1·2·3·4가동"))
        self.assertFalse(same_locality_name("홍제제1동", "서울특별시 서대문구 홍제2동"))


if __name__ == "__main__":
    unittest.main()
