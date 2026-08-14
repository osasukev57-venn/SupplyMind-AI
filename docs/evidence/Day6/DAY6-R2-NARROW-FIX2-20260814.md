# Day 6 R2 Narrow Fix #2（2026-08-14）— Independent Attack Findings F1-F4

> 性质：D6 Stage Review=`CHANGES_REQUESTED` 后的第二轮生产修复证据。
> Base：`07f5539`=HISTORICAL R2 FIX #1（INDEPENDENT ATTACK CHANGES_REQUIRED）；`03e82bc`=HISTORICAL FAILED STAGE CANDIDATE。
> 独立攻击：`8dc0c07`（INDEPENDENT ATTACK，20 targeted tests / 14 PASS / 6 FAIL→归并为 F1-F4 四个生产根因）。
> 框架不变：Java 17 / Spring Boot 3.5.15 / Spring AI 1.1.8（DEC-060）。未改 DEC-060、未升级框架、未引入 RAG/Vector/MCP/Database、未开始 Day7。

## F1 — 不可信模型文本不进入 degradeReason/持久化报告（PASS）

- `AgentResponseVerifier` 的拒绝原因全部改为受控 reasonCode：`SECRET_INJECTION`、`UNKNOWN_FACT_REFERENCE`、`UNKNOWN_EVIDENCE_REFERENCE`、`FABRICATED_NUMBER`（不再携带模型数字/文本）。
- degradeReason 仅由受控 code 组成（MODEL_RESPONSE_REJECTED:* / TOOL_EXECUTION_REJECTED / UNKNOWN_TOOL / LLM_UNAVAILABLE / chat_failed_<class>）；模型异常文本、tool raw arguments、编造数字永不进入正式 report。
- 攻击验证：Formal Claim Fabrication / Formal Answer Fabrication / Injected Secret → claims/answer/EvidencePack/持久化 report JSON 均不含模型编造内容或 secret（ResponseAttack 5/5 PASS）。

## F2 — unknown fact/evidence 引用彻底 fail-closed（PASS）

- 模型 draft 引用校验双通道：(a) 结构化 claims[] 的 factIds/evidenceRefs 必须存在于当前 EvidencePack；(b) 自由文本扫描 `fact-*` 与 `raw|processed|staging|warning|config/...` 引用——任一 unknown → 整个 draft REJECTED → JAVA_TEMPLATE。
- 不"丢掉未知引用继续接受剩余文本"；mixed valid+invalid / empty-refs-with-numeric-claim 均 fail-closed（ResponseAttack unknown-reference 测试 PASS）。

## F3 — 真实 ChatClient tool-calling 生命周期（PASS）

- `SpringAiLlmService` 构造时通过 `ToolCallAdvisor.builder().toolCallingManager(ToolCallingManager.builder().build())` 注册官方 advisor（与 Spring Boot autoconfigure 等价）；ChatClient 两阶段真实成立：模型选工具 → ToolCallback 执行真实 adapter → 工具结果回传模型 → 模型最终响应。
- request-scoped 恰好 7 个 ToolCallback；context 中其他 ToolCallback bean（如 dangerous.write）不泄漏（SpringAiAttack exact-seven PASS）。
- unknown tool（backfill.start）→ ToolCallingManager 抛 "No ToolCallback found for tool name: ..." → SpringAiLlmService 映射为受控 `UNKNOWN_TOOL` → fallback；invalid args → adapter REJECTED ToolResult 回传模型 + `ToolExecutionLedger`（SupplyMind 可信层）记录 → `TOOL_EXECUTION_REJECTED` → fallback。
- 生产验证测试 `AgentF3LifecycleVerificationTest` 3/3（两阶段 lifecycle、invalid args ledger、unknown tool 非 SUCCESS）。
- 攻击验证：realChatClientToolLifecycle（history+warning 两工具模型选择）PASS、invalidModelToolArguments PASS、unknownAndRejectedModelToolCalls PASS。

## F4 — calculationVersion 等 lineage 真实透传（PASS）

- `ToolResult.Lineage`（calculationVersion/calendarVersion/configVersions/actualSourceName/sourceFingerprint/validationVersion）由 adapters 从真实 production 记录填充：daily 行（calculationVersion/calendarVersion/configVersions/validationVersion + CanonicalJsonV1 计算的 sourceFingerprint）、aggregate 行（含真实 sourceFingerprint）。
- EvidencePack fact 与 evidenceRef 均携带 lineage；无占位符（"v1"/"default" 等）；缺真实值则 fail-closed（evidence 不进 LLM context）。
- Golden evidence JSON（固定 SHA）与 lineage 断言 PASS（GoldenEvidenceTest、EvidenceReportAttack formalFactsCarryEveryRequiredNonPlaceholderLineageField）。

## 独立攻击结果（8dc0c07 原样执行，未修改）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| targeted tests | 20 | **20** |
| failures | 6 | **0** |
| errors | 0 | 0 |
| skipped | 0 | 0 |

## Regression（真实执行，`mvn clean test`，Java 17 / Boot 3.5.15 / Spring AI 1.1.8）

| 指标 | 结果 |
|---|---|
| suites (classes) | 90（89 R2#1 + 1 新 F3 验证 suite） |
| tests | 447（444 + 3 新 F3 验证） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与 Day5/Day6 基线逐项相同：7 真实联网/raw 门禁 + 1 AT-TIME-003/004 D10；Day1-Day5 核心测试 0 丢失、0 新增 skip） |

- 保护项未回归：Secret Injection、Exactly 7 Request Tools、Invalid Evidence To LLM、FORMAL Demo Isolation、Evidence Golden JSON、Evidence Tamper、Report Restart Read/Body Tamper/Manifest Tamper/Identity/Evidence Reverify、Cost Production Reuse、Fallback Deterministic Only（全部 UNCHANGED_PASS）。
- No Secret：F1/F2 修复后 secret 与模型编造内容均不落盘 report/EvidencePack/degradeReason；Cloud 配置环境变量注入。
- No Database：无任何数据库栈。

## 状态

- Day6 Development Tasks=`ALL_DONE`（实施侧）；Day6 Final Acceptance=implementation-side PASS；Day6 Stage Review=`CHANGES_REQUESTED`（R2_FIX_IN_PROGRESS，待独立 attack review）；Day 6=`NOT_COMPLETE`。
- `03e82bc`=HISTORICAL FAILED STAGE CANDIDATE、`07f5539`=HISTORICAL R2 FIX #1、`8dc0c07`=INDEPENDENT ATTACK（20 targeted/6 failures）均历史保留。
