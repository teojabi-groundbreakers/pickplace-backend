"""공식 원본에서 비공개 검토용 기준정보 묶음을 만든다. 승인·통계 적재는 하지 않는다."""
import argparse
import csv
import hashlib
import io
import json
import re
import zipfile
from datetime import datetime
from pathlib import Path

import openpyxl


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def original(path):
    metadata_path = path.with_suffix('.meta.json') if path.suffix == '.bin' else path.with_name(path.name + '.meta.json')
    meta = json.loads(metadata_path.read_text(encoding='utf-8'))
    if sha(path) != meta['sha256'] or path.stat().st_size != meta['bytes']:
        raise ValueError(f'Original checksum/size mismatch: {path.name}')
    return meta


def official(path):
    with zipfile.ZipFile(path) as archive:
        member = next(n for n in archive.namelist() if '/kikcd_h.' in n.lower() and n.endswith('.xlsx'))
        workbook = openpyxl.load_workbook(io.BytesIO(archive.read(member)), read_only=True, data_only=True)
        rows = list(workbook.active.values)[1:]
        workbook.close()
    result = {}
    for number, row in enumerate(rows, 2):
        code, province, district, locality, created, abolished = row
        if code in result or not re.fullmatch(r'[0-9]{10}', code):
            raise ValueError('Duplicate/invalid official code')
        result[code] = dict(code=code, province=province, district=district, locality=locality,
                            rawCreatedOn=created, rawAbolishedOn=abolished, rowNumber=number)
    return result, member


def build(region_dir, statistics_dir, version):
    files = [('H_CURRENT_ZIP', region_dir / 'jscode20261026.zip', 'xlsx'),
             ('H_HISTORY_ZIP', region_dir / 'jscode20261026-history.zip', 'xlsx'),
             ('LAYOUT', region_dir / 'layout.hwpx', 'hwpx')]
    metadata = {role: original(path) for role, path, _ in files}
    current, current_member = official(files[0][1])
    history, history_member = official(files[1][1])
    if not current.keys() <= history.keys():
        raise ValueError('Current official nodes absent from history')
    for code, row in current.items():
        if row != history[code] and any(row[k] != history[code][k] for k in row if k != 'rowNumber'):
            raise ValueError('Current/history official content conflict')
    artifacts = []
    for role, path, encoding in files:
        meta = metadata[role]
        artifacts.append(dict(role=role, noticeUrl='https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000052',
                              downloadUrl=meta['url'], storageKey=str(path.resolve()), sha256=meta['sha256'],
                              bytes=meta['bytes'], collectedAt=meta['fetchedAt'], attachmentVersion='20261026',
                              memberPath=current_member if role == 'H_CURRENT_ZIP' else history_member if role == 'H_HISTORY_ZIP' else None,
                              encoding=encoding, usageStatus='PENDING', usageEvidence=None))
    nodes = []
    for row in history.values():
        row = dict(row, artifactRole='H_HISTORY_ZIP', status='LEGACY' if row['rawAbolishedOn'] else 'CURRENT')
        if (row['status'] == 'CURRENT') != (row['code'] in current):
            raise ValueError('Current/history status conflict')
        try:
            created = datetime.strptime(row['rawCreatedOn'], '%Y%m%d').date()
            abolished = datetime.strptime(row['rawAbolishedOn'], '%Y%m%d').date() if row['rawAbolishedOn'] else None
            if abolished and created > abolished:
                raise ValueError('Reversed dates')
            row.update(createdOn=created.isoformat(), abolishedOn=abolished.isoformat() if abolished else None,
                       dateStatus='VALID', dateIssue=None)
        except ValueError:
            row.update(createdOn=None, abolishedOn=None, dateStatus='INVALID', dateIssue='INVALID_OFFICIAL_DATE')
        nodes.append(row)

    # 접두어/이름으로 찾은 관계는 검토 후보로만 저장한다.
    provinces = {c: r for c, r in current.items() if c[2:] == '00000000'}
    middle = {c: r for c, r in current.items() if c not in provinces and c[5:] == '00000'}
    ward_parents = {}
    for code, row in middle.items():
        if row['district'] and ' ' in row['district']:
            candidates = [c for c, r in middle.items() if r['province'] == row['province'] and r['district'] == row['district'].split(' ')[0]]
            if len(candidates) != 1:
                raise ValueError('Ambiguous city container')
            ward_parents[code] = candidates[0]
    containers = set(ward_parents.values())
    navigation = []
    for code, row in current.items():
        province = code[:2] + '00000000'
        parent = None if code in provinces else code[:5] + '00000' if row['locality'] else ward_parents.get(code, province)
        branch = '출장소' in ''.join(str(row[k] or '') for k in ('province', 'district', 'locality'))
        if code in provinces:
            kind, role, selection_parent = 'PROVINCE', 'PROVINCE', None
        elif branch:
            kind, role, selection_parent = 'BRANCH', 'NONE', None
        elif row['locality']:
            kind = {'읍': 'EUP', '면': 'MYEON', '동': 'DONG'}.get(row['locality'][-1], 'UNKNOWN')
            role, selection_parent = 'LOCALITY', parent
        elif code in containers:
            kind, role, selection_parent = 'CITY_CONTAINER', 'NONE', None
        else:
            kind, role, selection_parent = 'GROUP', 'GROUP', province
        navigation.append(dict(code=code, kind=kind, parent=parent, province=province,
                               selectionParent=selection_parent, displayName=row['locality'] or row['district'] or provinces[province]['province'],
                               derivedName=not bool(row['locality'] or row['district']), role=role, selectable=False,
                               autoSelect=False, reviewStatus='PENDING', evidence=None))

    population_path = statistics_dir / 'population-202609-original.bin'
    store_path = statistics_dir / 'stores-original.bin'
    population_meta, store_meta = original(population_path), original(store_path)
    sources = []
    with population_path.open(encoding='cp949', newline='') as stream:
        for row in csv.DictReader(stream):
            if row['기준연월'] != '2026-09-30':
                raise ValueError('Unexpected population reference date')
            sources.append(dict(system='MOIS_RESIDENT', code=row['행정기관코드'], version='mois-202609-raw-v1',
                                name=row['읍면동명'], districtCode=None, districtName=row['시군구명'], referenceOn='2026-09-30',
                                url=population_meta['url'], evidence=json.dumps({'sha256': population_meta['sha256'], 'province': row['시도명']}, ensure_ascii=False),
                                collectedAt=population_meta['downloadedAt']))
    store_regions, industries = {}, {}
    row_count = 0
    with zipfile.ZipFile(store_path) as archive:
        for member in archive.namelist():
            if not member.lower().endswith('.csv'):
                continue
            with archive.open(member) as raw:
                for row in csv.DictReader(io.TextIOWrapper(raw, encoding='utf-8-sig', newline='')):
                    row_count += 1
                    if None in row or any(v is None for v in row.values()):
                        raise ValueError('Malformed store CSV row')
                    code = row['행정동코드']
                    data = (row['행정동명'], row['시군구코드'], row['시군구명'])
                    if code in store_regions and store_regions[code] != data:
                        raise ValueError('Conflicting store region source')
                    store_regions[code] = data
                    parent = None
                    for label, level in [('대', 'LARGE'), ('중', 'MIDDLE'), ('소', 'SMALL')]:
                        industry_code = row[f'상권업종{label}분류코드']
                        data = (row[f'상권업종{label}분류명'], level, parent)
                        if industry_code in industries and industries[industry_code] != data:
                            raise ValueError('Conflicting industry name/parent')
                        industries[industry_code] = data
                        parent = industry_code
    for code, (name, parent, parent_name) in sorted(store_regions.items()):
        sources.append(dict(system='SEMAS_STORE', code=code, version='semas-202609-raw-v1', name=name,
                            districtCode=parent, districtName=parent_name, referenceOn='2026-09-30', url=store_meta['url'],
                            evidence=json.dumps({'sha256': store_meta['sha256']}, ensure_ascii=False), collectedAt=store_meta['downloadedAt']))
    candidates = {}
    for code, row in history.items():
        if row['locality'] and code.endswith('00'):
            candidates.setdefault(code[:8], []).append(code)
    mappings = []
    for source in sources:
        targets = [source['code']] if source['system'] == 'MOIS_RESIDENT' and source['code'] in history else candidates.get(source['code'], [])
        if len(targets) != 1:
            raise ValueError('Missing/ambiguous mapping candidate')
        mappings.append(dict(system=source['system'], sourceCode=source['code'], sourceVersion=source['version'],
                             targetCode=targets[0], referenceOn=source['referenceOn'], reviewStatus='PENDING',
                             evidence='Official history candidate only; names, parent and temporal compatibility require review'))
    snapshot = dict(version=version, parserVersion='national-reference-builder-v1', policyVersion='candidate-only-v1',
                    collectedAt=max(population_meta['downloadedAt'], store_meta['downloadedAt']), classificationVersion='semas-202609-raw-v1',
                    classificationSourceUrl=store_meta['url'], classificationSourceSha256=store_meta['sha256'], referenceOn='2026-09-30',
                    artifacts=artifacts, nodes=nodes, navigation=navigation, sources=sources, mappings=mappings,
                    industries=[dict(code=c, name=n, level=l, parentCode=p, displayName=n, supported=False, displayOrder=0)
                                for c, (n, l, p) in sorted(industries.items())])
    return snapshot, dict(officialNodes=len(nodes), currentNodes=len(current), populationRegions=len(sources)-len(store_regions),
                         storeRegions=len(store_regions), storeRowsScanned=row_count, industries=len(industries),
                         mode='REVIEW_ONLY: not approved or published')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--regions', required=True, type=Path)
    parser.add_argument('--statistics', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Output already exists; use a new snapshot path')
    snapshot, report = build(args.regions, args.statistics, args.version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    report['snapshotSha256'] = sha(args.output)
    report['snapshotBytes'] = args.output.stat().st_size
    args.output.with_suffix('.report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
