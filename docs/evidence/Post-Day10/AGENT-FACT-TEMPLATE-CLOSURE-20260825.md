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
- 真实Bailian `qwen-plus`连通性沿用已审1/1 PASS；本次包含复制演示业务事实的新版端到端请求因需要单独明确授权，当前未执行，不伪装PASS。

## Preservation

- Tool count仍为7；write tool仍为NONE。
- 无数据库、RAG、向量库、本地大模型或新Provider。
- API Key未写入源码、日志、证据或制品。
- 用户`测试运行/`目录未修改。