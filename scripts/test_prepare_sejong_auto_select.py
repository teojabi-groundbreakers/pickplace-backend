import json
import unittest

from prepare_sejong_auto_select import prepare, BASE_VERSION, VERSION, GROUP, PROVINCE


class SejongRecommendationTests(unittest.TestCase):
    def fixture(self):
        return {'version': BASE_VERSION, 'sources': [{'unchanged': True}], 'navigation': [
            {'code': GROUP, 'role': 'GROUP', 'province': PROVINCE, 'selectionParent': PROVINCE,
             'selectable': True, 'reviewStatus': 'VERIFIED', 'autoSelect': False, 'evidence': '{"original":"preserved"}'},
            {'code': '1111000000', 'autoSelect': False},
        ]}

    def test_changes_only_sejong_hint_version_and_evidence_without_mutating_input(self):
        original = self.fixture()
        result = prepare(original)
        self.assertEqual(result['version'], VERSION)
        self.assertFalse(original['navigation'][0]['autoSelect'])
        self.assertTrue(result['navigation'][0]['autoSelect'])
        self.assertEqual(json.loads(result['navigation'][0]['evidence'])['original'], 'preserved')
        result['version'] = original['version']
        result['navigation'][0]['autoSelect'] = False
        result['navigation'][0]['evidence'] = original['navigation'][0]['evidence']
        self.assertEqual(result, original)

    def test_rejects_changed_version(self):
        snapshot = self.fixture()
        snapshot['version'] = 'other'
        with self.assertRaises(ValueError):
            prepare(snapshot)

    def test_rejects_missing_or_duplicate_group(self):
        for rows in [[], [self.fixture()['navigation'][0]] * 2]:
            snapshot = self.fixture()
            snapshot['navigation'] = rows
            with self.assertRaises(ValueError):
                prepare(snapshot)

    def test_rejects_other_existing_recommendation(self):
        snapshot = self.fixture()
        snapshot['navigation'][1]['autoSelect'] = True
        with self.assertRaises(ValueError):
            prepare(snapshot)

    def test_rejects_wrong_parent_role_or_review(self):
        for key, value in [('selectionParent', '1100000000'), ('role', 'LOCALITY'), ('selectable', False), ('reviewStatus', 'PENDING')]:
            snapshot = self.fixture()
            snapshot['navigation'][0][key] = value
            with self.assertRaises(ValueError):
                prepare(snapshot)


if __name__ == '__main__':
    unittest.main()
