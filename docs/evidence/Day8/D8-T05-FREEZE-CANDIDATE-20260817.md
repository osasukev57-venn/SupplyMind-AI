# Day 8 T05 — P0 功能冻结与 Day8 Stage Closure（FINAL，2026-08-18）

> 性质：D8-T05 Final Stage Closure；Final Stage Review=`PASS`，D8-T01～D8-T05=`DONE`、Day8=`COMPLETE`、Feature Freeze=`EFFECTIVE`。
> Base：`f79e309`（Day7 final closure）。最终实现 commit 见文末。

## 1. Day8 功能冻结清单（最终）

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
| Final Stage Fix Commit | `239d025`（M1 单次 intake、M3 FORMAL/demo/row-ref、UI最终收口） |
| Final Stage Review Candidate | `239d025`（最终生产/测试/UI实现） |
| 后端 JAR | `backend/target/supplymind-backend-0.1.0-SNAPSHOT.jar` |
| JAR SHA-256（可复现） | `95E2C6F63E18383F499BB239649EBFCA5B5D8966B8D9201FC1BD2C9D03D49426`（两次独立 clean package 一致，见 §4） |
| backend/pom.xml SHA-256 | `51E7F1DB6208A10B5EB514BB725AD42FED7A57D70A29E6EC62E0FA864F874031` |
| frontend/package.json SHA-256 | `1EBEE57B122026BEAF24889F8EA9D2ECDE369FA181E58DA1A3BF1394B9C71625` |
| frontend/package-lock.json SHA-256 | `5E9585124B59E62E707CE865DCFE9E25FB3D96198D27E8A867F15235BE01545C` |
| docs/04 SHA-256 | `CD5F66A2AA8D67ADE2B137C674DADBD8F677DB8336C2C4DB85AA2A9E21851B79` |
| docs/05 SHA-256 | `B0D66C19124BFB337130FF0EA3D7B3B8B24B208E3894F6219951A708574054EB` |
| docs/06 SHA-256 | `24FB7D49B50A92E60423ACA67395BBE95C34D97672AF5150C79705A999D32E91` |
| FILE-SCHEMA-V1 SHA-256 | `FA56B800F8153B94F5A728552E605B7F5AE79B2BC6B640E0B4D2E09851A4C59D` |
| artifacts SHA-256 manifest | `docs/evidence/Day8/artifacts/SHA256-MANIFEST.txt`（最终工件逐文件 SHA-256） |

## 3. 统一回归（真实执行）

### 后端 `.\mvnw.cmd clean test`
- suites：**117**
- tests：**632**
- failures：**0**
- errors：**0**
- skipped：**8**（与 Day5/Day6 基线逐项相同：AtSrc002 gated、Day5TimeContractHarness、Aggregate/DailyRealRawEvidence、PbocOfficialWebRealNetworkAttempt、PbocRawClosedLoopSmokeGate、PublishRealRawEvidence、PbocValidationRealRawEvidence——真实联网/raw 门禁 + D10 物理时间，未新增 skip）
- 旧快照均 HISTORICAL：Day7=110/578；D8-T01=112/592；D8-T02=113/605；D8-T03=115/607；D8-T05 前=116/608；旧 Final Candidate=116/631；当前 Final=117/632（新增 FORMAL 不得投影 demo warning 的独立攻击用例）
- 测试后 `backend/data` 无残留

### 前端 `npm run test` / `npm run build`
- tests：**33/33 PASS**（Test Files 5/5，含 governance.spec 治理隔离 3 项）
- build（vue-tsc --noEmit + vite）：**PASS**（dist 产出）

### Day1-Day7 回归
- **PASS**：旧测试 0 failures/0 errors；8 skipped 与基线一致；无断言弱化、无 golden 反向改写；Day1-Day6 代码零修改；Day7 仅 router/导航/API client/共享 DTO 集成修改（裁决三允许范围，均含回归测试）

## 4. 重建验证

- `mvnw.cmd clean test` 从干净工作副本完整重建并回归（见上）
- `npm run test` + `npm run build` 从干净依赖重建前端产物
- **可复现构建（M5）**：两次独立 `.\mvnw.cmd clean package -DskipTests`，JAR SHA-256 均为
  `95E2C6F63E18383F499BB239649EBFCA5B5D8966B8D9201FC1BD2C9D03D49426` → **MATCH**
  （pom.xml 固定 `project.build.outputTimestamp=2026-08-17T00:00:00Z`，JAR 条目时间戳确定）
- 浏览器形态预验收证据见 `D8-T04-WEB-P0-PREACCEPTANCE-20260817.md` 与 `DAY8-FINAL-STAGE-FINDINGS-FIX-20260817.md`；正式 Web P0 工件见 `artifacts/`（截图 + runner JSON + SHA manifest）

## 5. Evidence Index（docs/evidence/Day8/）

| 任务 | 证据 |
|---|---|
| D8-T01 | `D8-T01-DYNAMIC-CONFIG-20260817.md` |
| D8-T02 | `D8-T02-WARNING-ACK-20260817.md` |
| D8-T03 | `D8-T03-AGENT-WORKBENCH-20260817.md` |
| D8-T04 | `D8-T04-WEB-P0-PREACCEPTANCE-20260817.md` |
| D8-T05 | 本文件 |
| Final Stage Fix | `DAY8-FINAL-TECHNICAL-CLOSURE-20260818.md`（当前）；`DAY8-FINAL-STAGE-FINDINGS-FIX-20260817.md`（HISTORICAL） |
| Web P0 工件 | `artifacts/`（backend-surefire-summary.json、backend-surefire-xml.zip、frontend-vitest-result.json、frontend-build-output.txt、maven-dependency-tree.txt、web-p0-full-matrix.json、截图及SHA256-MANIFEST.txt） |
| 视觉验收 | `docs/visual-acceptance/`（7 页 × light 1440 / dark 1440 / responsive 768 = 21 张 + README.md） |
| 决策 | docs/06 DEC-061；docs/04 D8-T02/D8-T05；docs/05 Day8ExecutionMode |

## 6. 状态（Final Stage Review PASS）

- D8-T01～D8-T05 = `TaskExecutionStatus=DONE`
- Day8 = `COMPLETE`
- Feature Freeze = `EFFECTIVE`
- D9-T01=`NOT_STARTED`+`READY`；未 merge main；未开始 Day9
- 真实 Cloud LLM gated run = `NOT_RUN/PENDING_EXTERNAL`（未伪报）
