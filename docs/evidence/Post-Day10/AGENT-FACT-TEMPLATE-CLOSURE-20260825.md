# Agent Fact Template Closure — 2026-08-25

- Implementation：`dfb5158`
- Decision：`DEC-066 EFFECTIVE`
- Scope：仅修复 Agent 云端语言组织合同；Java事实、只读工具、EvidencePack、AgentReport、预警裁决与Day1–Day10业务规则不变。

## 最终链路

`已发布/已校验文件与有效预警证据 → Java受控工具 → StandardFactPack → 云端answerTemplate + usedFactIds → Java校验占位符集合 → Java按factId注入数值/日期/单位/来源/趋势/风险/确认状态 → Java逐事实复核 → Java回绑evidenceRefs → AgentReport`

云端不能直接生成业务字面量、证据路径或hash。未知、缺失、重复、悬空占位符，模板外伪造数值，或与Java趋势/风险/确认事实矛盾的语言均fail-closed并显示Java事实报告。

## Verification

- Agent定向合同与既有攻击：30/30 PASS；新增事实模板主流程与攻击：7/7 PASS。
- 新增攻击：模板外`999999.999`不得通过；`usedFactIds`与占位符集合不闭合不得通过。
- Backend full regression：131 suites / 673 tests / 0 failures / 0 errors / 9 skipped。
- Frontend：6 files / 39 tests / 0 failures；build PASS。
- Desktop：31/31 PASS。
- 真实Bailian `qwen-plus`业务事实端到端Gate：`PASS`（2026-08-25 14:46 +08:00）。使用隔离数据副本与当前源码JAR，Java先生成标准事实，云端返回事实模板，Java渲染并复核后得到 `generatedBy=LLM`、`degraded=false`、`limitations=[]`。
- 真实请求调用 `series.resolve`、`history.query`、`quality.inspect`、`provenance.trace` 四个只读工具，全部 `SUCCESS`；报告包含1条已验证claim、7条evidenceRef，数据截至 `2026-08-24`。
- 机器可读摘要：`docs/evidence/Post-Day10/AGENT-FACT-TEMPLATE-REAL-CLOUD-20260825.json`。API Key未输出、未写入请求体、日志、证据或制品。

## Preservation

- Tool count仍为7；write tool仍为NONE。
- 无数据库、RAG、向量库、本地大模型或新Provider。
- API Key未写入源码、日志、证据或制品。
- 用户`测试运行/`目录未修改。
