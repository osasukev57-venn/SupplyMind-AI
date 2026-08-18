# Day 8 T03 — 工业供应链 Agent 工作台（2026-08-17）

> 性质：D8-T03 实施证据（Batch 执行模式，`TaskExecutionStatus=REVIEW_PENDING`）。
> Base：`9c88a84`（D8-T02，integration/day8）。
> 依赖：D6-T01～D6-T05（七只读工具/EvidencePack/LLMService/模板降级）、D8-T02、D7-T01 均 DONE。

## 1. 范围与边界

D8-T03 将既有 D6 Agent 后端（`AgentQueryController` + `AgentOrchestrator` + 七只读工具 + EvidencePack + Java 模板降级）以 Web 工作台形态交付。**后端业务链零修改**——只向后兼容扩展 API 响应 DTO：

| 复用（零修改） | 用途 |
|---|---|
| `AgentOrchestrator`/`ToolExecutor`/七 Tool Adapter | 受控工具调用编排（D6-T01） |
| `EvidencePackV1`/`EvidenceRefVerifier` | 证据所有权与引用核验（D6-T02） |
| `LLMService`+Spring AI adapter / `TemplateFallbackService` | LLM 或 Java 模板降级（D6-T03/T05） |
| `AgentReportV1`/`ReportStore` | 报告持久化（D6-T04） |
| 前端 `client.ts`/`StatusBadge`/router | 基础（Day7 集成文件修改，含回归） |

**新增**：
- `AgentQueryResponse` **向后兼容扩展**（原 requestId/answer/llmStatus/degraded/degradeReason/toolTrace/evidenceRefs/reportRef/facts 全部保留；新增 generatedBy/provider/model/scope/limitations/recommendations/claims/dataThrough）——全部从**已验证** `AgentReportV1`/`EvidencePackV1` 映射，前端绝不重推风险/建议/结果；`answer` 不再退化为"首条 claim"（优先完整 explanation）
- `AgentQueryController`：改用 `AgentQueryResponse.of(result)` 统一映射（零业务改动）
- 前端：`AgentView.vue` + `api/agent.ts` + `types/agent.ts` + router/导航
- 测试：`AgentApiMvcContractTest`（正式 @WebMvcTest）+ `AgentApiTest` 扩展断言 + 前端 `agent.spec.ts`

**禁止项已遵守**：不新增 Agent 工具、不改 EvidencePack/AgentReport schema、不泄露本地绝对路径（evidenceRefs 均为 dataRoot 相对引用）、真实 Cloud 未运行时继续 NOT_RUN/PENDING_EXTERNAL 语义（测试用 stub 失败 → JAVA_TEMPLATE，诚实标注）、前端零计算。

## 2. API 契约（扩展，向后兼容）

```
POST /api/agent/query  body {question,itemId?,startDate?,endDate?,grain?,periodStart?,periodEnd?,month?,businessDate?,mode?}
→ {requestId, answer, llmStatus, degraded, degradeReason,
   toolTrace[{invocationIndex,toolName,toolVersion,readOnly,input,output,status,evidenceRefs}],
   evidenceRefs[], reportRef, facts[{factId,statement,value,businessDate,period,validationStatus}],
   generatedBy, provider, model,
   scope{itemIds[],businessDate,periodStart,periodEnd,timezone},
   limitations[], recommendations[], claims[{claimId,text,evidenceRefs[]}], dataThrough}
```

- `generatedBy`=`JAVA_TEMPLATE`/`LLM`（与 report 一致，真实展示）；`degraded/degradeReason` 如实
- `dataThrough` 取自 EvidencePack facts 的 max(businessDate)，后端派生
- 错误契约保持 D6：缺 question → 400 `{status:REJECTED,message}`；无 500/堆栈泄漏

## 3. 测试结果（真实执行）

### 后端

| 套件 | 结果 |
|---|---|
| `AgentApiMvcContractTest`（新增） | 2/2 PASS（缺 question 400；扩展响应 200：D6 字段保留 + generatedBy/scope/limitations/claims/dataThrough 断言） |
| `AgentApiTest`（扩展） | 2/2 PASS（真实 orchestrator + 失败 ChatModel stub → degraded=true、generatedBy=JAVA_TEMPLATE、claims 非空且 evidenceRefs 非空、dataThrough/limitations 映射） |
| 既有 Agent 套件（攻击/边界/工具） | 全部 PASS（零回归） |

关键断言：`JAVA_TEMPLATE` 诚实标注；claims 的 evidenceRefs 恒非空（可追溯）；limitations 含 fallback 说明；scope 从 EvidencePack 映射。

### 前端（`npm run test` / `npm run build`）

| 项 | 结果 |
|---|---|
| 新增 `agent.spec.ts` | 4/4 PASS（渲染 facts/时间线/claims/模板标签、fallback limitation、失败不白屏、空问题前端拒绝不发请求） |
| 既有 3 个 spec | 23/23 PASS（回归保持） |
| `vue-tsc --noEmit && vite build` | PASS |

## 4. 回归

- 后端全量 `.\mvnw.cmd clean test`：**115 suites / 607 tests / 0 failures / 0 errors / 8 skipped**（D8-T02 后 113/605 → +2 suites/+2 tests）
- 前端 `npm run test`：**27/27 PASS**；`npm run build` PASS

## 5. 保持的冻结决策

- C21（七只读工具精确冻结）、C22（LLM 不判数）、C36（降级边界）、DEC-060（Spring AI 边界）
- AGENT-EVIDENCE-SCHEMA-V1 / AgentReport schema 未改
- 真实 Cloud gated run 保持 NOT_RUN/PENDING_EXTERNAL（本地 stub 失败矩阵验证降级路径，不伪报 PASS）
- Day1-Day6 代码零修改；Day7 仅 router/导航/API client 集成修改（含回归）

## 6. 状态

- D8-T03 = `TaskExecutionStatus=REVIEW_PENDING`（Batch 执行模式下不阻止 D8-T04 继续）
- Day8 未 COMPLETE；Feature Freeze 未 EFFECTIVE；未 merge main；未开始 Day9
