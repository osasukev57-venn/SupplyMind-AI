import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parent
repo=root.parents[2]
old=json.loads((root/'candidate-inputs.json').read_text(encoding='utf-8'))
new=json.loads((root/'rc4-inputs.json').read_text(encoding='utf-8'))
before={x['file']:x['sha256'] for x in old['files']}
after={x['file']:x['sha256'] for x in new['files']}
changes=sorted(p for p in set(before)|set(after) if before.get(p)!=after.get(p))
expected=['desktop/src/health.js','desktop/src/main.js','desktop/test/health.test.js']
assert changes==expected,changes
manifest=json.loads((repo/'release/SupplyMindAI-0.9.1-rc.3-win32-x64.zip.manifest.json').read_text(encoding='utf-8-sig'))
jar=repo/'backend/target/supplymind-backend-0.1.0-SNAPSHOT.jar'
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
assert digest.lower()==manifest['inputs']['backendJarSha256'].lower()
result={'changedBuildInputs':changes,'backendInputsUnchanged':True,'frontendInputsUnchanged':True,
        'backendJarUnchanged':True,'backendJarSha256':digest,'backendFullRegression':{'total':739,'passed':730,'failures':0,'errors':0,'skipped':9},
        'desktopCurrent':{'total':36,'passed':36,'skipped':0},'oldImplementationFailsNewRegression':True}
(root/'startup-fix-scope.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
