# Day 6 R2 Stage Fix（2026-08-13）— Agent Stage Findings M1-M7

> 性质：D6 Stage Review=`CHANGES_REQUESTED` 后的 R2 生产修复证据（FAST-R0 实施侧）。
> Base：`03e82bc`=**HISTORICAL FAILED DAY6 STAGE CANDIDATE**（STAGE_REVIEW_CHANGES_REQUESTED）。
> 框架不变：Java 17 / Spring Boot 3.5.15 / Spring AI 1.1.8（DEC-060）。未改 DEC-060、未重跑 D6-T00、未开始 Day7、未引入数据库/RAG/Vector/MCP。

## M1 — LLM 输出不得直接成为正式事实（PASS）

- 新增 `ModelDraftV1`（UNTRUSTED MODEL DRAFT，rawText+claims）+ `ModelClaimV1`（claimId/text/factIds/evidenceRefs）+ `AgentResponseVerifier`。
- 校验规则：每个 claim 的 factIds/evidenceRefs 必须存在于当前 EvidencePack（unknown ref→REJECTED）；模型文本中的业务数值必须严格对应引用 fact 的 value（编造数字如 999999.999→`FABRICATED_NUMBER` REJECTED）；secret 注入（配置 apiKey/credential/Bearer/api-key pattern）→`SECRET_INJECTION` REJECTED。
- 任何 REJECTED → 整个 model result 不进入 formal answer/claims → JAVA_TEMPLATE fallback（degraded=true、degradeReason=MODEL_RESPONSE_REJECTED:*）。JAVA_TEMPLATE claims 由确定性 Java 从已验证 facts 生成（可信）。
- A16（编造数字/unknown evidence ref 拒绝）、A17（secret 注入→fallback 且不持久化）测试绿。

## M2 — EvidencePack 完整 lineage 与 FORMAL/DEMO 隔离（PASS）

- `EvidenceRefEntry` 新增 `status`（VERIFIED/MISSING/INVALID/UNAVAILABLE）+ `reasonCode`（UNSAFE_REF/FILE_NOT_FOUND/MANIFEST_MISMATCH/NO_SHA256）；仅 VERIFIED（含真实 sha256）作为可引用 evidence。
- 仅 VERIFIED refs 进入 LLM evidence context；MISSING/INVALID/UNAVAILABLE 只进入 limitations/notices（模型知道证据不足）。
- FORMAL mode 排除 demo/synthetic evidence（warning 证据在 EXT-07/08 确认前一律排除；`isDemoOrSynthetic`）。
- A18 golden JSON 确定性、A19 篡改→INVALID、A20 FORMAL 排除 demo warning 测试绿。

## M3 — 真实 Spring AI Tool Calling（PASS）

- `SpringAiLlmService` 使用 `ChatClient.toolCallbacks(ToolCallback...)` 每次 request 显式挂载**恰好 7 个** SupplyMind ToolCallbacks（request-scoped；context 中其他 ToolCallback bean 不泄漏）。
- 测试用 Spring AI `ToolCallingManager`（ToolCallAdvisor 同款官方 runtime）：模型选择 history.query→Spring AI 解析 ToolCallback→真实 HistoryQueryToolAdapter 执行→tool result 回传对话；模型选择 warning.explain→真实 adapter 执行（NO_DATA 诚实）。unknown tool（backfill.start）无 ToolCallback 注册→不可执行（A13/A14）。
- Java ToolExecutor 仅用于确定性的 fallback 查询策略，明确属于 FALLBACK PATH，不冒充 model-selected 路径。

## M4 — REJECTED Tool 请求强制安全失败（PASS）

- 任一 ToolResult=REJECTED → `toolChainClean=false` → LLM 交互不标 SUCCESS → degraded=true、degradeReason=TOOL_EXECUTION_REJECTED → JAVA_TEMPLATE。
- 不允许"Tool rejected + LLM success + formal non-degraded report"组合（A15）。

## M5 — ReportStore 重启读取与身份核验（PASS）

- 新增 `ReportStore.read(ref)`：合法 path 解析→manifest 校验→decode AgentReportV1→body reportId==filename identity→month 路径与 createdAt 一致→requestId 与 evidencePack 一致→evidenceRefs 重新核验。
- A21 restart read、A22 body/manifest 篡改→MANIFEST_MISMATCH fail-closed、A23 identity 漂移→IDENTITY_MISMATCH fail-closed 测试绿。
- StorageSchemaVerifier/ManifestDerivedFieldsVerifier 保持 report/YYYY-MM 严格命名空间，raw/staging/processed/warning 语义不变。

## M6 — cost.impact 复用生产计算（PASS）

- 提取共享 `CostImpactCalculator`（changeRatio=(current-previous)/previous, 12 位 HALF_UP；EXT-08 demo weight=1）到 production 层。
- `WarningService`（Day5 业务链）与 `CostImpactToolAdapter` 调用**同一组件**；Agent tool 无自有业务公式。
- A24：Agent cost.impact 结果 == 生产 CostImpactCalculator 输出。

## M7 — 攻击/验收覆盖 A13-A24（PASS）

A13 模型经 ChatClient 选工具并执行 production adapter；A14 unknown tool 无 callback 不可执行；A15 invalid args→fallback；A16 编造数字/unknown ref 拒绝；A17 secret 注入 fallback 且不落盘；A18 evidence golden JSON 确定性；A19 篡改→INVALID；A20 FORMAL 排除 demo；A21 report restart read；A22 report 篡改 fail-closed；A23 identity 漂移 rejected；A24 cost 生产复用。

## Regression（真实执行，`mvn clean test`，Java 17 / Boot 3.5.15 / Spring AI 1.1.8）

| 指标 | 结果 |
|---|---|
| suites (classes) | 89（87 R2 前 + 2 新 R2 suite：AgentR2StageFixAttackTest、AgentSpringAiToolCallingTest） |
| tests | 444（430 + 14 新 R2 测试） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与 Day5/Day6-R2前基线逐项相同：7 真实联网/raw 门禁 + 1 AT-TIME-003/004 D10；Day1-Day5 核心测试 0 丢失、0 新增 skip） |

- 保护项：Spring Boot 3.5.15 / Spring AI 1.1.8 未动；D6-T00=DONE 保持；Spring AI isolation（类型仅 infrastructure 层）；read-only boundary（快照不变测试仍绿）；path traversal BLOCKED；No Database；Day1-Day5 全套回归绿（含 ScheduledGuard/BackfillRange/HistoryQuery/Warning a6/a7）。
- No Secret：A17 验证 secret 不进 report/EvidencePack；Cloud 配置环境变量注入。

## 状态

- D6-T01~T05=实施完成（docs/04 状态 DONE）。
- Day6 Development Tasks=`ALL_DONE`（实施侧）；Day6 Final Acceptance=implementation-side PASS；Day6 Stage Review=`CHANGES_REQUESTED`（R2 fix 完成，待独立 attack review）；Day 6=`NOT_COMPLETE`。
- `03e82bc`=HISTORICAL FAILED DAY6 STAGE CANDIDATE（历史保留）。Cloud LLM 真实网络=`PENDING_EXTERNAL`（EXT 未确认，未伪造）。
