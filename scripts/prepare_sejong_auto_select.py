"""검증된 게시본을 보존하고 세종 그룹의 자동 선택 권고만 새 버전에 반영한다."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

BASE_VERSION = 'national-202609-release-v1'
BASE_SHA256 = 'f9b3815bcb4b5f9362dd467191840b8b77b4eed67399fa4ac5ba895f1e9cb371'
VERSION = 'national-202609-sejong-hint-v1'
GROUP = '3611000000'
PROVINCE = '3600000000'


def prepare(snapshot):
    if snapshot['version'] != BASE_VERSION:
        raise ValueError('Expected the reviewed base release')
    result = copy.deepcopy(snapshot)
    groups = [row for row in result['navigation'] if row['code'] == GROUP]
    if len(groups) != 1:
        raise ValueError('Sejong group must exist exactly once')
    node = groups[0]
    if not (node['role'] == 'GROUP' and node['province'] == PROVINCE
            and node['selectionParent'] == PROVINCE and node['selectable']
            and node['reviewStatus'] == 'VERIFIED' and not node['autoSelect']):
        raise ValueError('Sejong selection role, parent or review changed')
    if any(row['autoSelect'] for row in result['navigation']):
        raise ValueError('Base release already contains an auto-selection recommendation')
    evidence = json.loads(node['evidence'])
    if not isinstance(evidence, dict):
        raise ValueError('Review evidence must remain an object')
    evidence['autoSelectRecommendation'] = {
        'policy': 'sejong-auto-select-hint-v1',
        'decisionOn': '2026-11-09',
        'meaning': 'BE recommendation; FE decides whether to apply the selection',
        'scope': 'Sejong GROUP only; false does not prohibit FE auto-selection',
    }
    node['autoSelect'] = True
    node['evidence'] = json.dumps(evidence, ensure_ascii=False, separators=(',', ':'))
    result['version'] = VERSION
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    if hashlib.sha256(raw).hexdigest() != BASE_SHA256:
        raise ValueError('Base release checksum mismatch')
    if args.output_dir.exists():
        raise ValueError('Output directory already exists; reviewed releases must not be overwritten')
    result = prepare(json.loads(raw))
    data = json.dumps(result, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    digest = hashlib.sha256(data).hexdigest()
    args.output_dir.mkdir(parents=True)
    (args.output_dir / 'snapshot.json').write_bytes(data)
    manifest = {
        'snapshotVersion': VERSION, 'snapshotFile': 'snapshot.json', 'sha256': digest, 'byteCount': len(data),
        'baseVersion': BASE_VERSION, 'baseSha256': BASE_SHA256,
        'changedGroupCode': GROUP, 'recommendationPolicy': 'sejong-auto-select-hint-v1',
        'allowedChanges': ['version', 'navigation[3611000000].autoSelect', 'navigation[3611000000].evidence'],
        'scope': 'Reference data only; industry classification, exclusion policy and source dates unchanged',
        'publicationNote': 'Generation is not DB approval or publication; use explicit import/approve/publish commands',
    }
    (args.output_dir / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'version': VERSION, 'sha256': digest, 'bytes': len(data)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
