"""확정 제외 정책·이용 근거를 검토본에 연결한다. DB 승인·게시는 별도 명령이다."""
import argparse
import hashlib
import json
from pathlib import Path

from build_national_reference import sha
from review_national_reference import require


def prepare(snapshot, policy, usage):
    require(usage['status'] == 'VERIFIED', 'Usage review is incomplete')
    nodes = {n['code']: n for n in snapshot['navigation']}
    excluded = set(policy['excludedCodes'])
    blocked = {n['code'] for n in snapshot['navigation'] if n['reviewStatus'] == 'BLOCKED'}
    require(blocked == excluded, 'Excluded nodes differ from confirmed policy')
    current = next(a for a in snapshot['artifacts'] if a['role'] == 'H_CURRENT_ZIP')
    require(current['sha256'] == policy['currentArtifactSha256'], 'Exclusion policy source snapshot mismatch')
    for code in excluded:
        node = nodes[code]
        require(not node['selectable'] and not node['autoSelect'] and node['role'] == 'NONE', 'Excluded node remains selectable')
        evidence = json.loads(node['evidence'])
        evidence.update(policy=policy['version'], reason='INITIAL_SELECTION_EXCLUDED',
                        decision='User confirmed on 2026-11-09: preserve history and exclude from initial selection',
                        scope='Selection exclusion confirmed; jurisdiction/type remains unresolved')
        node['evidence'] = json.dumps(evidence, ensure_ascii=False)
    region_usage = next(d for d in usage['datasets'] if d['datasetId'] == '15095148')
    for artifact in snapshot['artifacts']:
        artifact.update(usageStatus='VERIFIED', usageEvidence=json.dumps(dict(
            catalogUrl=region_usage['catalogUrl'], license=region_usage['license'],
            metadataSha256=region_usage['metadataSha256'], sourceLink=usage['regionSourceLink'],
            scope=usage['scope'], attribution=usage['attribution']), ensure_ascii=False))
    desired = {'I21201': '카페', 'I21007': '김밥/만두/분식', 'I21001': '빵/도넛'}
    industries = {n['code']: n for n in snapshot['industries']}
    for order, (code, name) in enumerate(desired.items(), 1):
        require(code in industries and industries[code]['name'] == name and industries[code]['level'] == 'SMALL', 'Supported industry definition changed')
        industries[code].update(supported=True, displayOrder=order, displayName=name)
    snapshot.update(policyVersion=policy['version'], classificationVersion='semas-202609-service-v1')
    return dict(excludedNodes=len(excluded), supportedCodes=desired,
                classificationVersion=snapshot['classificationVersion'], usageScope=usage['scope'])


def main():
    parser = argparse.ArgumentParser()
    for key in ('input', 'output', 'policy', 'usage', 'evidence-dir'):
        parser.add_argument('--' + key, required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    require(sha(args.input) == args.sha256, 'Input snapshot checksum mismatch')
    require(not args.output.exists() and not args.output.with_suffix('.release.json').exists(), 'Output already exists')
    usage = json.loads(args.usage.read_text(encoding='utf-8'))
    for dataset in usage['datasets']:
        data = (args.evidence_dir / dataset['evidenceFile']).read_bytes()
        require(hashlib.sha256(data).hexdigest() == dataset['metadataSha256'], 'Usage evidence checksum mismatch')
        raw = json.loads(data)
        require(raw['identifier'] == dataset['datasetId'] and raw['license']['name'] == dataset['license'] == '이용허락범위 제한 없음', 'Usage evidence content mismatch')
    link = usage['regionSourceLink']
    raw_page = (args.evidence_dir / link['evidenceFile']).read_bytes()
    require(hashlib.sha256(raw_page).hexdigest() == link['pageSha256']
            and link['linkedBoardUrl'] in raw_page.decode('utf-8'), 'Official source link mismatch')
    snapshot = json.loads(args.input.read_text(encoding='utf-8'))
    require(snapshot['version'] != args.version, 'Release requires a new version')
    report = prepare(snapshot, json.loads(args.policy.read_text(encoding='utf-8')), usage)
    snapshot['version'] = args.version
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    report.update(inputSha256=args.sha256, snapshotVersion=args.version, snapshotSha256=sha(args.output),
                  usageManifestSha256=sha(args.usage), policyManifestSha256=sha(args.policy), approved=False, published=False)
    args.output.with_suffix('.release.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
