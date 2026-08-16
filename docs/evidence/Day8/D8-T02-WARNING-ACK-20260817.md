# Day 8 T02 — 预警规则、记录与确认闭环（2026-08-17）

> 性质：D8-T02 实施证据（Batch 执行模式，`TaskExecutionStatus=REVIEW_PENDING`）。
> Base：`f70ff18`（D8-T01，integration/day8）。
> 依赖：D4-T04、D5-T03、D7-T01 均 `DONE`（既有能力复用）。

## 1. 范围与边界

D8-T02 在既有 WarningService（D5-T05 确定性求值 + 幂等持久化）之上补齐 Web 形态的**查询、确认与页面**。原预警证据不可变是硬边界：

| 复用（零修改） | 用途 |
|---|---|
| `WarningService.evaluate` + `WarningRuleV1` | 确定性规则求值（demoRule 必 true；EXT-07/EXT-08 保持 OPEN_EXTERNAL） |
| `WarningStore` | 原 `warning/YYYY-MM/<warningId>.json` 不可变写入与 manifest |
| `HistoryQueryService`/aggregate/daily | 规则输入（已发布数据） |
| Dashboard `StatusBadge`、`client.ts`、router | 前端基础（Day7 集成文件修改，含回归） |

**新增（DEC-061 冻结）**：
- `WarningAcknowledgementV1`：固定字段 schemaVersion/warningId/warningRef/warningFileSha256/status/acknowledgedAt/dispositionNote；status v1 仅 `ACKNOWLEDGED`；dispositionNote 非空、≤500 字符、禁止路径/分隔符/冒号/分号/换行
- `WarningAckStore`：sidecar 写入 `warning/YYYY-MM/<warningId>.ack.json` + 相邻 manifest；先验证原 warning+manifest；CREATE_NEW；同字节幂等；异内容 fail-closed；原 warning 永不改写
- `WarningQueryService`：真实 from/to 月范围扫描（非固定 lookback）；只把 `<warningId>.json` 当记录（排除 `.ack.json`/`.manifest.json`/tmp/bak）；逐文件 manifest 校验；坏文件跳过不中断
- `WarningController`（/api/warnings：GET 列表 / GET {id} / POST {id}/ack / GET {id}/ack / POST evaluate）
- `WarningApiAdvice`：统一 400 `{status:REJECTED,message}`
- `StorageSchemaVerifier`/`ManifestDerivedFieldsVerifier`：新增 `.ack.json` 分支（写 sidecar 走原子存储，受控集成修改，含回归）
- 前端：`WarningView.vue` + `api/warning.ts` + `types/warning.ts` + router/导航
- docs 同步：DEC-061 登记 docs/06；FILE-SCHEMA-V1 新增 ack 条目；docs/01/02/03（AT-ALT-002）/04（D8-T02）

**禁止项已遵守**：不新增第二套 active rule store；不改 WarningRecordV1 历史字节；LLM 不参与判定；不自动调价；前端不计算阈值/级别；Controller 不扫文件系统。

## 2. API 契约（新增，正式 MVC contract 测试覆盖）

```
GET  /api/warnings?itemId&from&to          -> {itemId,from,to,warnings[WarningView]}
GET  /api/warnings/{warningId}?itemId      -> WarningView
POST /api/warnings/{warningId}/ack?itemId  body {dispositionNote} -> AckView
GET  /api/warnings/{warningId}/ack?itemId  -> {acknowledged,ack?}
POST /api/warnings/evaluate                body EvaluateRequest   -> {status:TRIGGERED|NOT_TRIGGERED, warning?}
```

WarningView 含 acknowledged/ackRef 状态（sidecar 存在即 acknowledged）；evaluate v1 只接受 demoRule=true（构造器强制），页面/API 明示 TEST/DEMO。

## 3. 测试结果（真实执行）

### 后端

| 套件 | 结果 |
|---|---|
| `WarningAckStoreTest` | 7/7 PASS（sidecar 落盘+原文件逐字节不变、幂等重试、异内容冲突 fail-closed、重启恢复、dispositionNote 控制、查询排除 ack/manifest、精确 from/to 月范围、坏文件不中断） |
| `WarningApiMvcContractTest` | 6/6 PASS（正式 @WebMvcTest：缺参数 400、日期 400、unknown warningId 400、空备注 400、200 契约体、evaluate 结构化） |
| 既有 `WarningServiceTest`/`WarningStoreTest`/Dashboard 套件 | 全部 PASS（零回归） |

关键断言摘要：
1. ack 后 `warning/YYYY-MM/<id>.ack.json` + manifest 存在且 verified；**原 warning 文件字节不变**。
2. 同内容重复 ack 幂等（不产生第二个文件）；不同内容 → `StorageException`（不可变冲突）。
3. 新 Harness（等价重启）从 sidecar 恢复 ACKNOWLEDGED。
4. 空/超长/路径形/换行形 dispositionNote 全部拒绝。
5. 查询层：ack/manifest 文件绝不作为 WarningRecord 解码；坏 warning 文件跳过不中断；July 记录不出现在 August 精确范围查询。
6. 未知 warningId → 400；空备注 → 400。

### 前端（`npm run test` / `npm run build`）

| 项 | 结果 |
|---|---|
| 新增 `warning.spec.ts` | 5/5 PASS（渲染 demo 标记、ack 只发 dispositionNote、空备注前端拒绝不发请求、evaluate 请求不含 demoRule、失败不白屏） |
| 既有 `pages.spec.ts` + `config.spec.ts` | 18/18 PASS（回归保持） |
| `vue-tsc --noEmit && vite build` | PASS |

## 4. 回归

- 后端全量 `.\mvnw.cmd clean test`：**113 suites / 605 tests / 0 failures / 0 errors / 8 skipped**（D8-T01 后 112/592 → +1 suite/+13 tests；8 skipped 与 Day5/Day6 基线逐项相同）
- 前端 `npm run test`：**23/23 PASS**（原 18 + 新 5）；`npm run build` PASS

## 5. 保持的冻结决策

- EXT-07/EXT-08 继续 `OPEN_EXTERNAL`；所有规则 demoRule=true 且页面/API 明示
- DEC-008（前端零计算）、DEC-051、DEC-060、DEC-061（本任务冻结）
- Day1-Day6 代码零修改；Day7 仅 router/导航/API client 集成修改（含回归）
- WarningRecordV1 历史字节未改；无第二真值目录；无数据库

## 6. 状态

- D8-T02 = `TaskExecutionStatus=REVIEW_PENDING`（Batch 执行模式下不阻止 D8-T03 继续）
- Day8 未 COMPLETE；Feature Freeze 未 EFFECTIVE；未 merge main；未开始 Day9
