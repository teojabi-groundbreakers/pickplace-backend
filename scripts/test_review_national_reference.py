import copy
import json
from pathlib import Path
import unittest

from review_national_reference import locality_notation, same_locality_name, review, review_mappings, temporal_status


class ReferenceReviewTests(unittest.TestCase):
    def setUp(self):
        fixture = Path(__file__).resolve().parents[1] / 'src/test/resources/reference-data/national-reference-fixture.json'
        self.snapshot = json.loads(fixture.read_text(encoding='utf-8'))
        self.history = {n['code']: copy.deepcopy(n) for n in self.snapshot['nodes']}
        self.current = copy.deepcopy(self.history)
        self.raw = {(s['system'], s['code']): dict(name=s['name'], province='서울특별시', district=s['districtName'],
                    districtCode=s['districtCode'], rawSha256='a') for s in self.snapshot['sources']}

    def test_matching_code_names_and_parent_are_verified(self):
        result = review(self.snapshot, self.current, self.history, self.raw, 'reviewed-v1', 'a' * 64)
        self.assertEqual(result['hierarchy']['verified'], 3)
        self.assertEqual(result['mapping']['identityVerified'], 2)
        self.assertFalse(result['published'])

    def test_notation_changes_do_not_remove_normal_name_characters(self):
        self.assertTrue(same_locality_name('신길제3동', '신길3동'))
        self.assertTrue(same_locality_name('홍제1동', '홍제제1동'))
        self.assertTrue(same_locality_name('거제제1동', '거제1동'))
        self.assertEqual(locality_notation('용담.명암.산성동'), locality_notation('용담명암산성동'))
        self.assertNotEqual(locality_notation('제기동'), locality_notation('기동'))

    def test_same_name_does_not_allow_another_code(self):
        self.snapshot['mappings'][0]['targetCode'] = '1111000000'
        with self.assertRaises(ValueError):
            review_mappings(self.snapshot, self.raw)

    def test_same_code_with_different_province_is_rejected(self):
        self.raw['MOIS_RESIDENT', '1111051500']['province'] = '다른 시도'
        with self.assertRaises(ValueError):
            review_mappings(self.snapshot, self.raw)

    def test_unexpected_source_name_is_rejected(self):
        self.snapshot['sources'][0]['name'] = '유사한동'
        self.raw['MOIS_RESIDENT', '1111051500']['name'] = '유사한동'
        with self.assertRaises(ValueError):
            review_mappings(self.snapshot, self.raw)

    def test_source_content_change_is_rejected(self):
        self.raw['MOIS_RESIDENT', '1111051500']['name'] = '다른동'
        with self.assertRaises(ValueError):
            review_mappings(self.snapshot, self.raw)

    def test_official_parent_change_is_rejected(self):
        self.history['1111051500']['district'] = '다른구'
        with self.assertRaises(ValueError):
            review(self.snapshot, self.current, self.history, self.raw, 'reviewed-v1', 'a' * 64)

    def test_change_day_and_outside_period_are_held(self):
        node = dict(createdOn='2020-01-01', abolishedOn='2026-09-30')
        self.assertEqual(temporal_status(node, '2026-09-30'), 'CHANGE_DATE_HOLD')
        self.assertEqual(temporal_status(node, '2026-10-01'), 'OUTSIDE_PERIOD_HOLD')
        self.assertEqual(temporal_status(node, '2026-09-01'), 'WITHIN_RECORDED_PERIOD')

    def test_unknown_empty_group_name_is_rejected(self):
        for collection in (self.snapshot['nodes'], self.current.values(), self.history.values()):
            for node in collection:
                if node['code'] == '1111000000':
                    node['district'] = None
        with self.assertRaises(ValueError):
            review(self.snapshot, self.current, self.history, self.raw, 'reviewed-v1', 'a' * 64)

    def test_selection_display_must_match_official_name(self):
        self.snapshot['navigation'][2]['displayName'] = '가짜 표시명'
        with self.assertRaises(ValueError):
            review(self.snapshot, self.current, self.history, self.raw, 'reviewed-v1', 'a' * 64)


if __name__ == '__main__':
    unittest.main()
