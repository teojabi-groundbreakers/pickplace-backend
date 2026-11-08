"""고정 원본과 지역·소속을 대조하고 새 검토본을 만든다. 승인/게시하지 않는다."""
import argparse
from collections import Counter, defaultdict
import csv
import io
import json
from pathlib import Path
import re
import zipfile

from build_national_reference import official, original, sha

POLICY = 'national-reference-review-v1'
NOTICE = 'https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=130674'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def locality_notation(value):
    # 동일 코드·소속을 대조한 뒤에만 표기 차이를 비교한다. 이름으로 코드를 찾지 않는다.
    return re.sub(r'[\s.·ㆍ]', '', value or '')


def same_locality_name(left, right):
    left, right = locality_notation(left), locality_notation(right)
    # 홍제1동/홍제제1동처럼 지명 자체가 '제'로 끝날 수 있어 양쪽을 일괄 삭제하지 않는다.
    return (left == right or re.sub(r'제(?=[0-9])', '', left, count=1) == right
            or left == re.sub(r'제(?=[0-9])', '', right, count=1))


def temporal_status(node, reference_on):
    if reference_on in (node['createdOn'], node['abolishedOn']):
        return 'CHANGE_DATE_HOLD'
    if reference_on < node['createdOn'] or (node['abolishedOn'] and reference_on > node['abolishedOn']):
        return 'OUTSIDE_PERIOD_HOLD'
    return 'WITHIN_RECORDED_PERIOD'


def review_navigation(snapshot):
    nodes = {n['code']: n for n in snapshot['nodes']}
    rows = {n['code']: n for n in snapshot['navigation']}
    branches, evidence = [], []
    require(len(rows) == len(snapshot['navigation']), 'Duplicate navigation code')
    for code, row in rows.items():
        node = nodes[code]
        require(node['status'] == 'CURRENT' and node['dateStatus'] == 'VALID', 'Invalid current node date')
        province = nodes[row['province']]
        require(rows[row['province']]['role'] == 'PROVINCE', 'Invalid province role')
        branch_like = any('출장소' in str(node[k] or '') for k in ('province', 'district', 'locality'))
        if branch_like:
            row.update(selectable=False, autoSelect=False, role='NONE', reviewStatus='BLOCKED')
            row['evidence'] = json.dumps(dict(policy=POLICY, reason='BRANCH_SCOPE_REVIEW',
                detail='Raw branch-like node retained; legal type, jurisdiction and selection eligibility not established',
                rawProvince=node['province'], rawDistrict=node['district'], rawLocality=node['locality']), ensure_ascii=False)
            branches.append(dict(code=code, **{k: node[k] for k in ('province', 'district', 'locality')}))
            continue
        require(node['province'] == province['province'], 'Province label mismatch')
        if row['role'] == 'PROVINCE':
            require(row['kind'] == 'PROVINCE' and row['parent'] is None and row['selectionParent'] is None
                    and code == row['province'] and node['district'] is None and node['locality'] is None,
                    'Invalid province root')
            rule = 'OFFICIAL_PROVINCE_ROW'
        elif row['kind'] == 'CITY_CONTAINER':
            require(row['role'] == 'NONE' and row['parent'] == row['province'] and node['district']
                    and node['locality'] is None, 'Invalid city container')
            wards = [n for n in rows.values() if n['parent'] == code and n['role'] == 'GROUP']
            require(wards and all(nodes[n['code']]['province'] == node['province']
                    and nodes[n['code']]['district'].split(' ')[0] == node['district'] for n in wards),
                    'City/ward label mismatch')
            rule = 'CITY_CONTAINER_WITH_OFFICIAL_PATH_LABELS'
        elif row['role'] == 'GROUP':
            require(row['kind'] == 'GROUP' and node['locality'] is None and row['selectionParent'] == row['province'],
                    'Invalid selection group')
            if row['parent'] != row['province']:
                parent = nodes[row['parent']]
                require(rows[row['parent']]['kind'] == 'CITY_CONTAINER' and parent['province'] == node['province']
                        and node['district'].split(' ')[0] == parent['district'], 'Wrong city/ward parent')
            elif node['district'] is None:
                require(code == '3611000000' and row['province'] == '3600000000', 'Unknown empty group name')
            require(row['displayName'] == (node['district'] or province['province']), 'Wrong group display')
            rule = 'OFFICIAL_GROUP_PATH_AND_UI_PROJECTION'
        elif row['role'] == 'LOCALITY':
            parent = nodes[row['parent']]
            require(row['kind'] in ('EUP', 'MYEON', 'DONG') and node['locality']
                    and rows[row['parent']]['role'] == 'GROUP' and row['selectionParent'] == row['parent']
                    and node['province'] == parent['province'] and node['district'] == parent['district'],
                    'Locality parent labels mismatch')
            require(row['displayName'] == node['locality'], 'Locality display differs from official name')
            rule = 'OFFICIAL_LOCALITY_PROVINCE_DISTRICT_LABELS'
        else:
            raise ValueError('Unexplained navigation role')
        # 관계 후보의 도달성·순환을 전체 경로로 검사한다.
        seen, cursor = set(), row
        while cursor['parent'] is not None:
            require(cursor['code'] not in seen, 'Hierarchy cycle')
            seen.add(cursor['code'])
            require(cursor['parent'] in rows, 'Missing hierarchy parent')
            cursor = rows[cursor['parent']]
        require(cursor['code'] == row['province'], 'Hierarchy does not reach its province')
        row.update(reviewStatus='VERIFIED', selectable=row['role'] != 'NONE', autoSelect=False)
        row['evidence'] = json.dumps(dict(policy=POLICY, rule=rule, officialNotice=NOTICE,
            officialCode=code, rawProvince=node['province'], rawDistrict=node['district'],
            rawLocality=node['locality'], scope='Service selection hierarchy; not a complete legal-type certification'), ensure_ascii=False)
        evidence.append(dict(code=code, rule=rule))
    return branches, evidence


def source_rows(statistics_dir):
    population_path = statistics_dir / 'population-202609-original.bin'
    store_path = statistics_dir / 'stores-original.bin'
    population_meta, store_meta = original(population_path), original(store_path)
    result = {}
    with population_path.open(encoding='cp949', newline='') as stream:
        for row in csv.DictReader(stream):
            key = ('MOIS_RESIDENT', row['행정기관코드'])
            require(key not in result, 'Duplicate population source region')
            require(row['기준연월'] == '2026-09-30', 'Unexpected population period')
            result[key] = dict(name=row['읍면동명'], province=row['시도명'], district=row['시군구명'], districtCode=None,
                               rawSha256=population_meta['sha256'])
    with zipfile.ZipFile(store_path) as archive:
        for member in archive.namelist():
            if not member.lower().endswith('.csv'):
                continue
            with archive.open(member) as stream:
                for row in csv.DictReader(io.TextIOWrapper(stream, encoding='utf-8-sig', newline='')):
                    key = ('SEMAS_STORE', row['행정동코드'])
                    data = dict(name=row['행정동명'], province=row['시도명'], district=row['시군구명'],
                                districtCode=row['시군구코드'], rawSha256=store_meta['sha256'])
                    require(key not in result or result[key] == data, 'Conflicting source names/parent')
                    result[key] = data
    return result


def review_mappings(snapshot, raw_sources):
    nodes = {n['code']: n for n in snapshot['nodes']}
    sources = {(s['system'], s['code'], s['version']): s for s in snapshot['sources']}
    require(len(sources) == len(snapshot['sources']), 'Duplicate snapshot source key')
    require(set(raw_sources) == {(s['system'], s['code']) for s in sources.values()}, 'Raw/snapshot source set mismatch')
    candidates = defaultdict(list)
    for node in nodes.values():
        if node['locality'] and node['code'].endswith('00'):
            candidates[node['code'][:8]].append(node['code'])
    variants, temporal_holds, legacy = [], [], []
    covered = set()
    for mapping in snapshot['mappings']:
        key = (mapping['system'], mapping['sourceCode'], mapping['sourceVersion'])
        require(key in sources and key not in covered, 'Missing/ambiguous source mapping')
        covered.add(key)
        source = sources[key]
        raw = raw_sources[(source['system'], source['code'])]
        target = nodes[mapping['targetCode']]
        require(source['name'] == raw['name'] and source['districtName'] == raw['district']
                and source['districtCode'] == raw['districtCode'], 'Snapshot source differs from original')
        require(json.loads(source['evidence'])['sha256'] == raw['rawSha256'], 'Source evidence hash mismatch')
        require(source['referenceOn'] == mapping['referenceOn'], 'Mapping period mismatch')
        require(target['dateStatus'] == 'VALID' and target['locality'], 'Invalid target date/role')
        if source['system'] == 'MOIS_RESIDENT':
            require(source['code'] == target['code'], 'Population identity mismatch')
        else:
            require(candidates[source['code']] == [target['code']], 'Store candidate not unique in official history')
            require(source['districtCode'] == target['code'][:5], 'Store district code mismatch')
        require(raw['province'] == target['province'], 'Source province mismatch')
        sejong = target['code'].startswith('36110') and target['province'] == '세종특별자치시' and target['district'] is None
        require((raw['district'] or '').strip() == (target['district'] or '').strip()
                or sejong and (raw['district'] or '').strip() == '세종특별자치시', 'Source district name mismatch')
        require(same_locality_name(raw['name'], target['locality']), 'Source locality mismatch: ' + source['code'])
        notation = raw['name'] != target['locality']
        if notation:
            variants.append(dict(system=source['system'], sourceCode=source['code'], targetCode=target['code'],
                                 sourceName=raw['name'], officialName=target['locality']))
        temporal = temporal_status(target, source['referenceOn'])
        if temporal != 'WITHIN_RECORDED_PERIOD':
            temporal_holds.append(dict(system=source['system'], sourceCode=source['code'], targetCode=target['code'],
                                       sourceName=raw['name'], referenceOn=source['referenceOn'],
                                       createdOn=target['createdOn'], abolishedOn=target['abolishedOn'], reason=temporal))
        if target['status'] == 'LEGACY':
            legacy.append(dict(system=source['system'], sourceCode=source['code'], targetCode=target['code'],
                               name=raw['name'], temporalStatus=temporal))
        mapping.update(reviewStatus='VERIFIED', evidence=json.dumps(dict(policy=POLICY,
            identity='VERIFIED_SOURCE_CODE_NAME_PARENT', rawSha256=raw['rawSha256'], sourceName=raw['name'],
            officialName=target['locality'], province=raw['province'], sourceDistrict=raw['district'],
            officialDistrict=target['district'], notationDifference=notation, temporalStatus=temporal,
            scope='Identity only; no redistribution or numeric statistic publication'), ensure_ascii=False))
    require(covered == set(sources), 'Source mapping coverage incomplete')
    return variants, temporal_holds, legacy


def review(snapshot, current, history, raw_sources, version, input_sha):
    require(len(snapshot['nodes']) == len(history), 'Official history node count mismatch')
    require({n['code'] for n in snapshot['navigation']} == set(current), 'Current navigation set mismatch')
    for code, row in current.items():
        require(code in history and all(row[k] == history[code][k] for k in row if k != 'rowNumber'),
                'Current/history official content mismatch')
    for node in snapshot['nodes']:
        raw = history[node['code']]
        require(all(node[k] == raw[k] for k in ('code', 'province', 'district', 'locality', 'rawCreatedOn', 'rawAbolishedOn', 'rowNumber')),
                'Official node differs from source')
    branches, hierarchy = review_navigation(snapshot)
    variants, temporal_holds, legacy = review_mappings(snapshot, raw_sources)
    snapshot.update(version=version, policyVersion=POLICY)
    counts = Counter(n['reviewStatus'] for n in snapshot['navigation'])
    return dict(policyVersion=POLICY, inputSha256=input_sha, snapshotVersion=version,
                hierarchy=dict(total=len(snapshot['navigation']), verified=counts['VERIFIED'], blocked=counts['BLOCKED'],
                               selectable=sum(n['selectable'] for n in snapshot['navigation']), rules=dict(Counter(r['rule'] for r in hierarchy))),
                mapping=dict(total=len(snapshot['mappings']), identityVerified=len(snapshot['mappings']),
                             notationDifferenceCount=len(variants), temporalHoldCount=len(temporal_holds), legacyCount=len(legacy)),
                blockedNavigation=branches, notationDifferences=variants, temporalHolds=temporal_holds, legacyMappings=legacy,
                artifactsUsage='PENDING: unchanged; not a license or public release approval',
                approved=False, published=False)


def main():
    parser = argparse.ArgumentParser()
    for option in ('input', 'regions', 'statistics', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    require(sha(args.input) == args.sha256, 'Input snapshot checksum mismatch')
    require(not args.output.exists() and not args.output.with_suffix('.review.json').exists(), 'Output already exists')
    snapshot = json.loads(args.input.read_text(encoding='utf-8'))
    require(args.version != snapshot['version'], 'Reviewed snapshot requires a new version')
    artifact_map = {r['role']: r for r in snapshot['artifacts']}
    for role, filename in [('H_CURRENT_ZIP', 'jscode20261026.zip'), ('H_HISTORY_ZIP', 'jscode20261026-history.zip'), ('LAYOUT', 'layout.hwpx')]:
        require(original(args.regions / filename)['sha256'] == artifact_map[role]['sha256'], 'Official artifact mismatch')
    current, _ = official(args.regions / 'jscode20261026.zip')
    history, _ = official(args.regions / 'jscode20261026-history.zip')
    report = review(snapshot, current, history, source_rows(args.statistics), args.version, args.sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    report['snapshotSha256'] = sha(args.output)
    args.output.with_suffix('.review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('blockedNavigation', 'notationDifferences', 'temporalHolds', 'legacyMappings')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
