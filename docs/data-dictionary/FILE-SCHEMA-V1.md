# SupplyMind AI 文件 Schema v1 数据字典

> **契约状态：冻结实现字典。** 本文件逐字段转录并索引 `docs/01-PROJECT-MASTER-PLAN.md` 第 8.3–9 节；发生不一致时，以该冻结计划书为准。本字典不引入第二套 schema。

## 适用范围与共同约束

- 唯一业务数据根目录由 `supplymind.data-root` 指定，所有引用均为相对该根目录、使用 `/` 的路径；禁止绝对路径、`..` 和目录穿越。
- 目录/文件名中的 `mode`、`providerType`、`itemId`、`runId`、`acquisitionId`、`recordId`、`conflictId` 必须非空、不得含路径分隔符，并匹配 `[A-Za-z0-9._-]+`。
- JSON v1：UTF-8 无 BOM、LF、末尾恰好一个换行、字段按本字典顺序；可空字段显式为 `null`；不转义 `/`，不做 Unicode 归一化。
- CSV v1：UTF-8 无 BOM、逗号分隔、RFC 4180 转义、CRLF、唯一固定表头；可空值为空字段。
- 所有精确业务十进制均为 JSON/CSV 字符串，使用 `BigDecimal.toPlainString()`；禁止 double/float、科学计数法和 `stripTrailingZeros()`。

## 目录与 wire 值

唯一物理树为：

```text
data/config/monitor-series.json
data/config/history/<configVersion>.json
data/raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json
data/staging/<runId>.json
data/quarantine/<itemId>/YYYY-MM/<runId>.json
data/processed/daily/<itemId>/YYYY-MM.csv
data/processed/aggregate/<itemId>/{month,quarter,halfyear,year}/YYYY.csv
data/warning/YYYY-MM/<warningId>.json
data/report/YYYY-MM/<reportId>.json
data/runtime/jobs/{active,history/YYYY-MM}/*.json
data/runtime/dirty/*.json
data/runtime/conflicts/raw/<itemId>/YYYY-MM/<runId>/<conflictId>.json
```

`mode`：`formal`、`demo`、`test`。`providerType`：`official_web`、`authorized_api`、`free_public`、`manual`、`local_import`、`synthetic_demo`。`accessMethod` 与 providerType 一一对应：`public_official_html`、`authorized_api`、`free_public_web`、`manual`、`local_import`、`synthetic_demo`。`routeDecision`：`primary`、`fallback_free_public`、`fallback_manual`、`direct_local_import`、`synthetic_demo`。

## RawReceiptV1

稳定字段顺序为：

| 字段 | 类型/约束 |
|---|---|
| schemaVersion | 字符串，固定 `"1.0"` |
| rawRef | 必填，逐字等于按 mode/providerType/itemId/receivedAt/runId 导出的 `raw/.../YYYY/MM/<runId>.json` |
| acquisitionId, runId | 必填 string；一次多 item 外部响应可共享 acquisitionId，但每 item 独立 run/raw/timeline |
| mode, providerType, accessMethod | 必填合法 wire 值，provider/access 必须一一匹配 |
| configVersion | 必填正整数，必须能解析到不可变 history 快照 |
| actualSourceName | 必填真实来源显示名 |
| sourceUrl, sourceReference | nullable string；三个 HTTP provider 必填绝对 http(s) `sourceUrl`；manual/local_import 必填 `sourceReference`；synthetic_demo 的 sourceReference 为 fixture ID 且 sourceUrl 为 null |
| itemId | 必填 string，一个 receipt 仅对应一个 item |
| sourceBusinessDateRaw, sourceBusinessDate | nullable 原文 / `YYYY-MM-DD` |
| sourcePublishedAtRaw, sourcePublishedAt | nullable 原文 / ISO-8601 offset datetime |
| receivedAt | 必填 ISO-8601 offset datetime |
| inputAt | manual/local_import 必填；其他 provider 为 null |
| rawValue, rawUnit, rawCurrency | nullable 原始词法 string；不得由 BigDecimal 回写改写 |
| operatorRef | manual 必填；其他来源可为 null |
| httpStatus | 三个 HTTP provider 必填 integer；manual/local_import/synthetic_demo 为 null |
| contentType | 所有 provider 必填非空 string |
| payloadEncoding | 固定 `base64` |
| payloadBase64 | 完整原始实体字节的 base64，不是字段摘要 |
| payloadSha256 | 对解码后的完整原始实体字节计算的 64 位小写 SHA-256 |
| matchAnchor | nullable item 级自动解析锚点 |
| updatedAt | 必填 ISO-8601 offset datetime，创建时等于 receivedAt，之后永不改写 |

PBOC USD/CNY 与 EUR/CNY 的 `rawCurrency` 都是 `CNY`；`baseCurrency` 仅由配置显式表达，单位分别为 `CNY/1 USD`、`CNY/1 EUR`。RawReceipt 不含 `processingStage` 或 `validationStatus`。

## LifecycleTimelineV1 与 CandidateV1

顶层稳定字段：`schemaVersion`、`recordId`、`runId`、`rawRef`、`currentRecordVersion`、`records`。`records` 是从 1 连续递增、不可删除的快照；`currentRecordVersion == records.size == records` 最后一项版本。

每个快照稳定字段：`recordVersion`、`processingStage`、`validationStatus`、`candidate`、`reasonCode`、`validationVersion`、`validatedAt`、`publishedAt`、`publishRef`、`updatedAt`。

`ProcessingStage` 仅为 `RECEIVED`、`PARSED`、`VALIDATED`、`PUBLISHED`；`ValidationStatus` 仅为 `PENDING`、`VERIFIED`、`VERIFIED_WITH_NOTICE`、`REJECTED`、`CONFLICT`，两者不得混为一个通用 status 字段。白名单组合仅为：

`RECEIVED+PENDING`、`PARSED+PENDING`、`RECEIVED+REJECTED`、`VALIDATED+VERIFIED`、`VALIDATED+VERIFIED_WITH_NOTICE`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT`、`PUBLISHED+VERIFIED`、`PUBLISHED+VERIFIED_WITH_NOTICE`。

初态固定为版本 1 的 `RECEIVED+PENDING`。仅允许：received→parsed / received→received-rejected / parsed→validated 四种结论 / validated-verified→published-verified / validated-notice→published-notice。成功发布链固定为 recordVersion `1→2→3→4`；daily inputRefs 只能指向 `PUBLISHED` recordVersion `4`。

`CandidateV1` 字段全部必填：`itemId`、`businessDate` (`YYYY-MM-DD`)、`value`（精确十进制 string）、`currency`、`unit`、`providerType`、`actualSourceName`、`accessMethod`、`normalizationVersion`。received 的 pending/rejected candidate 为 null；从 parsed 起 candidate 非 null，且同一 run 后续快照逐字段不变。validated/published 必填 `validationVersion` 与 `validatedAt`；PUBLISHED 必填 `publishedAt` 和 `staging/<runId>.json#recordVersion=<version>` publishRef。

## QuarantineProjectionV1

只从 `RECEIVED+REJECTED`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT` 终态创建。稳定字段：`schemaVersion`、`quarantineRef`、`itemId`、`runId`、`rawRef`、`stagingRef`、`terminalRecordVersion`、`processingStage`、`validationStatus`、`reasonCode`、`validationVersion`、`rawPayloadSha256`、`rawFileSha256`、`receivedAt`、`quarantinedAt`。

`quarantineRef` 必须按 receivedAt 的 Asia/Shanghai 年月为 `quarantine/<itemId>/YYYY-MM/<runId>.json`，`stagingRef` 固定为 `staging/<runId>.json`，`quarantinedAt` 等于 terminal snapshot 的 updatedAt。received-rejected 的 validationVersion 为 null；两个 validated 终态必须非 null。

## Monitor-series 配置与 history

活动文件顶层字段依序为 `schemaVersion`、`configVersion`、`mode`、`updatedAt`、`items`。item 字段依序为：`itemId`、`displayName`、`enabled`、`sourceIntent`、`providerType`、`accessMethod`、`actualSourceName`、`routeDecision`、`fallbackReason`、`routeEffectiveAt`、`supersedesItemId`、`externalCode`、`sourceFieldKey`、`rateKind`、`calculationVersion`、`calculationScale`、`displayScale`、`roundingMode`、`calendarVersion`、`currency`、`baseCurrency`、`unit`。

items 按 itemId Unicode code point 升序且唯一。history `<configVersion>.json` 与当时活动配置逐字节相同、CREATE_NEW、不可改写；活动文件才可替换。`currency` 是计价币种；运行 JSON 不得新增 `quoteCurrency` 字段。`primary` 仅可配 official_web/authorized_api；其余 routeDecision 与 providerType 必须对应，两个 fallback 必填 fallbackReason，其余为 null。

生产初始 PBOC 配置是 formal/configVersion 1：`FX.EUR.CNY.PBOC_MID` 与 `FX.USD.CNY.PBOC_MID`，均使用 `arithmetic-mean-v1`、scale 8/4、`HALF_UP`、`weekday-asia-shanghai-v1`；其货币为 CNY、baseCurrency 分别为 EUR/USD。GD-01 test fixture 才使用 scale 12/9 与 `golden-calendar-v1`。

## Daily / Aggregate CSV

Daily 固定表头：`schemaVersion,businessDate,itemId,providerType,actualSourceName,accessMethod,processingStage,validationStatus,validationVersion,configVersions,calculationVersion,calculationScale,displayScale,roundingMode,calendarVersion,sum,validCount,avg,expectedCount,missingCount,complete,currency,unit,inputRefs,updatedAt`。

Daily 的 processingStage 固定 `PUBLISHED`，validationStatus 仅 `VERIFIED` 或 `VERIFIED_WITH_NOTICE`；configVersions 为数值升序紧凑 JSON；inputRefs 为按 runId/rawRef/recordVersion 升序的紧凑 JSON，单项字段顺序为 runId、rawRef、recordVersion，且 recordVersion 固定 4。`updatedAt`（DEC-052 确定性语义）表示该 daily 行全部有效 PUBLISHED 输入中 `max(publishedAt)`（按 Instant 比较后统一转换为 Asia/Shanghai 的 ISO-8601 offset datetime），**不是 processing 执行时间**；相同逻辑输入跨执行时间重算必须逐字节一致；缺少合法 publishedAt 必须 fail-closed。

Aggregate 固定表头：`schemaVersion,grain,periodStart,periodEnd,itemId,providerType,actualSourceName,accessMethod,validationStatus,validationVersion,configVersions,calculationVersion,calculationScale,displayScale,roundingMode,calendarVersion,sum,validCount,avg,min,max,expectedCount,missingCount,complete,qualityStatus,currency,unit,sourceFingerprint,inputRefs,calculatedAt`。

Aggregate 的 qualityStatus 仅 `COMPLETE`/`INCOMPLETE`，必须分别对应 complete=true/false。sourceFingerprint 是无 BOM/空白/末尾换行的紧凑 JSON（字段顺序 providerType、actualSourceName、accessMethod）的 SHA-256。inputRefs 的对象字段顺序为 dailyFileRef、businessDate、validationVersion、fileSha256，并按 businessDate/dailyFileRef/validationVersion/fileSha256 升序。`calculatedAt`（DEC-055 确定性语义）表示该 aggregate 行实际参与计算的全部正式 daily inputs 中 `max(daily.updatedAt)`（按 Instant 比较后统一转换为 Asia/Shanghai 的 ISO-8601 offset datetime），**不是 processing 执行时间**；month/quarter/halfyear/year 四级全部直接从各自周期内正式 daily 行独立计算，禁止从下级聚合继承；相同逻辑 daily 输入跨执行时间重算必须逐字节一致；daily updatedAt 缺失/非法或输入不可解析必须 fail-closed。

## ManifestV1 与 DirtyMarkerV1

Manifest 顶层稳定字段：`schemaVersion`、`fileName`、`fileSha256`、`byteLength`、`rowCount`、`minBusinessDate`、`maxBusinessDate`、`sourceRunIds`、`generatedAt`、`commitState`。schemaVersion 固定 `"1.0"`、commitState 固定 `COMMITTED`。JSON 的 rowCount/min/max 为 null；CSV rowCount 不含表头。manifest 与目标同目录，命名 `<完整业务文件名>.manifest.json`，不为 manifest/tmp/bak/dirty 再生成 manifest。

DirtyMarkerV1 顶层稳定字段：`schemaVersion`、`transactionId`、`transactionType`、`createdAt`、`markerRevision`、`transactionPhase`、`targets`。transactionType 仅 `SINGLE_FILE`、`CONFIG_ACTIVATION`、`AGGREGATION_BATCH`；phase 仅 `OPEN`、`COMMITTED`；revision 从 1 开始、每次持久化恰好 +1。target 字段：`order`、`role`、`dataRef`、`manifestRef`、`expectedFileSha256`、`oldFileSha256`、`targetPhase`。CONFIG_ACTIVATION 恰有两个 target：CONFIG_HISTORY/order 1 和 CONFIG_ACTIVE/order 2，覆盖四个物理文件。marker 使用 canonical、`.marker.tmp`、`.marker.bak` 的同 transactionId 自恢复候选，异常/歧义 fail-closed。

### 补充：Raw 冲突与 DirtyMarker 恢复

Raw 的 `YYYY/MM` 与 quarantine 的 `YYYY-MM` 均按 `receivedAt` 在 Asia/Shanghai 的年月路由，不按 sourceBusinessDate 路由。raw、quarantine 与 history 都是 CREATE_NEW：相同完整业务文件 hash 是幂等，异 hash 绝不覆盖。

当既有 raw 的完整业务文件 hash 与 incoming hash 不同，原 raw/manifest 保持不变，并 CREATE_NEW 写入 `runtime/conflicts/raw/<itemId>/YYYY-MM/<runId>/<conflictId>.json` 与相邻 manifest。RawConflictEvidenceV1 字段顺序固定为：`schemaVersion`（固定 `"1.0"`）、`conflictId`、`itemId`、`runId`、`existingRawRef`、`existingFileSha256`、`incomingFileSha256`、`incomingReceipt`（完整 RawReceiptV1 对象）、`detectedAt`。写完后必须抛出明确冲突，incoming 不得进入正常链路。

DirtyMarker canonical 为 `runtime/dirty/<transactionId>.json`；其专用候选固定为 `runtime/dirty/.<transactionId>.json.marker.tmp` 与 `.marker.bak`。业务/manifest target tmp 为 `.<完整目标文件名>.<transactionId>.tmp`，bak 为同名 `.bak`。目标顺序固定为业务文件先于 manifest；全部 target 到 `MANIFEST_COMMITTED` 后才可将 transactionPhase 改为 COMMITTED。恢复只接受同 transactionId、schema 有效、不可变字段逐字一致、revision 单调无缺口的 canonical/tmp/bak 候选；最高 revision 并列而字节不同、字段漂移、跳号或回退均保留证据并 fail-closed。
