# Day 8 T05 — P0 功能冻结与 Day8 Stage Closure（候选准备，2026-08-17）

> 性质：D8-T05 Stage Candidate Preparation（Batch 执行模式；**Review 前不写 DONE/COMPLETE/EFFECTIVE**）。
> Base：`f79e309`（Day7 final closure）。候选 commit 见文末。

## 1. Day8 功能冻结清单（候选）

**已冻结功能（D8-T01～D8-T04 实现）**：

| 领域 | 能力 |
|---|---|
| 动态配置（D8-T01） | /api/config：items/history/capabilities/ADD/ENABLE-DISABLE/REPLACE；/api/backfill：jobs/create/run/retry/list；DynamicConfigWorkflowService 编排（复用 ConfigManagementService + BackfillOrchestrator）；configVersion+1 原子激活、history 不可变、capability 服务端 Gate；ConfigView 页面 |
| 预警确认（D8-T02） | /api/warnings：list（真实 from/to）/detail/ack（DEC-061 sidecar）/evaluate（demoRule 仅）；WarningAckStore CREATE_NEW+幂等+冲突 fail-closed；原 warning 不可变；WarningView 页面 |
| Agent 工作台（D8-T03） | /api/agent/query 向后兼容扩展（generatedBy/provider/model/scope/limitations/claims/dataThrough）；AgentView 页面；七只读工具/EvidencePack/模板降级边界不变 |
| Web P0 修复（D8-T04） | backfill 列表排除 time-state；manual operator 受控默认；历史页选择器含 disabled 项 |

**冻结边界（不可改）**：DEC-008（前端零计算）、DEC-051（stale）、DEC-060（Java17/Boot3.5.15/AI1.1.8）、DEC-061（ack sidecar）、C21/C22/C35/C36（Agent 只读边界）、EXT-07/EXT-08（OPEN_EXTERNAL，规则恒 demoRule）、真实 Cloud NOT_RUN。

## 2. 最终候选与 SHA-256

| 项 | 值 |
|---|---|
| Branch | `integration/day8` |
| Base | `f79e309` |
| D8-T01 commit | `f70ff18` |
| D8-T02 commit | `9c88a84` |
| D8-T03 commit | `ec2f0b5` |
| D8-T04 commit | `4745080` |
| Final Stage Candidate | 见文末 commit |
| 后端 JAR | `backend/target/supplymind-backend-0.1.0-SNAPSHOT.jar`（50,101,727 bytes） |
| JAR SHA-256 | `859C24931E0B5F51C6F80E7B30E4279268EF0EE75AAEE70DDBC7CFC955F16715` |
| backend/pom.xml SHA-256 | `184372BEEF1A45FC5E065BDF86E5D381826ABE4CDD9C4E73865DE5A28F0401F5` |
| frontend/package.json SHA-256 | `1EBEE57B122026BEAF24889F8EA9D2ECDE369FA181E58DA1A3BF1394B9C71625` |
| frontend/package-lock.json SHA-256 | `5E9585124B59E62E707CE865DCFE9E25FB3D96198D27E8A867F15235BE01545C` |
| docs/04 SHA-256 | `887FAEAD62C18A94C1C0F2D4171C4A9D02AB03F0E4495626AA37F12F9D0C2BAF` |
| docs/05 SHA-256 | `712826703D524DAC9A54052605CCD7DC849E04F5CC4A0F0AAE42DA0CC2604431` |
| docs/06 SHA-256 | `24FB7D49B50A92E60423ACA67395BBE95C34D97672AF5150C79705A999D32E91` |
| FILE-SCHEMA-V1 SHA-256 | `FA56B800F8153B94F5A728552E605B7F5AE79B2BC6B640E0B4D2E09851A4C59D` |

## 3. 统一回归（真实执行）

### 后端 `.\mvnw.cmd clean test`
- suites：**115**
- tests：**608**
- failures：**0**
- errors：**0**
- skipped：**8**（与 Day5/Day6 基线逐项相同：AtSrc002 gated、Day5TimeContractHarness、Aggregate/DailyRealRawEvidence、PbocOfficialWebRealNetworkAttempt、PbocRawClosedLoopSmokeGate、PublishRealRawEvidence、PbocValidationRealRawEvidence——真实联网/raw 门禁 + D10 物理时间，未新增 skip）
- 旧快照均 HISTORICAL：Day7=110/578；D8-T01=112/592；D8-T02=113/605；D8-T03=115/607
- 测试后 `backend/data` 无残留

### 前端 `npm run test` / `npm run build`
- tests：**28/28 PASS**（Test Files 4/4）
- build（vue-tsc --noEmit + vite）：**PASS**（dist 产出）

### Day1-Day7 回归
- **PASS**：旧测试 0 failures/0 errors；8 skipped 与基线一致；无断言弱化、无 golden 反向改写；Day1-Day6 代码零修改；Day7 仅 router/导航/API client/共享 DTO 集成修改（裁决三允许范围，均含回归测试）

## 4. 重建验证

- `mvnw.cmd clean test` 从干净工作副本完整重建并回归（见上）
- `npm run test` + `npm run build` 从干净依赖重建前端产物
- 浏览器形态预验收证据见 `D8-T04-WEB-P0-PREACCEPTANCE-20260817.md`

## 5. Evidence Index（docs/evidence/Day8/）

| 任务 | 证据 |
|---|---|
| D8-T01 | `D8-T01-DYNAMIC-CONFIG-20260817.md` |
| D8-T02 | `D8-T02-WARNING-ACK-20260817.md` |
| D8-T03 | `D8-T03-AGENT-WORKBENCH-20260817.md` |
| D8-T04 | `D8-T04-WEB-P0-PREACCEPTANCE-20260817.md` |
| D8-T05 | 本文件 |
| 决策 | docs/06 DEC-061；docs/04 D8-T02/D8-T05；docs/05 Day8ExecutionMode |

## 6. 状态（候选，待 Final Stage Review）

- D8-T01～D8-T05 = `TaskExecutionStatus=REVIEW_PENDING`
- Day8 = `NOT_COMPLETE`
- Feature Freeze = `PENDING_FINAL_STAGE_REVIEW`
- 未 merge main；未开始 Day9
- 真实 Cloud LLM gated run = `NOT_RUN/PENDING_EXTERNAL`（未伪报）
