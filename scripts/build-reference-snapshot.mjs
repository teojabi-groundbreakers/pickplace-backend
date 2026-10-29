import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(process.argv[2] ?? '../data-validation');
const sources = [];
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function source(relative, metadata) {
  const bytes = await readFile(path.join(root, relative));
  const meta = JSON.parse(await readFile(path.join(root, metadata), 'utf8'));
  assert.equal(hash(bytes), meta.sha256, `Source hash mismatch: ${relative}`);
  sources.push({ file: relative, sha256: meta.sha256, url: meta.datasetUrl ?? meta.url,
    collectedAt: meta.downloadedAt ?? meta.fetchedAt, parameters: meta.parameters });
  return { bytes, meta };
}
const districtRaw = await source('2026-10-15/raw/district.json', '2026-10-15/raw/district.meta.json');
const dongRaw = await source('2026-10-15/raw/dong.json', '2026-10-15/raw/dong.meta.json');
await source('2026-10-16/sources/jscode20261012.zip', '2026-10-16/sources/jscode20261012.zip.meta.json');
await source('2026-10-16/sources/jscode20261012-history.zip', '2026-10-16/sources/jscode20261012-history.zip.meta.json');
const reportBytes = await readFile(path.join(root, '2026-10-16/region-report.json'));
sources.push({ file: '2026-10-16/region-report.json', sha256: hash(reportBytes), kind: 'derived-code-cross-check' });
const report = JSON.parse(reportBytes);
const districts = JSON.parse(districtRaw.bytes).DATA.map(row => ({ code: row.signgu_cd,
  name: row.signgu_nm, collectedAt: districtRaw.meta.downloadedAt })).sort((a, b) => a.code.localeCompare(b.code));
const parents = new Map(districts.map(row => [row.code, row.name]));
const dongs = JSON.parse(dongRaw.bytes).DATA.map(row => {
  const mapping = report.mappings.find(item => item.sourceCode === row.adstrd_cd);
  assert.ok(mapping?.parentNameMatches && parents.get(mapping.districtCode) === mapping.districtName);
  assert.equal(mapping.sourceName, row.adstrd_nm);
  const official = mapping.official ?? mapping.historical;
  assert.ok(official && parents.get(mapping.districtCode) === official.parts[1]);
  const date = value => value ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}` : null;
  return { code: row.adstrd_cd, name: row.adstrd_nm, districtCode: mapping.districtCode,
    officialCode: official.code, officialName: official.parts[2], status: mapping.official ? 'CURRENT' : 'LEGACY',
    createdOn: date(official.createdAt), abolishedOn: date(official.abolishedAt), collectedAt: dongRaw.meta.downloadedAt };
}).sort((a, b) => a.code.localeCompare(b.code));
const supported = new Map([['CS100010', ['커피·음료', 1]], ['CS100008', ['분식', 2]], ['CS100005', ['제과점', 3]]]);
const files = (await readdir(path.join(root, '2026-10-15/store-partitions'))).filter(name => /^CS\d{6}\.json$/.test(name)).sort();
const businessTypes = [];
for (const file of files) {
  const { bytes, meta } = await source(`2026-10-15/store-partitions/${file}`, `2026-10-15/store-partitions/${file.replace('.json', '.meta.json')}`);
  const rows = JSON.parse(bytes).DATA.filter(row => row.stdr_yyqu_cd === '20262');
  const code = file.replace('.json', '');
  assert.ok(rows.length && rows.every(row => row.svc_induty_cd === code));
  const names = [...new Set(rows.map(row => row.svc_induty_cd_nm))];
  assert.equal(names.length, 1);
  businessTypes.push({ code, sourceName: names[0], displayName: supported.get(code)?.[0] ?? names[0],
    supported: supported.has(code), displayOrder: supported.get(code)?.[1] ?? 0, collectedAt: meta.downloadedAt });
}
assert.equal(districts.length, 25);
assert.equal(dongs.length, 425);
assert.equal(businessTypes.length, 100);
assert.equal(new Set(dongs.map(row => row.code)).size, 425);
assert.equal(dongs.filter(row => row.status === 'LEGACY').length, 3);
const snapshot = { version: 'seoul-reference-20262-mois-20261012-v1', officialSnapshotOn: report.officialDate, districts, dongs, businessTypes };
const snapshotText = JSON.stringify(snapshot, null, 2) + '\n';
const manifest = { version: snapshot.version, statisticsQuarter: '20262', officialSnapshotOn: report.officialDate,
  snapshotSha256: hash(Buffer.from(snapshotText)), attribution: '서울 열린데이터광장 / 서울신용보증재단 / 행정안전부',
  note: 'Frozen verified snapshot, not a claim of latest boundaries. Derived mapping report retained separately.', sources };
const manifestText = JSON.stringify(manifest, null, 2) + '\n';
if (process.argv[3] === '--check') {
  assert.equal(await readFile('src/main/resources/reference-data/seoul-reference.json', 'utf8'), snapshotText);
  assert.equal(await readFile('docs/data/reference-source-manifest.json', 'utf8'), manifestText);
  console.log('Verified 25 districts, 425 dongs, 100 business types and all original source hashes.');
} else {
  console.log(JSON.stringify({ snapshotText, manifestText, sha256: manifest.snapshotSha256 }));
}
