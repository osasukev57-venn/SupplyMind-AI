# Day 8 Final Stage Findings Fix（2026-08-17，HISTORICAL / SUPERSEDED）

> 本文件记录 `4ed6060` 时点，计数与结论不再是当前最终状态；已由 `239d025` 及 `DAY8-FINAL-TECHNICAL-CLOSURE-20260818.md` 取代。保留仅用于审计时间线。

> 性质：Day8 Final Stage Review 5 个 MAJOR 修复证据（Batch 执行模式）。
> Base：`80e58d7`（D8-T05 Stage Candidate Preparation）。
> 执行：M1→M2→M3→M4→M5 顺序，仅修复 findings，未改动 Day1～Day7 冻结业务语义、DEC-061 语义，未新增数据库/Agent 工具/写工具/RAG/Vector/MCP。

## M1 — 真实动态配置闭环（FIXED）

### 1. PbocOfficialWebDataProvider 动态目标
- **问题**：`supports()` 对 exchange-rate 返回 true，但 `collect()` 用 USD/EUR 固定 `SUPPORTED_ITEM_IDS`，GBP 真实 Provider 返回 UNSUPPORTED_TARGET；测试用匿名假 DataProvider。
- **修复**：
  - 删除固定 itemId 集合；`collect()` 按**活动配置元数据**（providerType/accessMethod/rateKind/sourceIntent/route/actualSourceName/currency/sourceFieldKey）解析目标（`resolveConfiguredPbocTarget`/`isConfiguredPbocTarget`）。
  - `PbocAnnouncement` 改为 `rateByAnchor` map（按 `1X对人民币` 锚点提取全部币种）；`createRawReceipt` 按 `item.sourceFieldKey()` 取 rawValue，禁止 itemId 猜币种。
  - 配置锚点缺页面 → 该目标 fail-closed（`CONFIGURED_ANCHOR_NOT_ON_PAGE`，不造数/不回退旧值/不冒充）；USD/EUR 请求缺失仍整链 PARSE_REJECTED（acquisition-only，D1-T04 语义保持）。
  - DEC-056 raw-first 保持：完整 HTTP entity bytes 先落 RawAcquisition 再解析；多币种共享 acquisition、run/raw/timeline 独立。
  - 测试：`PbocDynamicTargetAttackTest` 6/6（真实 provider + stub transport + GBP fixture）；既有 `PbocOfficialWebDataProviderContractTest` 7/7 保持。

### 2. ADD/REPLACE 自动链
- **问题**：backfill 范围为空 → 0 任务；有范围也只 WAITING 不自动执行；ConfigView 替换固定 null/null。
- **修复**（`DynamicConfigWorkflowService.runIntakeChain`）：
  - **当前值采集始终尝试**（today 单日 job 经真实 orchestrator 自动 run），不依赖范围是否填写。
  - 有完整范围 → 自动 `createOrResume` + `run`（acquisition→validation→publish→daily→aggregate）。
  - 单边范围 → DTO 400（both-or-neither）。
  - Manual 替换自动创建并启动 → 诚实 `AWAITING_MANUAL_INPUT`（不伪造自动完成）。
  - ConfigView：替换表单**历史回填范围必填**；页面展示每个 job 的 itemId:status。
- **测试**：`DynamicConfigWorkflowServiceTest` 12/12（新增：无范围仍当前采集、替换不再 0 jobs、Manual 诚实、单边 400）；前端 `config.spec.ts` 更新。

### 3. ConfigHistoryQueryService
- **问题**：只验证 manifest 就称 "decode verification"。
- **修复**：manifest 验证后**实际 decode MonitorSeriesConfigV1** 且校验文件名 configVersion == 正文 configVersion；decode/schema/version 不匹配 → verified=false。

## M2 — DEC-061 SHA Binding（FIXED）

- **问题**：`isAcknowledged()`/`read()` 只验证 ack 自身 manifest，未绑定原 warning。
- **修复**：`WarningAckStore.readVerified(warning)` 成为**唯一权威验证入口**（WarningQueryService.isAcknowledged 与 WarningController 全部复用）：
  1) ack 文件+manifest；2) decode；3) schema/status；4) warningId 相等；5) warningRef == 规范 ref；6) 原 warning 存在；7) 原 manifest 验证；8) decode 原记录；9) 原 warningId 匹配；10) 重算原 warning SHA-256；11) 与 ack.warningFileSha256 **精确相等**。
  - 任一失败 → StorageException fail-closed（API 400 REJECTED，无 500/堆栈）；同步替换 sidecar+manifest 也无法得到 acknowledged=true。
  - `acknowledgedAt` 改由**注入的 Clock** 生成（Controller 不再 `OffsetDateTime.now()`）。
- **测试**：`WarningAckStoreTest` 14/14（新增 7 个 M2 攻击：篡改 warningId/ref/SHA + 重写 manifest、篡改原 warning + 重写 manifest、缺原 manifest、同步替换 sidecar+manifest）；`WarningApiMvcContractTest` 6/6。

## M3 — Agent 工作台 DoD（FIXED）

- **问题**：evidenceRefs 纯文本不可点击；recommendations/计算口径/风险视图缺失。
- **修复**：
  - `AgentQueryResponse` 新增受控 `EvidenceLinkView`（evidenceId/evidenceType/itemId/businessDate/periodStart/periodEnd/grain/targetView(HISTORY|WARNING|QUALITY)/route/query）——**仅从 VERIFIED EvidencePack 元数据投影**，MISSING/INVALID/UNAVAILABLE 不产生链接；禁止前端解析文件路径；route/query 携带可重执行查询的参数。
  - 新增 `CalculationBasisView`（validationVersion/calculationVersion/calendarVersion/configVersions，从 lineage-complete VERIFIED 条目投影）与 `RiskView`（仅 Java/EvidencePack/Warning 事实）。
  - AgentView：RouterLink 渲染可点击导航；展示 recommendations/计算口径/风险视图；JAVA_TEMPLATE 降级仍显示相同 EvidencePack。
- **测试**：`AgentApiMvcContractTest` 3/3（含非 VERIFIED 不生成链接）；前端 `agent.spec.ts` 6/6（RouterLink href、recommendations、计算口径、无绝对路径）。

## M4 — 正式 Web P0 Evidence（归档）

- 浏览器真实执行：替换自动链（M1-WEB-01 PASS）、重启恢复（M1-WEB-02 PASS）、Agent 工作台（M3-WEB-01 PASS）、预警 demo 求值（D8T02-WEB-01 PASS）。
- 归档：`docs/evidence/Day8/artifacts/`（config-page/m1-replace-auto-chain/m3-agent-workbench/warning-page 截图、restart-recovery-api.txt、web-p0-runner-summary.json + SHA256-MANIFEST.txt）。
- console 唯一错误为 favicon 404（非业务错误）；无绝对路径/秘密泄漏。

## M5 — 状态一致性

- docs/04、docs/05：D8-T01～D8-T05=`REVIEW_PENDING`；Day8=`NOT_COMPLETE`；Feature Freeze=`PENDING_FINAL_STAGE_REVIEW`；Day9=`NOT_STARTED`；未 merge main。
- 最终 SHA-256 与回归数字见 Final Stage Review Candidate 文档。

## 回归

- 后端 `.\mvnw.cmd clean test`：**116 suites / 625 tests / 0 failures / 0 errors / 8 skipped**（8 skipped 与基线逐项相同）
- 前端 `npm run test`：**30/30 PASS**；`npm run build`：PASS
- 测试后 `backend/data` 无残留
