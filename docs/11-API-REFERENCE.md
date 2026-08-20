# SupplyMind AI — API 说明

> 文档编号：SMA-API-001
> 适用版本：P0 便携发布
> 最后更新：2026-08-20

## 1. 通则

- 后端仅绑定 `127.0.0.1` 动态端口（桌面壳启动时分配，从不固定 8080）；实际地址见 `logs\backend-url.txt`。
- 前端与后端同源（`/api`），浏览器即可调用。
- 错误契约：参数/状态非法一律结构化 `400 {status:"REJECTED", message}` 或 `{status:"UNAVAILABLE", message}`；**不返回 500 堆栈、不泄漏文件路径**。
- 所有业务数值为字符串（精确十进制），前端只渲染不计算。
- 只有 `PUBLISHED + VERIFIED/VERIFIED_WITH_NOTICE` 数据进入查询结果；PENDING/REJECTED/CONFLICT 不可见。

## 2. 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 后端存活：`{status:"UP", pid, ...}` |

## 3. 面板查询（`/api/dashboard`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/overview` | 启用监测项卡片（最新已发布值、来源、完整率、路线） |
| GET | `/history?itemId&from&to` | 每日历史点 + 图表坐标（后端计算）+ `evidenceIssues`（缺失/损坏期间显式列出） |
| GET | `/metrics?itemId&grain&fromYear&toYear` | 月/季/半年/年聚合行 + `evidenceIssues` |
| GET | `/quality?itemId&from&to` | 质量视图（latestStatus、行、warning、issues） |
| GET | `/sources` | 来源列表（含已停用项）与录入入口状态 |
| POST | `/manual`（form 参数 itemId/source/businessDate/value/unit） | 人工录入受理 → `{status:"PENDING", runId, rawRef, timelineRef, message}` |
| POST | `/api/manual/{runId}/process` | 显式操作员步骤：复用冻结校验/发布/daily/aggregate；成功返回 `status=PUBLISHED`，不自动信任 Manual |
| POST | `/import`（multipart `file`） | CSV/XLSX 导入受理 → 逐行受理证据 + 逐行错误 |
| GET | `/import/template` | 导入 CSV 模板下载 |
| POST | `/synthetic-demo` | 确定性演示数据（不持久化、不进入正式判断） |

## 4. 当前官方汇率采集（`/api/acquisition/current`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/acquisition/current` | 异步采集状态：IDLE/RUNNING/SUCCEEDED/FAILED、业务日期和非敏感提示 |
| POST | `/api/acquisition/current/refresh` | 非阻塞触发一次真实人民银行公开网页采集；并发触发复用当前任务，不创建重复链 |

桌面 EXE 启动时自动触发一次；失败不阻塞本地查询，不使用第三方数据冒充人民银行。

## 5. 动态配置（`/api/config`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/items` | 活动配置视图（含已停用项；configVersion/mode/updatedAt/items[]） |
| GET | `/history` | 配置历史快照审计（configVersion/verified/message；损坏显式报告） |
| GET | `/capabilities` | 无秘密的 Provider 能力视图（当前/历史能力、支持类型） |
| POST | `/items` | 受控 ADD：客户端只提交业务字段；后端生成 configVersion/routeEffectiveAt/审计时间并校验能力；返回 `{config, currentIntake, backfillJobs[]}` |
| POST | `/items/{itemId}/enabled?enabled=bool` | 停用/启用（历史保留） |
| POST | `/replace` | REPLACE：`{oldItemId, newItem{...}}`；旧项停用、新项独立 itemId + supersedesItemId |

`AddItemRequest` 关键字段：`itemId/displayName/sourceIntent/providerType/accessMethod/actualSourceName/routeDecision/fallbackReason/externalCode/sourceFieldKey/rateKind/calculationVersion/calculationScale/displayScale/roundingMode/calendarVersion/currency/baseCurrency/unit/materialValidation?/backfillFrom?/backfillTo?`（backfillFrom/To 必须成对、from≤to；材料项必须带 materialValidation）。

能力判定按配置元数据（providerType/accessMethod/rateKind/sourceIntent/route），**不做 itemId 字符串硬编码**。

## 6. 历史回填（`/api/backfill`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/jobs` | 任务列表 |
| GET | `/jobs/{jobId}` | 任务详情 |
| POST | `/jobs?itemId&from&to` | 创建/复用任务（同范围幂等复用 jobId） |
| POST | `/jobs/{jobId}/run` | 运行：真实链 acquisition→validation→publish→daily→aggregate；Manual/无历史能力 → 诚实 `AWAITING_MANUAL_INPUT`；检查点连续推进 |
| POST | `/jobs/{jobId}/retry` | FAILED→WAITING 重开（同 jobId） |

状态：`WAITING / RUNNING / AWAITING_MANUAL_INPUT / PARTIAL_SUCCESS / SUCCEEDED / FAILED`。

## 7. 预警（`/api/warnings`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/evaluate` | 请求驱动评估（v1 仅 demoRule=true 的 TEST/DEMO 规则）：`{ruleId, ruleKind, itemId, grain, threshold, direction, periodStart, periodEnd, baselinePeriods?}` → `{status:"TRIGGERED", warning}` 或 `{status:"NOT_TRIGGERED"}` |
| GET | `?itemId&from&to` | 区间预警列表 |
| GET | `/{warningId}?itemId` | 预警详情 |
| POST | `/{warningId}/ack?itemId` | 确认（DEC-061 sidecar；`acknowledgedAt` 由服务端时钟生成） |
| GET | `/{warningId}/ack?itemId` | 确认状态 |

## 8. Agent（`/api/agent`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/query` | `{question, itemId?, startDate?, endDate?, grain?, periodStart?, periodEnd?, month?, businessDate?, mode?}` → 结构化响应 |

响应字段：`requestId / answer / llmStatus / degraded / degradeReason / toolTrace[] / evidenceRefs / reportRef / facts[] / generatedBy(CLOUD_LLM|JAVA_TEMPLATE) / provider / model / scope / limitations / recommendations / claims[] / dataThrough / evidenceLinks[] / calculationBasis / risk`。

- 7 个只读工具：`series.resolve / history.query / period.metrics / quality.inspect / cost.impact / warning.explain / provenance.trace`；无写工具。
- 云模型故障（断网/401/429/超时/5xx/畸形）→ 同一 EvidencePack 生成 Java 模板报告（`degraded=true`），接口不 500。
- 报告中数值、结论必须可回指证据引用；引用失效 fail-closed。

## 9. 桌面专用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 桌面壳健康探测与父进程看门狗使用 |

后端不接受除 loopback 外的监听；未暴露写数据库、写配置回填以外的任何第二真值路径。
