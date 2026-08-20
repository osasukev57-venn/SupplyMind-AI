# SupplyMind AI — 数据字典

> 文档编号：SMA-DATA-001
> 适用版本：P0 便携发布（FILE-SCHEMA-V1）
> 最后更新：2026-08-20

## 1. 存储总则

- 唯一业务持久化介质：程序根目录 `data/` 下的 UTF-8 文本文件。**不存在数据库、数据库索引或隐藏存储**（Electron 缓存不承载业务真值）。
- JSON 文件：配置、raw、生命周期、隔离、预警、报告、任务/时间状态、manifest。
- CSV 文件：`processed/daily`（每日加工）与 `processed/aggregate`（月/季/半年/年聚合）；RFC 4180、UTF-8 无 BOM、`\r\n` 行尾、固定表头、行规范排序。
- 每个业务 JSON/CSV 文件都伴随 `<文件>.manifest.json`（SHA-256、时间、内容引用），读取前强制校验；manifest 自身不再生成 manifest。
- 写入一律原子提交（临时文件 + dirty marker + 备份恢复），无半文件。
- 路径分区规则（DEC-017）：raw 与隔离证据按 `receivedAt`（Asia/Shanghai）的年月分区；processed daily/aggregate 严格按已验证 `businessDate` 分区。

## 2. 目录布局

```
data/
  config/
    monitor-series.json               # 唯一活动配置（configVersion、mode、items[]）
    history/<configVersion>.json      # 不可变已生效配置快照
  raw/
    source/<acquisitionId>.json       # 官方源抓取实体证据（DEC-056）
    import/<importId>.json            # 导入源文件证据（LocalImport）
    <mode>/<providerType>/<itemId>/<YYYY>/<MM>/<runId>.json   # 逐项原始记录
  staging/<runId>.json                # 生命周期时间线（recordVersion 序列）
  quarantine/<itemId>/<YYYY-MM>/<runId>.json   # 隔离证据
  processed/
    daily/<itemId>/<YYYY-MM>.csv      # 每日加工（业务月轮转）
    aggregate/<itemId>/<grain>/<YYYY>.csv      # grain ∈ month|quarter|halfyear|year
  warning/<YYYY-MM>/<warningId>.json  # 预警记录（+ .ack.json 确认 sidecar）
  report/<YYYY-MM>/<reportId>.json    # Agent 报告（AGENT-EVIDENCE-SCHEMA-V1）
  runtime/
    jobs/active/<jobId>.json          # 回填任务 + time-state.json
    jobs/history/<YYYY-MM>/<jobId>.json
    dirty/<transactionId>.json        # 事务恢复标记（含 writer lock）
    conflicts/raw/<itemId>/<YYYY-MM>/<runId>/<conflictId>.json  # 同键异hash冲突证据
```

## 3. 配置 JSON（`config/monitor-series.json`）

顶层：`schemaVersion`、`configVersion`（整数单调+1）、`mode`（formal|demo|test）、`updatedAt`、`items[]`。

item 字段：`itemId`、`displayName`、`enabled`、`sourceIntent`（PBOC|SMM|Asian Metal 等）、`providerType`（official_web|authorized_api|free_public|manual|local_import|synthetic_demo）、`accessMethod`、`actualSourceName`（必须为真实来源名）、`routeDecision`（primary|fallback_free_public|fallback_manual|direct_local_import）、`fallbackReason`、`routeEffectiveAt`、`supersedesItemId`、`externalCode`、`sourceFieldKey`、`rateKind`（人民币汇率中间价|material）、`calculationVersion`、`calculationScale`、`displayScale`、`roundingMode`（Java RoundingMode 字符串）、`calendarVersion`、`currency`、`baseCurrency`、`unit`、`materialValidation`（材料：valueMinExclusive/valueMaxInclusive/staleThresholdDays/canonicalSpecCode/acceptedSpecAliases）。

## 4. Raw 记录（RawReceiptV1）

关键字段：`schemaVersion`、`rawRef`、`acquisitionId`、`runId`、`mode`、`providerType`、`accessMethod`、`configVersion`、`actualSourceName`、`sourceUrl`、`sourceReference`、`itemId`、`sourceBusinessDateRaw`、`sourceBusinessDate`、`sourcePublishedAtRaw`、`sourcePublishedAt`、`receivedAt`、`rawValue`（原始词法字符串，原样保留）、`rawUnit`、`rawCurrency`、`operatorRef`（Manual）、`contentType`、`payloadBase64`、`payloadSha256`、`sourceFieldKey`、`updatedAt`。

raw 不含 `processingStage` / `validationStatus`（两者只存在于生命周期时间线）。

## 5. 生命周期时间线（LifecycleTimelineV1，`staging/`）

- `recordId`、`runId`、`rawRef`、`createdAt`、`recordVersion`（单调）、快照序列。
- 快照：`ProcessingStage` ∈ RECEIVED→PARSED→VALIDATED→PUBLISHED；`ValidationStatus` ∈ PENDING/VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT；`CandidateV1`（标准化值、业务日期、来源等）。
- 发布门禁：仅 `PUBLISHED + VERIFIED/VERIFIED_WITH_NOTICE` 可进入下游（DEC-011）。

## 6. Manifest（`*.manifest.json`）

字段：`schemaVersion`、`dataRef`、`fileSha256`、`contentRefs`（输入引用）、`createdAt` 等；读取前必须与文件内容逐字节校验。

## 7. daily CSV（`processed/daily/<itemId>/<YYYY-MM>.csv`）

固定表头（26 列）：

```
schemaVersion, businessDate, itemId, providerType, actualSourceName, accessMethod,
processingStage, validationStatus, validationVersion, configVersions, calculationVersion,
calculationScale, displayScale, roundingMode, calendarVersion, sum, validCount, avg,
expectedCount, missingCount, complete, currency, unit, inputRefs, updatedAt, canonicalSpecCode
```

- `sum`/`avg` 为精确十进制字符串（`toPlainString()`，无科学计数法、不丢尾零、不 stripTrailingZeros）。
- `expectedCount`/`missingCount`/`complete` 表示应采集日（calendarVersion 工作日日历）与缺日；缺日不计入平均、不补 0。
- `inputRefs`：本行引用的事先发布 run 证据（数组，RFC 4180 内转义）。
- 行按 `businessDate` 规范排序；重复键去重；同键不同内容不静默任选（隔离）。

## 8. aggregate CSV（`processed/aggregate/<itemId>/<grain>/<YYYY>.csv`）

固定表头（31 列）：

```
schemaVersion, grain, periodStart, periodEnd, itemId, providerType, actualSourceName,
accessMethod, validationStatus, validationVersion, configVersions, calculationVersion,
calculationScale, displayScale, roundingMode, calendarVersion, sum, validCount, avg,
min, max, expectedCount, missingCount, complete, qualityStatus, currency, unit,
sourceFingerprint, inputRefs, calculatedAt, canonicalSpecCode
```

- `grain` ∈ month|quarter|halfyear|year；`qualityStatus` ∈ COMPLETE|PARTIAL（完整率口径）。
- `sourceFingerprint`：确定性来源向量；`inputRefs` 指向参与的 daily/发布证据。
- 聚合只对已发布数据计算；重算确定性（同输入同字节）。

## 9. 时间状态（`runtime/jobs/active/time-state.json`）

`schemaVersion`、`stateVersion`（单调）、`lastObservedTime`、`effectiveHighWaterTime`、`effectiveBusinessDate`、`lastCompletedPeriod`（YYYY-MM 高水位）、`updatedAt`。回拨时 observed < high-water 被显式识别，高水位不回退，边界只消费一次。

## 10. 回填任务（`runtime/jobs/active/backfill-<jobId>.json`）

`jobId`、`itemId`、`fromDate`、`toDate`、`status`（WAITING|RUNNING|AWAITING_MANUAL_INPUT|PARTIAL_SUCCESS|SUCCEEDED|FAILED）、`completedPeriods`、`currentCheckpoint`（连续高水位，不跳过失败日）、`failureReasons`、`configVersion`、`createdAt`、`updatedAt`。

## 11. 预警（`warning/<YYYY-MM>/<warningId>.json`）

`warningId`、`ruleId`、`ruleVersion`、`ruleKind`、`itemId`、`period`、`threshold`、`current`、`baseline`、`riskLevel`、`evidenceRefs`、`dataStatus`、`evaluatedAt`、`inputFingerprint`。同 fingerprint 幂等；ack 写入 `<warningId>.ack.json` sidecar。

## 12. Agent 报告（`report/<YYYY-MM>/<reportId>.json`，AGENT-EVIDENCE-SCHEMA-V1）

`reportId`、`question`、`mode`、`generatedBy`（CLOUD_LLM|JAVA_TEMPLATE）、`degraded`、`summary`、`conclusion`、`limitations`、`toolResults`（工具名、输入、输出）、`evidenceRefs`、`createdAt`。报告正文引用必须可回指证据文件；引用失效 fail-closed。
