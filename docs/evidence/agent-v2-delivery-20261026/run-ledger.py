"""Summarize Maven terminal totals without replacing the immutable raw logs."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parent
out=[]
for path in sorted(root.glob('*.log')):
    text=path.read_text(encoding='utf-8',errors='replace')
    matches=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*\r?\n',text)
    if not matches: continue
    total,fail,error,skip=map(int,matches[-1])
    out.append({'log':path.name,'total':total,'passed':total-fail-error-skip,'failures':fail,'errors':error,'skipped':skip,
                'result':'SUCCESS' if 'BUILD SUCCESS' in text else 'FAILURE_OR_INCOMPLETE',
                'finishedAt':(re.findall(r'Finished at: ([^\r\n]+)',text) or [None])[-1]})
(root/'run-ledger.json').write_text(json.dumps({'note':'Terminal Maven totals; only backend-full-final follows clean on final frozen sources. Historical runs are not final-candidate results. Compile failures without test execution remain in their raw logs.','runs':out},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(out,ensure_ascii=False))
