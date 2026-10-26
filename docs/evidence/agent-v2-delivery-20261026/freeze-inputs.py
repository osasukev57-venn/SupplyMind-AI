"""Hash build/test inputs, not environment variables or user runtime data."""
import hashlib, json, sys
from datetime import datetime
from pathlib import Path
repo = Path(__file__).resolve().parents[3]
destination = Path(__file__).resolve().parent / sys.argv[1]
paths = set()
for folder in ('backend/src', 'frontend/src', 'desktop/src', 'desktop/test', 'desktop/scripts'):
    paths.update(p for p in (repo / folder).rglob('*') if p.is_file())
for name in ('backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
             'frontend/tsconfig.json', 'frontend/vite.config.ts', 'desktop/package.json',
             'desktop/package-lock.json', '.gitattributes'):
    path = repo / name
    if path.is_file():
        paths.add(path)
entries = [{'file': p.relative_to(repo).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
           for p in sorted(paths)]
payload = {'at': datetime.now().astimezone().isoformat(), 'files': entries, 'count': len(entries)}
if destination.exists():
    previous = json.loads(destination.read_text(encoding='utf-8'))
    if previous['files'] != entries:
        old = {r['file']: r['sha256'] for r in previous['files']}
        changed = [r['file'] for r in entries if old.get(r['file']) != r['sha256']]
        raise SystemExit('Frozen input mismatch: ' + ', '.join(changed))
    print(f'All {len(entries)} frozen inputs unchanged')
else:
    destination.write_text(json.dumps(payload, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Frozen {len(entries)} inputs')
