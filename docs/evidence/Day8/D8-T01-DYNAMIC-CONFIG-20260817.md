# Day 8 T01 — 动态监测配置与历史回填闭环（2026-08-17）

> 性质：D8-T01 实施证据（Batch 执行模式，`TaskExecutionStatus=REVIEW_PENDING`）。
> Base：`f79e309`（Day7 final closure，integration/day8）。
> 依赖：D3-T01、D4-T01、D5-T04、D7-T01 均 `DONE`（既有能力复用，零重复实现）。

## 1. 范围与边界

D8-T01 目标是让"新增/停用/替换监测项 → 原子激活 → 回填任务 → 面板重构 → 旧历史保留"全链路在 Web 形态可用。**后端只新增薄编排与查询层**，业务链全部复用既有冻结服务：

| 复用（零修改） | 用途 |
|---|---|
| `ConfigManagementService` | ADD/ENABLE/DISABLE/REPLACE 原子激活（configVersion+1、history 不可变快照、capability Gate） |
| `BackfillOrchestrator` + `BackfillJobStore` | 真实回填链：acquisition→validation→publish→daily→aggregate、检查点、幂等、AWAITING_MANUAL_INPUT |
| `DataProviderRegistry`/`DataProvider` | capability 判定与采集（服务端完整校验） |
| `PublishedQueryService`/`HistoryQueryService` | 面板/历史查询（校验前不可见、旧历史可查） |

**新增（最小）**：
- `com.supplymind.config.api.ConfigV1`：受控请求/响应 DTO（前端不得提交 configVersion/routeEffectiveAt/supersedesItemId/审计时间）
- `com.supplymind.config.DynamicConfigWorkflowService`：应用层编排（激活后按请求创建回填任务；retry 通过既有 JobStore 重置 FAILED→WAITING 后经 Orchestrator 恢复）
- `com.supplymind.config.ConfigHistoryQueryService`：config/history 只读审计（逐文件 manifest 校验，损坏显式 issue）
- `com.supplymind.backfill.BackfillJobQueryService`：runtime/jobs 只读列表（manifest 校验）
- `ConfigController`（/api/config：items/history/capabilities/items POST/items/{id}/enabled/replace）
- `BackfillController`（/api/backfill：jobs/jobs/{id}/jobs POST/jobs/{id}/run/jobs/{id}/retry）
- `ConfigApiAdvice`：统一 400 `{status:REJECTED,message}`，无 500/堆栈/路径泄漏
- `DynamicConfigConfiguration`：Spring 装配（BackfillOrchestrator 首次纳入生产 bean）
- 前端：`ConfigView.vue` + `api/config.ts` + `types/config.ts` + router/导航（Day7 集成文件修改，含回归）

**禁止项已遵守**：Controller 不读文件系统、不编排跨模块业务、不构造 configVersion/routeEffectiveAt/任务ID；前端不计算业务值；不新增第二套 config/backfill 存储；Day1-Day7 冻结语义零修改。

## 2. API 契约（新增，均经正式 MVC contract 测试）

```
GET  /api/config/items                     -> ConfigView{schemaVersion,configVersion,mode,updatedAt,items[]}
GET  /api/config/history                   -> HistoryEntry[]{configVersion,verified,message}
GET  /api/config/capabilities              -> {providers:[CapabilityView]}（无秘密）
POST /api/config/items                     -> {config, backfillJobs[]}   （受控 AddItemRequest）
POST /api/config/items/{itemId}/enabled?enabled=bool -> ConfigView
POST /api/config/replace                   -> {config, backfillJobs[]}   （ReplaceItemRequest）
POST /api/backfill/jobs?itemId&from&to     -> BackfillJobView（幂等创建/复用）
POST /api/backfill/jobs/{jobId}/run        -> BackfillJobView（真实执行）
POST /api/backfill/jobs/{jobId}/retry      -> BackfillJobView（FAILED→WAITING 重开再 run）
GET  /api/backfill/jobs/{jobId}            -> BackfillJobView
GET  /api/backfill/jobs                    -> {jobs:[BackfillJobView]}
```

错误契约：非法参数/未知 itemId/capability 拒绝/损坏快照 → `400 {status:"REJECTED", message}`；无 500。

## 3. 测试结果（真实执行）

### 后端定向（`.\mvnw.cmd -Dtest=... test`）

| 套件 | 结果 |
|---|---|
| `DynamicConfigWorkflowServiceTest` | 8/8 PASS（真实生产链 harness：EUR 停用/GBP 新增/无 capability 拒绝/双 AZ91D 替换/Manual AWAITING/retry/history 损坏/capabilities 无秘密） |
| `ConfigApiMvcContractTest` | 6/6 PASS（正式 @WebMvcTest + DispatcherServlet：缺参数 400、布尔类型 400、畸形 JSON 400、受控消息透传、capability 拒绝 400、200 契约体） |
| 既有 `ConfigManagementServiceTest` / `BackfillOrchestratorTest` / Dashboard 套件 | 全部 PASS（零回归） |

真实生产路径断言摘要（workflow 测试）：
1. **停用 EUR**：configVersion+1；面板项 enabled=false；旧快照 manifest verified 且可读；重建 workflow（新 store 实例）重启后状态保持。
2. **新增 GBP**：capability 由服务端 Gate（无 OFFICIAL_WEB 支持→StorageException 且旧 config 保持）；激活后创建真实 backfill job（WAITING）→ run 走完整链 → SUCCEEDED，daily CSV 真实落盘。
3. **替换双 AZ91D**：旧项 disabled（不删除）；新 MAT-REPL-01.SMM/.AM 独立 itemId + supersedesItemId；sourceIntent SMM/Asian Metal 独立；两条 ADC12 保持 enabled；替换后 SMM 快照仍 verified 可读。
4. **Manual**：无输入 → 诚实 `AWAITING_MANUAL_INPUT`，绝不 SUCCEEDED。
5. **retry**：FAILED→WAITING 重开（经既有 JobStore 原子写），同 jobId 复用。
6. **capabilities**：无 token/key/cookie，无 data-root 路径。
7. **history 损坏**：篡改快照 → verified=false + 显式 message，不静默跳过、不 500。

### 前端（`npm run test` / `npm run build`）

| 项 | 结果 |
|---|---|
| 新增 `config.spec.ts` | 7/7 PASS（渲染、停用、受控 ADD 请求断言不含 configVersion/routeEffectiveAt/supersedesItemId、替换、run、创建任务、失败不白屏） |
| 既有 `pages.spec.ts` | 11/11 PASS（Day7 回归保持） |
| `vue-tsc --noEmit && vite build` | PASS（dist 产出） |

## 4. 保持的冻结决策

- DEC-008（前端零业务计算；本页不做任何业务值计算）
- DEC-051（stale 后端派生）、DEC-053/054（计算/日历版本）、DEC-060（Java17/Boot3.5.15/AI1.1.8）
- Day1-Day6 代码零修改；Day7 仅 router/导航/API client 集成修改（裁决三允许范围，含回归）
- 不新增数据库、不新增第二真值目录、不新增 Agent 工具

## 5. 状态

- D8-T01 = `TaskExecutionStatus=REVIEW_PENDING`（Batch 执行模式下不阻止 D8-T02 继续）
- Day8 未 COMPLETE；Feature Freeze 未 EFFECTIVE；未 merge main；未开始 Day9
