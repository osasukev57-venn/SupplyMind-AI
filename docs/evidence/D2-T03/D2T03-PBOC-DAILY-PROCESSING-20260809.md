# D2-T03 PBOC 每日加工与 CSV 持久化实施记录

> 证据性质：D2-T03 任务级每日加工实现/测试/真实 raw 证据记录。
> 不是 AT-SRC-002、Day 2 总门禁的 PASS 证据；AT-SRC-002 仍为 `NOT_RUN`。
> EXT-03（每日均值业务定义）与 EXT-06（节假日/未发布日）尚未关闭；按冻结 DoD，本任务不得标 DONE 或宣称正式业务口径通过（提交 `REVIEW_PENDING`）。

## 执行范围

- 执行时间：2026-08-09（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`
- 输入：D2-T02 已发布记录（仅 PUBLISHED+VERIFIED 类）、不可变配置 history、冻结 CALCULATION-RULES（arithmetic-mean-v1 / weekday-asia-shanghai-v1 版本化 P0 默认）、Daily CSV 冻结 schema（总计划 8.4.5 / FILE-SCHEMA-V1）
- 未使用真实网络：输入全部来自已落盘真实 raw 与合成 fixture

## 前置 Gate / EXT

| 项 | 状态 |
|---|---|
| D2-T02 已发布记录（发布门禁） | DONE（复用 D2-T02 冻结发布语义：仅 PUBLISHED+VERIFIED / PUBLISHED+VERIFIED_WITH_NOTICE 进入） |
| 不可变配置 history（RawReceipt.configVersion → config/history/&lt;version&gt;.json） | 通过 `VersionedConfigReader`（D2-T01 通过能力）逐输入解析，DoD 要求"configVersions 均能解析到 history" |
| EXT-03 每日均值业务定义 | 未关闭；使用冻结版本化 P0 默认 `arithmetic-mean-v1`（CALCULATION-RULES 登记，带版本状态标注） |
| EXT-06 节假日/未发布日 | 未关闭；使用冻结版本化 P0 默认 `weekday-asia-shanghai-v1`（daily expectedCount=1 固定，不依赖日历计数） |
| DoD 约束 | 按冻结原文："EXT-03/EXT-06未关闭或未书面接受版本化默认时，本任务不得标DONE或宣称正式业务口径通过"——本记录与任务状态保持 `REVIEW_PENDING`，不宣称口径通过 |

## 实现内容（最小范围，未改动 D1/D2 基础）

| 组件 | 职责 |
|---|---|
| `processing/DailyProcessingService.java`（新增） | 按月（businessDate 的 YearMonth）扫描 staging，仅接受当前快照为 `PUBLISHED+VERIFIED` 类的运行（冻结发布门禁谓词，绝不扫描 raw/candidate 绕过 Gate）；逐输入经 TimelineStore.read（manifest 校验）、raw/manifest 校验读取、`VersionedConfigReader.readVersion(raw.configVersion())` 解析不可变 history 配置；计算 daily 行并原子写 `processed/daily/<itemId>/YYYY-MM.csv` + 相邻 manifest |
| `processing/DailyMeanCalculator.java`（新增） | 冻结 arithmetic-mean-v1 纯计算：精确 BigDecimal sum（不舍入）、avg=sum.divide(validCount, calculationScale, roundingMode)、expectedCount=1、missingCount=max(1-validCount,0)、complete=validCount>=1；按冻结每日分组键分组（itemId/businessDate/来源身份/校验结论/validationVersion/币种/单位/计算上下文），不同来源、单位、币种、校验结论或计算上下文绝不混行 |
| `processing/DailyInput.java`、`DailyResult.java` | 输入载体与结果载体 |

复用：`CsvV1Codec.encodeDaily/decodeDaily`（冻结表头/排序/RFC 4180/CRLF/无 BOM）、`DailyRecordV1`/`DailyInputRefV1`（冻结模型，含 missingCount/complete/inputRefs 覆盖校验）、`AtomicFileStore`（immutableData=false 原子替换 + DirtyMarker）、`ManifestFactory.csv`、`ManifestVerifier`、`TimelineStore`、`VersionedConfigReader`。未修改任何 D1/D2-T01/D2-T02 生产代码与冻结 schema。

## 核心执行链（实际实现数据流）

```
staging/<runId>.json（TimelineStore.read，manifest 校验）
  → 当前快照 PUBLISHED + VERIFIED/VERIFIED_WITH_NOTICE？（发布门禁谓词）
  → candidate.itemId == 目标 item 且 businessDate ∈ 目标月
  → raw/manifest 校验读取（payloadSha256/rawRef）
  → VersionedConfigReader.readVersion(raw.configVersion()) → item 计算上下文
  → DailyInput（value/校验结论/validationVersion/configVersion/runId/rawRef/recordVersion=4/计算上下文）
  → DailyMeanCalculator 分组计算（精确 sum；avg 仅按 calculationScale/roundingMode 一次舍入）
  → CsvV1Codec.encodeDaily（固定表头、规范行序、RFC 4180、CRLF）
  → AtomicFileStore.commit(processed/daily/<itemId>/YYYY-MM.csv + manifest, immutableData=false)
  → manifest：rowCount=数据行数、min/max businessDate、sourceRunIds=全部输入 runId（去重升序）
```

空月（无有效输入）不生成文件、不补 0；缺失日不产生行。

## 正式输入资格

仅 `PUBLISHED+VERIFIED` / `PUBLISHED+VERIFIED_WITH_NOTICE` 进入计算；RECEIVED+PENDING、PARSED+PENDING、VALIDATED（未发布）、VALIDATED+REJECTED、VALIDATED+CONFLICT、RECEIVED+REJECTED 与 quarantine 投影一律不可见（`nonPublishedAndInvalidRecordsNeverEnterDaily` 覆盖）。同键同日观测按冻结分组键处理：不同校验结论分行（VERIFIED 与 VERIFIED_WITH_NOTICE 各自成行）；相同分组的多条输入合并计算（validCount>1）并保留完整 inputRefs（runId/rawRef/recordVersion=4 升序，覆盖全部 validCount，禁止退化为单一 rawRef）。

## 计算/处理规则（冻结 arithmetic-mean-v1，CALCULATION-RULES）

- sum：精确 BigDecimal 相加，不舍入，toPlainString 持久化
- avg：`sum.divide(validCount, calculationScale, roundingMode)`，持久化恰好 calculationScale 位
- displayScale：仅记录于行内（API/UI 展示边界语义），不参与计算、不回写
- expectedCount=1（单日默认）；missingCount=`max(1-validCount,0)`；complete=`validCount>=1`
- configVersions：全部输入 RawReceipt.configVersion 去重数值升序（每行 = 该行 inputRefs 引用 raw 的 configVersion 集合）
- 分组键：itemId+providerType+actualSourceName+accessMethod+businessDate+currency+unit+validationStatus+validationVersion+calculationVersion+calculationScale+displayScale+roundingMode+calendarVersion
- 缺失不补 0；BigDecimal 全部从 String 构造，无 float/double

测试技术说明：D2-T01 冻结的重复/冲突规则使同源同日不同值观测必然判 `VALUE_CONFLICT`（隔离）、同值观测分裂为 VERIFIED 与 VERIFIED_WITH_NOTICE（冻结分组按校验结论分行）。因此"多观测日/循环小数/12位"等需要 validCount>1 且同组同校验结论的用例，以直接构造 PUBLISHED 快照的 fixture 技术实现（与 D1-T03 golden 同款），每日服务本身仍只接受 PUBLISHED+VERIFIED 类；真实同值重复经完整流水线验证其真实分裂行为。

## 文件输出

- `data/processed/daily/<itemId>/YYYY-MM.csv`（格式：UTF-8 无 BOM、RFC 4180、CRLF、冻结 25 列表头；行按冻结规范顺序；inputRefs/configVersions 为 RFC 4180 转义紧凑 JSON）
- 相邻 `<文件名>.manifest.json`：fileSha256/byteLength/rowCount/minBusinessDate/maxBusinessDate/sourceRunIds/commitState=COMMITTED
- 原子写入：AtomicFileStore 单文件事务（DirtyMarker，旧文件 bak 备份、tmp 原子替换），失败保留旧 daily
- 未创建任何新业务物理目录；`normalized`/`published` 保持逻辑概念

## 真实 PBOC 验证（复用 D1-T05 真实 raw，无网络）

输入复制自 `backend/data/d1-t05-smoke`（config/raw/staging 连同 manifest，逐字节一致；真实页面 SHA-256=`f37cda1f…4f82`，businessDate=2026-08-07），经 D2-T01 校验、D2-T02 发布后按 2026-08 月加工：

| itemId | sum | avg | updatedAt（DEC-052） | dailyRef |
|---|---|---|---|---|
| FX.USD.CNY.PBOC_MID | 6.7904 | 6.79040000（scale 8） | `2026-08-09T22:50+08:00`（= 真实 PUBLISHED input publishedAt） | `processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv` |
| FX.EUR.CNY.PBOC_MID | 7.8067 | 7.80670000（scale 8） | `2026-08-09T22:50+08:00`（= 真实 PUBLISHED input publishedAt） | `processed/daily/FX.EUR.CNY.PBOC_MID/2026-08.csv` |

- 每行：PUBLISHED / VERIFIED / `pboc-basic-validation-v1` / configVersions=[1] / validCount=1 / inputRefs 指向真实 runId（`pboc-usd-…`/`pboc-eur-…`，recordVersion=4）/ currency=CNY / unit=CNY/1 USD、CNY/1 EUR
- 真实 raw 文件加工后逐字节未动；manifest 校验通过（rowCount/min/max/sourceRunIds 对账）；重启解码（decodeDaily）与计算结果一致
- 机器可读证据：`docs/evidence/D2-T03/d2-t03-real-raw-daily-summary.json`

## 测试结果（Java 17）

### `DailyProcessingServiceTest`（19 tests，0 failures，0 errors）

| 用例 | 结果 |
|---|---|
| 单值日 → 冻结 daily 行，golden bytes 逐字节一致（`contracts/v1/valid/daily-pboc-v1.csv`），manifest 全字段对账，decode 往返一致 | PASS |
| 多观测日（fixture 双 VERIFIED：6.7904+6.8000）→ sum=13.5904、validCount=2、avg=6.79520000、inputRefs 2 条升序 | PASS |
| 缺失日不产生行；空月不生成文件 | PASS |
| 同值重复经真实流水线 → VERIFIED 行 + VERIFIED_WITH_NOTICE 行分行，各自 validCount=1 | PASS |
| PENDING/REJECTED/CONFLICT/未发布 VALIDATED 全部不可进入 daily；发布后仅合法观测进入 | PASS |
| 循环小数（6.7904+6.7904+7.8067）→ sum=21.3875 精确、avg=7.12916667（scale 8 HALF_UP 一次舍入） | PASS |
| 12 位持久化/9 位展示（100.1+100.2+100.2，scale 12）→ sum=300.5、avg=100.166666666667、displayScale=9 不回写 | PASS |
| 配置版本切换（同计算上下文 V1+V3）→ 单行 configVersions=[1,3]、validCount=2 | PASS |
| 计算上下文切换（scale 8 vs 12）→ 两行分行，avg 分别 6.79040000 / 6.790400000000 | PASS |
| 重算幂等（固定 Clock）→ 字节一致 | PASS |
| 重启读取：独立 Reader B（全新实例、同一物理 dataRoot）→ CSV/manifest 存在、fileSha256/byteLength/rowCount/min/max/sourceRunIds 对账、ManifestVerifier 通过、decodeDaily 逐字段独立期望一致 | PASS |
| 跨 Clock 幂等（processing Clock A≠B）→ CSV bytes/SHA/updatedAt 一致 | PASS |
| updatedAt = 单输入 publishedAt（DEC-052） | PASS |
| updatedAt = 多输入 max(publishedAt)（08:00/09:30/09:00 → 09:30） | PASS |
| 输入顺序反转 → updatedAt 不变 | PASS |
| 新增较旧输入不变 / 新增较新输入推进 | PASS |
| Instant 比较（非 offset 文本字典序）+ Asia/Shanghai 输出 | PASS |
| VERIFIED 与 VERIFIED_WITH_NOTICE 分行各自 max | PASS |
| missing publishedAt fail-closed（模型 + 存储边界，不落盘） | PASS |

### 真实 raw 门禁 `DailyRealRawEvidenceTest`（gated `-Dd2-t03.real-raw=true`，PASS）

见"真实 PBOC 验证"。

### 最小直接回归（10 类 83 tests，0 failures，0 errors，1 skipped=gated）

`DailyProcessingServiceTest`(19)、`DailyRealRawEvidenceTest`(1)、`PublishedQueryServiceTest`(11)、`PublishGateTest`(9)、`PbocValidationPipelineTest`(25)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)、`CsvV1CodecTest`(2)、`PbocOfficialWebDataProviderContractTest`(6)。

## D2-T03 DoD 逐项

| DoD 条款 | 状态 | 证据 |
|---|---|---|
| EUR/CNY、USD/CNY 均生成 daily | PASS | 真实 raw 门禁：双币 `processed/daily/<itemId>/2026-08.csv` 生成并解码一致 |
| sum 不舍入 | PASS | 多观测/循环小数用例断言精确 sum（13.5904 / 21.3875 / 300.5） |
| avg 只按 calculationScale/roundingMode 舍入 | PASS | 6.79520000 / 7.12916667 / 100.166666666667，全部为一次除法舍入 |
| displayScale 不回写 | PASS | 12/9 用例：avg 12 位持久化、displayScale=9 仅记录 |
| 缺失不补 0 | PASS | 缺失日无行、空月无文件 |
| configVersions 均能解析到 history | PASS | 每条输入经 `VersionedConfigReader.readVersion(raw.configVersion())` 解析，失败即 fail-closed；[1]、[1,3] 断言 |
| 重启可读 | PASS | `restartReadDecodesDailyFromDisk` + 真实门禁 decodeDaily 一致 |
| EXT-03/EXT-06 未关闭或未书面接受版本化默认时不得标 DONE / 宣称正式业务口径通过 | 遵守 | 本任务提交 `REVIEW_PENDING`；evidence 明确 EXT-03/EXT-06 未关闭，使用冻结版本化 P0 默认（arithmetic-mean-v1 / weekday-asia-shanghai-v1），不宣称口径通过 |

## 状态与边界

- 数据生命周期：真实双币 daily 已生成（输入为 PUBLISHED+VERIFIED）；未进入聚合（D2-T04）、warning、dashboard、Agent。
- 失败回退：保留 raw/已发布值与旧 daily；写入经 DirtyMarker 原子事务，失败不产生正式半文件。
- 未修改 AT-SRC-002（保持 `NOT_RUN`）；未进入 D2-T04；未绕过发布门禁；无 float/double。

## Review Fix 记录（CHANGES_REQUESTED，2026-08-09）

正式 Review（固定 commit=`caa6216`）结论：BLOCKER=无；MAJOR 1（重算确定性 updatedAt 来源）；MAJOR 2（restart 证据不充分）。

### MAJOR 1：daily.updatedAt 确定性 —— IMPLEMENTED（DEC-052）

技术负责人正式裁决 **DEC-052**（`docs/06-DECISION-LOG.md`）：daily 行 `updatedAt` = 该行全部有效 PUBLISHED 输入的 `max(publishedAt)`（按 Instant 比较），统一转换 Asia/Shanghai ISO-8601 offset datetime 输出；**不是 processing 执行时间**。实施：

- `DailyInput` 增加必填 `publishedAt`（来源：实际进入 daily 的 PUBLISHED Lifecycle snapshot.publishedAt，经 TimelineStore 校验读取；不来自 raw/文件时间/businessDate 构造）；
- `DailyMeanCalculator.deterministicUpdatedAt(inputs)`：Instant 比较取 max，`OffsetDateTime.ofInstant(latest, +08:00)` 输出；输入顺序无关；
- `calculateRow` 的 updatedAt 完全由输入 publishedAt 决定，processing Clock 不再进入行内容（仅 manifest `generatedAt` 保留为提交元数据，非业务文件派生字段）；
- `DailyProcessingService` 在收集输入时对 `publishedAt == null` fail-closed（`StorageException`）；模型层 PUBLISHED 快照缺 publishedAt 亦 fail-closed（SchemaValidationException）。

跨 Clock 幂等：`sameInputsAcrossDifferentProcessingClocksProduceIdenticalBytesAndSha`——相同业务输入，processing Clock A=`2026-08-10T10:00+08:00`、Clock B=`2026-08-10T18:30+08:00`，CSV bytes/SHA-256/updatedAt/sum/validCount/avg/configVersions/inputRefs 完全一致（两 harness 校验/发布时钟相同以保证输入 publishedAt 一致，仅 daily processing 时钟不同）。

### MAJOR 2：真正独立重启读取 —— FIXED（保留）

`restartReaderReinitializesIndependentlyAndVerifiesCsvAndManifestFromDisk` 维持：Writer A 完整写链后完全丢弃 → 同一物理 dataRoot 全新 Reader B（全新 DataRoot/AtomicFileStore/TimelineStore/DailyProcessingService）→ 从磁盘定位 CSV+manifest → fileSha256/byteLength/rowCount/min/max/sourceRunIds/ManifestVerifier 对账 → decodeDaily 逐字段独立冻结期望验证（不使用 A.rows().equals 作为唯一 oracle）。未因 DEC-052 退化。

### DEC-052 测试覆盖（`DailyProcessingServiceTest` 19 tests）

| 用例 | 结果 |
|---|---|
| 跨 Clock 幂等（Clock A≠B）→ CSV bytes/SHA/updatedAt/全部字段一致 | PASS |
| 单输入 updatedAt = 该输入 publishedAt（09:02+08:00，非 processing Clock） | PASS |
| 多输入 updatedAt = max(publishedAt)（08:00/09:30/09:00 → 09:30） | PASS |
| 输入顺序反转 → updatedAt 不变 | PASS |
| 新增较旧输入（08:30）→ updatedAt 不变（09:00）；新增较新输入（10:00）→ updatedAt 推进（10:00） | PASS |
| Instant 比较（2026-08-10T02:00+08:00 vs 2026-08-09T23:00+01:00 → 后者 Instant 更晚）→ updatedAt=2026-08-10T06:00+08:00（Asia/Shanghai 输出，非 offset 文本字典序） | PASS |
| VERIFIED 与 VERIFIED_WITH_NOTICE 分行，各自独立取本组 max | PASS |
| missing publishedAt fail-closed（模型层构造拒绝 + 存储边界 commit 拒绝且不落盘） | PASS |

EXT-03/EXT-06 保持 `OPEN`；D2-T03 保持 `REVIEW_PENDING`，不得 DONE。
