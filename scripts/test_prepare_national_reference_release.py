import json
from pathlib import Path
import unittest

from prepare_national_reference_release import prepare


class PrepareReleaseTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        self.snapshot = json.loads((root / 'src/test/resources/reference-data/national-reference-fixture.json').read_text(encoding='utf-8'))
        self.policy = dict(version='test-policy', currentArtifactSha256='a' * 64, excludedCodes=['4110500000'])
        self.usage = dict(status='VERIFIED', datasets=[dict(datasetId='15095148', catalogUrl='https://example.test/catalog',
                         license='이용허락범위 제한 없음', metadataSha256='b' * 64)], regionSourceLink={}, scope='reference', attribution='source')
        self.snapshot['navigation'].append(dict(code='4110500000', reviewStatus='BLOCKED', selectable=False,
             autoSelect=False, role='NONE', evidence='{}'))
        self.snapshot['industries'].extend([
            dict(code='I21007', name='김밥/만두/분식', level='SMALL'),
            dict(code='I21001', name='빵/도넛', level='SMALL')])

    def test_prepares_explicit_exclusion_and_three_industries(self):
        report = prepare(self.snapshot, self.policy, self.usage)
        self.assertEqual(report['excludedNodes'], 1)
        self.assertEqual(self.snapshot['policyVersion'], 'test-policy')
        self.assertEqual(sum(n['supported'] for n in self.snapshot['industries']), 3)

    def test_pending_usage_is_not_upgraded(self):
        self.usage['status'] = 'PENDING'
        with self.assertRaises(ValueError):
            prepare(self.snapshot, self.policy, self.usage)

    def test_extra_unreviewed_exclusion_is_not_accepted(self):
        self.policy['excludedCodes'].append('9999900000')
        with self.assertRaises(ValueError):
            prepare(self.snapshot, self.policy, self.usage)

    def test_changed_source_snapshot_is_not_accepted(self):
        self.policy['currentArtifactSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            prepare(self.snapshot, self.policy, self.usage)

    def test_excluded_node_cannot_stay_selectable(self):
        self.snapshot['navigation'][-1]['selectable'] = True
        with self.assertRaises(ValueError):
            prepare(self.snapshot, self.policy, self.usage)

    def test_industry_meaning_change_requires_review(self):
        self.snapshot['industries'][-1]['name'] = '다른 업종'
        with self.assertRaises(ValueError):
            prepare(self.snapshot, self.policy, self.usage)


if __name__ == '__main__':
    unittest.main()
