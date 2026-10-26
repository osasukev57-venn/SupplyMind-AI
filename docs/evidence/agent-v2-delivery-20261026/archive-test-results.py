"""Archive raw Surefire files outside target; never overwrite an earlier run."""
import hashlib, json, shutil, sys, xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

repo = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
name = sys.argv[1]
if not name.replace('-', '').isalnum():
    raise SystemExit('simple run name required')
destination = evidence / (name + '-surefire')
destination.mkdir()  # Fail if this run was already archived.
source = repo / 'backend/target/surefire-reports'
records, skips, digests = [], [], []
selected = set(sys.argv[2].split(',')) if len(sys.argv) > 2 else None
for path in sorted(source.iterdir()):
    if not path.is_file():
        continue
    shutil.copy2(path, destination / path.name)
    digests.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    if not (path.name.startswith('TEST-') and path.suffix == '.xml'):
        continue
    root = ET.parse(path).getroot()
    if selected is not None and root.get('name', '').split('.')[-1] not in selected:
        continue
    record = {'suite': root.get('name'), 'tests': int(root.get('tests', 0)),
              'failures': int(root.get('failures', 0)), 'errors': int(root.get('errors', 0)),
              'skipped': int(root.get('skipped', 0)), 'timestamp': root.get('timestamp')}
    records.append(record)
    for case in root.findall('testcase'):
        skip = case.find('skipped')
        if skip is not None:
            skips.append({'suite': root.get('name'), 'test': case.get('name'),
                          'reason': skip.get('message') or skip.text or ''})
totals = {key: sum(r[key] for r in records) for key in ('tests', 'failures', 'errors', 'skipped')}
totals['passed'] = totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']
summary = {'archivedAt': datetime.now().astimezone().isoformat(), 'suites': len(records),
           **totals, 'skipReasons': skips, 'suiteResults': records, 'rawFileDigests': digests,
           'selectedSuites': sorted(selected) if selected is not None else 'ALL (use only after clean for a full-run count)',
           'note': 'Raw directory may contain earlier reports in a focused run; totals include selected suites only. Use named run log for command and source generation.'}
(evidence / (name + '-summary.json')).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: summary[k] for k in ('suites', 'tests', 'passed', 'failures', 'errors', 'skipped')}))
