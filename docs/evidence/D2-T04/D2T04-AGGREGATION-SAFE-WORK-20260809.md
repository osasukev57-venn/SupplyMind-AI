# D2-T04 PBOC 历史读取与多周期聚合最小闭环 —— 安全工作实施记录

> 证据性质：D2-T04 部分实施记录（聚合计算安全工作）。
> 本任务存在一个正式业务裁决待办：`aggregate.calculatedAt` 的确定性语义在冻结文档中未定义（详见"业务裁决待办"）。
> 本窗口仅提交不依赖该裁决的安全工作；D2-T04 未标记 REVIEW_PENDING。
> 不是 AT-SRC-002、Day 2 总门禁的 PASS 证据；AT-SRC-002 仍为 `NOT_RUN`。

## 执行范围

- 执行时间：2026-08-09（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`
- 输入：D2-T03 daily 行的冻结 schema（DailyRecordV1 解码结果）与周期/精度规则（总计划 8.4.5、CALCULATION-RULES、DEC-053/054）
- 未使用真实网络；未修改 D1-T05 smoke 根

## 业务裁决待办（BUSINESS_DECISION_REQUIRED）

冻结文档（总计划 8.4.5 Aggregate 固定表头、FILE-SCHEMA-V1、CALCULATION-RULES、DEC-048/052/053/054）对 aggregate 行 `calculatedAt` 仅有表头字段名与类型约束（ISO-8601 offset datetime），**未定义其业务语义或确定性来源**。与 D2-T03 daily.updatedAt（DEC-052 裁决前）情形相同：若取 processing Clock 则跨执行时间重算 aggregate 字节不同（AT-AGG-001"文件重算结果与首次计算完全一致"要求不满足）；任何取值方案（照搬 daily.updatedAt / processing Clock / 其他）均属自创口径。按本窗口指令，**不自行决定**，等待技术负责人裁决（建议参照 DEC-052 模式：明确 calculatedAt 的确定性来源，如取该 aggregate 行参与 daily 输入中 max(daily.updatedAt)，或独立语义）。裁决前不实现依赖该字段语义的写盘服务与完整 Evidence。

## 安全工作实施内容（不依赖 calculatedAt 语义）

| 组件 | 职责 |
|---|---|
| `processing/PeriodBoundaries.java`（新增） | 自然周期边界：month（自然月）/quarter（1-3、4-6、7-9、10-12 月）/halfyear（1-6、7-12 月）/year（自然年）的 periodStart/periodEnd |
| `processing/ExpectedBusinessDayCounter.java`（新增） | expectedCount 日历计数：`weekday-asia-shanghai-v1`（Asia/Shanghai 周一至周五，DEC-054）、`golden-calendar-v1`（GD-01 fixture：每月 10/20 日） |
| `processing/AggregateInput.java`（新增） | 聚合输入载体：daily 行 + dailyFileRef + dailyFileSha256 |
| `processing/AggregateCalculator.java`（新增） | 冻结四级聚合纯计算：按冻结分组键分组（itemId/来源身份/校验结论/validationVersion/币种/单位/计算上下文/period）；仅聚合 businessDate ∈ [periodStart, periodEnd] 的有效 daily 输入；sum=参与 daily avg 精确和、validCount=daily 行数、avg=sum.divide(validCount, calculationScale, roundingMode)、min/max=参与 daily avg 精确最小/最大；expectedCount=日历计数、missingCount=max(expectedCount-validCount,0)、complete=validCount>=expectedCount、qualityStatus 由 complete 派生；configVersions=被引用 daily 行 configVersions 去重升序并集；sourceFingerprint=CanonicalJsonV1.sourceIdentity 的 SHA-256；inputRefs=冻结字段（dailyFileRef/businessDate/validationVersion/fileSha256）按冻结顺序升序、覆盖全部 validCount 输入；`calculatedAt` 由调用方传入（本计算器不决定其语义） |

复用冻结：`AggregateRecordV1`/`AggregateInputRefV1`/`AggregateGrain`/`QualityStatus`/`CanonicalJsonV1`（模型层强制校验 sourceFingerprint、min/max atScale、missingCount/complete/qualityStatus 派生、inputRefs 覆盖 validCount）。未创建任何物理文件写入（写盘服务待 calculatedAt 裁决）。

## 计算规则（冻结算法，总计划 8.4.5 / CALCULATION-RULES / C34）

- 月、季、半年、年全部**直接从属于同一计算上下文的有效 daily avg 字符串重算**；
- 禁止读取月均聚合结果作为季/半年/年输入；任何层级禁止读取 displayScore（displayScale）结果作为输入；
- sum/min/max 不发生未声明舍入（BigDecimal 精确）；avg 仅在最终除法按 calculationScale/roundingMode 舍入；
- missing 不计权重、不补 0；空周期不生成虚构数据（expectedCount 仅用于 completeness 派生）；
- 相同逻辑输入 → 确定性相同结果（输入顺序无关，计算器内排序确定）。

## 测试结果（Java 17）

### `AggregateCalculatorTest`（11 tests，0 failures，0 errors）

| 用例 | 结果 |
|---|---|
| 月聚合与手工复算一致（3 行 daily avg：sum=20.69040000、validCount=3、avg=6.89680000、min=6.79040000、max=7.10000000；2026-01 expectedCount=22、missingCount=19、INCOMPLETE；configVersions/inputRefs/calculatedAt 传入值） | PASS |
| sourceFingerprint 与独立 JDK SHA-256（冻结 JSON 向量）一致 | PASS |
| 季/半年/年直接从 daily avg 重算（sum/avg/min/max 独立 BigDecimal 复算一致；Q1 expectedCount=64、H1=129、Y=261） | PASS |
| 季结果与"月均再平均"错误对照不同（AT-PREC-003 模式） | PASS |
| 跨月 daily 行 → 各月独立行（1月 validCount=2、2月=1，2月 expectedCount=20）+ 季度行（validCount=3） | PASS |
| 缺失日不计权重（1 行 vs expectedCount 22 → missingCount=21） | PASS |
| 校验结论/计算上下文切换分行（VERIFIED / VERIFIED_WITH_NOTICE / scale 12 各一行） | PASS |
| displayScale 永不作为输入（sum 保留 8 位精确值，非 2 位截断） | PASS |
| expectedCount 工作日边界（1 月 22、2 月 20、3 月 22、Q1 64） | PASS |
| golden-calendar-v1 fixture 计数（每月 10/20 日：1 月 2、1-2 月 4） | PASS |
| 输入顺序不影响结果 | PASS |

### 最小直接回归（8 类 79 tests，0 failures，0 errors）

`AggregateCalculatorTest`(11)、`DailyProcessingServiceTest`(19)、`CsvV1CodecTest`(2)、`PublishGateTest`(9)、`PbocValidationPipelineTest`(25)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)、`PbocOfficialWebDataProviderContractTest`(6)。

## 未实施部分（依赖业务裁决）

- 四级 writer / 写盘服务（从 data/processed/daily 读取 → 计算 → 原子写 processed/aggregate/.../YYYY.csv + manifest）：待 `calculatedAt` 语义裁决；
- 重启读取（Writer A → Reader B）测试与真实 PBOC aggregate Evidence：待上述裁决后随写盘服务实施；
- D2-T04 任务状态不标记 REVIEW_PENDING（按窗口指令：未形成完整可审快照时不虚假标记）。

## 状态与边界

- 未创建任何 aggregate 物理文件；未修改 AT-SRC-002（`NOT_RUN`）；未进入 D2-T05。
- 未修改 D2-T01/D2-T02/D2-T03 生产代码、DEC-050~054、CALCULATION-RULES、FILE-SCHEMA-V1 与既有 evidence。
