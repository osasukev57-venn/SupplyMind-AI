# Day 8 T04 — Web 形态 P0 预验收（2026-08-17）

> 性质：D8-T04 实施证据（Batch 执行模式，`TaskExecutionStatus=REVIEW_PENDING`）。
> Base：`ec2f0b5`（D8-T03，integration/day8）。
> 依赖：D8-T01/T02/T03 均已实现（REVIEW_PENDING）。

## 1. 执行方式

真实浏览器（Playwright → Vite dev :5173 → 后端 8080）执行 Web 形态 P0 链路；后端为完整打包 JAR + 独立 data-root（测试结束后已清理，无残留）。非 Electron/Windows 封装类 P0 用例逐一执行并保存证据。

## 2. 验收结果

| 用例 | 状态 | 证据 |
|---|---|---|
| AT-CFG-001 动态停用欧元 | **PASS**（浏览器操作：停用 → configVersion 1→2 → 面板隐藏 → 重启保持 enabled=False → history 2 快照均 verified） | 见 §4 |
| AT-CFG-002 新增 GBP（配置驱动） | **PASS**（/api/config/items POST capability 服务端校验；激活创建真实回填任务） | 后端 MVC/工作流测试 + API 直连 |
| AT-CFG-003 回填检查点/幂等 | **PASS**（既有 BackfillOrchestratorTest 真实链；D8-T01 workflow 测试 run→SUCCEEDED/daily 落盘） | D8-T01 evidence |
| AT-CFG-004 替换 AZ91D | **PASS**（workflow 测试：双来源意图独立 itemId/supersedesItemId/ADC12 保持） | D8-T01 evidence |
| AT-UI-001 面板重构 | **PASS**（停用 EUR 后 Dashboard 不再显示 EUR 卡片——配置驱动） | 浏览器快照 |
| AT-UI-002 旧项隐藏但历史可查 | **PASS**（修复后：停用 EUR 仍出现在历史查询选择器） | Finding F3 + retest |
| AT-ALT-001 确定性预警 | **PASS**（后端 WarningServiceTest/AT-ALT-001 既有；evaluate 无已发布数据 → NOT_TRIGGERED 诚实） | 浏览器 + API |
| AT-ALT-002 确认 sidecar/重启 | **PASS**（WarningAckStoreTest 7/7：sidecar/幂等/冲突/重启恢复/扫描隔离） | D8-T02 evidence |
| AT-SRC-007-DX Manual 受理 | **PASS**（浏览器手动录入 ADC12 → PENDING + runId/rawRef/timelineRef 真实落盘） | Finding F2 + retest |
| AT-AI Web 链（降级） | **PASS**（Agent 页无 LLM key → JAVA_TEMPLATE + TOOL_EXECUTION_REJECTED 诚实显示；真实 Cloud 保持 NOT_RUN） | 浏览器快照 |
| 重启恢复 | **PASS**（configVersion=2/EUR disabled/history verified 重启后保持） | API 直连 |

## 3. Findings（真实集成暴露，全部修复 + 回归）

### F1（BLOCKER→CLOSED）backfill 列表把 time-state 当 job 解码
- **发现**：真实启动后 `GET /api/backfill/jobs` → 400 "Backfill job fails its manifest: time-state"——D5-T01 的 `runtime/jobs/active/time-state.json` 与 backfill job 共用目录，`BackfillJobQueryService.list()` 未过滤。
- **修复**：只识别 `backfill-` 前缀 job 文件（`BackfillOrchestrator.JOB_ID_PREFIX`）；新增回归测试（time-state 共存时列表正常 + 真实 job 仍列出）。
- **Retest**：API 200 `{"jobs":[]}`；`DynamicConfigWorkflowServiceTest` 新增用例 PASS。
- 影响任务：D8-T01（其 query 服务缺陷）。

### F2（BLOCKER→CLOSED）手动录入生产缺 operator 身份
- **发现**：浏览器手动录入 → 400（被 advice 吞为通用消息）——根因 `supplymind.manual.operator-ref` 未配置，`OperatorContext.configured("")` fail-closed（正确安全行为，但 Web 形态不可用）。
- **修复**：`application.yml` 提供受控默认 `local-operator`（`SUPPLYMIND_MANUAL_OPERATOR_REF` 可覆盖；置空仍禁用；客户端始终不可指定——DEC-057 保持）。
- **Retest**：浏览器提交 ADC12 → `{status:PENDING, runId, rawRef, timelineRef}` 真实落盘。
- 影响任务：D8-T01 前端录入链路（真实边界已由 Day7 feaedd3 提供）。

### F3（MAJOR→CLOSED）历史页选择器不含已停用项
- **发现**：AT-UI-002 预期 6——停用 EUR 后 HistoryView 选择器无 EUR（原来用 `fetchOverview` 仅 enabled 项）。
- **修复**：选择器改用 `/api/config/items`（含 disabled 项）；新增前端回归测试（disabled item 出现在 option 列表）。
- **Retest**：浏览器历史页选择器含"欧元/人民币中间价"。
- 影响任务：D7-T03 页面（D8 直接需求：AT-UI-002 语义），含回归测试。

## 4. 浏览器证据（快照摘录）

- Dashboard：7 导航齐全；6 卡片（EUR/USD/4 材料）NO_DATA 诚实显示，无伪造值。
- 停用 EUR 后 Dashboard 无 EUR 卡片；configVersion=2。
- 历史页：缺失期间 2026-01~12 显式列出、未插值；修复后选择器含停用 EUR。
- 来源页：手动录入提交 → PENDING + 受理证据（runId/rawRef/timelineRef 显示）。
- 预警页：evaluate → NOT_TRIGGERED 诚实提示。
- Agent 页：JAVA_TEMPLATE + 降级原因 + 限制列表（series.resolve: itemId is required / fallback 说明）完整渲染。
- 重启后：configVersion=2、EUR disabled、history 2/2 verified。

## 5. 回归

- 后端全量 `.\mvnw.cmd clean test`：**116 suites / 608 tests / 0 failures / 0 errors / 8 skipped**（D8-T03 后 115/607 → +1 suite/+1 test；8 skipped 与基线逐项相同）
- 前端 `npm run test`：**28/28 PASS**（27+1 新：disabled item 选择器）；`npm run build` PASS
- 测试后 `backend/data` 无残留（全部临时 root 隔离；回归前已清理浏览器会话产物）

## 6. 状态

- D8-T04 = `TaskExecutionStatus=REVIEW_PENDING`（Batch 执行模式下继续 D8-T05 Stage Candidate 准备）
- Day8 未 COMPLETE；Feature Freeze 未 EFFECTIVE；未 merge main；未开始 Day9
- 真实 Cloud LLM 保持 NOT_RUN/PENDING_EXTERNAL（未伪报）
