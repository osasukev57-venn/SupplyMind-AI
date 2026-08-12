# Day 5 Implementation（2026-08-12）— FAST-R0 连续实施汇总

> 性质：Day5 阶段汇总 Evidence（T01~T05 单一 Evidence，FAST-R0；非任务级 Review、非 Stage Review）。
> Base：`81af88a`（main=`merge: complete Day4`，tag `day4-complete`）；Branch：`feature/d5-core-opencode`。
> Checkpoints：T01=`975e22f`（time rotation）→ T02=`586c015`（cross-file history）→ T03=`effb2a1`（dynamic config）→ T04=`1a74f46`（backfill）→ T05=`9bfcadd`（warning）→ Day5 integration（本 Evidence 随最终 commit）。
> 冻结依据：docs/01 §15 Day5 行与退出条件、docs/03 §8 Day5 行（AT-TIME/AT-XR/AT-CFG/AT-ALT 后端路径）、docs/04 D5-T01~T05、FILE-SCHEMA-V1（runtime/jobs/active、warning/YYYY-MM 路径模式）、DEC-053/054（计算/日历上下文）。

## 1. T01 文件轮转与系统时间变化检测（975e22f）

- 新增：`TimeStateV1`（schemaVersion/stateVersion/lastObservedTime/lastObservedBusinessDate/lastCompletedPeriod/updatedAt，单调推进）、`TimeStateStore`（runtime/jobs/active/time-state.json，原子可重写+manifest，损坏 fail-closed）、`TimeRotationService`（周期边界检测：月/季/半年/年、前跳（含休眠恢复间隔>1 日）、回拨、首次运行、重启恢复；纯观察不写业务文件不造数）、`TimeRotationConfiguration`（Spring 装配 + 启动 recover hook）。
- 冻结路径：time-state 复用 FILE-SCHEMA 已冻结的 `runtime/jobs/active/` 模式（StorageSchemaVerifier/ManifestDerivedFieldsVerifier 同步登记）。
- 测试 9/9：month-end、quarter-end、June half-year、year-end、leap day、forward jump、rollback、restart 恢复、corrupt fail-closed。
- AT-TIME-001/002（后端）=PASS；AT-TIME-003/004 物理系统时间=属 D10-T02，未假造 PASS。

## 2. T02 跨文件与跨年度历史查询（586c015）

- 新增：`HistoryQueryService`（多月 daily + 多年 aggregate 读取、稳定业务键去重、确定性排序、日期范围过滤、missing/corrupt/conflict 显式报告；复用 CsvV1Codec/ManifestVerifier，无第二套 parser）。
- 语义：缺文件=missing 报告绝不补 0；损坏文件=corrupt 报告绝不当无数据；同键同内容=确定性去重；同键不同内容=conflict 报告绝不静默任选；反向日期 fail-closed；重复查询确定。
- 测试 7/7：跨年 merge、去重、conflict、missing、corrupt 文件、corrupt manifest、反向日期、aggregate 跨年。
- AT-XR-001/002=PASS。

## 3. T03 动态监测标的与联动配置（effb2a1）

- 新增：`ConfigManagementService`（ADD/ENABLE/DISABLE/REPLACE，全部走既有 ConfigActivationStore +1/history/manifest 原子激活；无第二 config store）+ `ConfigManagementConfiguration`。
- 依赖验证先于激活：material item 必须带 materialValidation；enabled item 必须有已注册对应 providerType；失败=旧 active config 保持有效。
- REPLACE：旧 item enabled=false（不删历史）、新 item 独立 itemId + supersedesItemId=旧 itemId（不冒充旧序列）；MAT-REPL-01 仅在测试 fixture 使用（GD-05 语义，需求方执行前指定，未发明生产 itemId）。
- H07=PASS（新 target 纯配置，无 Java 业务代码变更）；H09 隐藏=仅 enabled 翻转，历史/文件不删。
- 测试 5/5：ADD GBP、disable/enable EUR（history 快照递增）、REPLACE AZ91D、验证失败保持旧配置、重启保持。
- AT-CFG-001/002/004=PASS；AT-CFG-003（GBP 历史回填/聚合/恢复）由 T03+T04 覆盖。

## 4. T04 新标的 current + history backfill（1a74f46）

- 新增：`BackfillJobStateV1`（WAITING/AWAITING_MANUAL_INPUT/RUNNING/PARTIAL_SUCCESS/SUCCEEDED/FAILED + 检查点）、`BackfillJobStore`（runtime/jobs/active/<jobId>.json 原子+manifest）、`BackfillOrchestrator`（编排既有 Provider→raw→validation→publish→daily→aggregate 全链；Manual 路由诚实 AWAITING_MANUAL_INPUT；无自动历史能力绝不伪装 SUCCEEDED；重复启动幂等复用同 jobId；重启从检查点恢复；完成期自动重建 daily/aggregate）。
- H08=PASS（新 target → current → history → validation → publish → daily → aggregate 真实可演示）；旧历史不删；PARTIAL_SUCCESS 带失败原因。
- 测试 5/5：AWAITING→SUCCEEDED（真实发布后）、PARTIAL_SUCCESS、重复启动幂等、重启恢复、无自动能力不伪装。

## 5. T05 最小规则预警与持久化（9bfcadd）

- 新增：`WarningRuleV1`（ruleId/ruleVersion/ruleKind=PRICE_CHANGE/RATE_CHANGE/COST_IMPACT/DATA_QUALITY、threshold、direction、baselinePeriods、demoRule、description——EXT-07/08 未确认，全部显式 TEST/DEMO 规则并标记非最终业务阈值）、`WarningRecordV1`（warningId/ruleId/ruleVersion/itemId/period/threshold/current/baseline/riskLevel/evidenceRefs/dataStatus/evaluatedAt/inputFingerprint）、`WarningStore`（warning/YYYY-MM/ 不可变证据，同 warningId 幂等 no-op）、`WarningService`（仅消费已发布 aggregate/daily——PENDING/REJECTED/CONFLICT/DEMO 结构上不可能触发；BigDecimal 全链；同逻辑输入→同 fingerprint→同 warningId 不重复）。
- LLM 不参与触发/等级/数值；AT-ALT-001 后端=PASS。
- 测试 5/5：below/equal/above 边界、未验证数据不触发、低完整率 quality 预警、幂等重跑、BigDecimal 精度。

## 6. Day5 Integration（H05-H09 后端路径 + AT 后端）

- `Day5ImplementationIntegrationTest` 1/1（真实全链）：H05 月界轮转→H07 ADD 新 target（纯配置）→H08 backfill（Manual 输入→validation→publish→daily→aggregate，SUCCEEDED）→H06 跨文件历史查询→AT-ALT 确定性预警（无基线不触发）→H09 hide 后旧历史仍可查、daily 文件保留。
- AT 后端路径：AT-TIME-001/002、AT-XR-001/002、AT-CFG-001/002/003/004、AT-ALT-001 全部满足（模块测试+集成测试真实执行）；AT-TIME-003/004 属 D10-T02，明确未假造。

## 7. Regression（真实执行，Java 17.0.19）

| 指标 | 结果 |
|---|---|
| classes | 66 |
| tests | 352 |
| failures | 0 |
| errors | 0 |
| skipped | 7（全部为既有真实联网/真实 raw 门禁，Day4 基线语义未改动；无 Day5 核心测试跳过） |

Day4 无回归：MaterialValidationPipelineTest/MaterialPublishGateTest/MaterialDailyAggregateTest/FutureMaterialDay4ContractTest 等全部绿。

## 8. 边界与状态

- 无数据库；全部持久化沿用冻结目录（time-state/jobs 复用 runtime/jobs/active 模式；warning 用 warning/YYYY-MM）；BigDecimal 全链；Synthetic 正式隔离保持；Publish Gate/validationVersion 绑定未被削弱。
- 已知外部/人工限制（如实记录，非缺陷）：AT-TIME-003/004 物理系统时间属 D10-T02；EXT-07/EXT-08 阈值/成本权重未确认→仅 TEST/DEMO 规则；Manual 历史回填依赖真实人工输入（AWAITING_MANUAL_INPUT 为诚实状态）。
- 当前状态：Day5 Development Tasks=ALL_DONE（实施完成）；Day5 Final Acceptance=PASS（实施侧）；Day5 Stage Review=`PENDING`（待 Sol+第二方对最终 Candidate 大审）；Day 5=`NOT_COMPLETE`。

## 9. R2 STAGE FIX（2026-08-12，`fix: close Day5 stage review findings`；Base failed candidate=`2a5b878`，Sol Stage Review=`CHANGES_REQUESTED`）

- **F1 Time High-Water（RESOLVED）**：`TimeStateV1` 分离 `lastObservedTime`（诊断事实，允许回拨）与 `effectiveHighWaterTime`/`effectiveBusinessDate`/`lastCompletedPeriod`（单调业务高水位，绝不回退）；rollback=observed<highWater，回拨不降水位、不重复触发边界、不重复 publish/daily/aggregate；生产链接入 `RotationGuardedCollectionService`（调度周期在 rollback 时被抑制、恢复后放行）。测试：Aug31→Sep1 仅一次 rotation；Sep1→Aug31 rollback=0 新 rotation；恢复 Sep1=0 重复；Sep2=0 重复月 rotation；guard 抑制/放行断言。AT-TIME-001/002 由真实生产路径成立。
- **F2 History Conflict（RESOLVED）**：同业务键不同内容 → 冲突键从 usable records **排除**（绝不返回任选记录）并在 conflictKeys 报告；相同记录仍确定性去重；结果与遍历顺序无关。测试：冲突文件多次查询 conflict outcome 恒定、usable 不含冲突键。
- **F3 Provider Capability（RESOLVED）**：`DataProvider.supports(MonitorSeriesItemV1)` 通用契约（默认无能力声明；Manual=material+MANUAL 路由、PBOC=人民币汇率中间价+OFFICIAL_WEB，rateKind 驱动，无 itemId 硬编码）；`ConfigManagementService` 激活前验证 Provider 存在**且** capability 支持目标，否则 fail-closed 且旧 active 保持。H07 正确语义：新 target 无需改 Java 代码，前提是已有 Provider 通用 capability 声明支持；Provider 无能力时配置不得假装激活成功。测试：supports=false→ADD rejected+旧配置不变；通用新汇率标的→accepted。
- **F4 Backfill Full Chain（RESOLVED）**：`BackfillOrchestrator` 真实编排 Provider acquisition→raw（外部 HTTP 先持久化 RawAcquisitionV1）→LifecycleValidationService→LifecyclePublishService→Daily→Aggregate；自动目标 WAITING→**RUNNING（真实持久化）**→SUCCEEDED/PARTIAL_SUCCESS/FAILED；Manual 目标 WAITING→AWAITING_MANUAL_INPUT（不伪装 RUNNING/SUCCEEDED）；checkpoint=最近完成业务日期（原子持久化），restart 从 checkpoint 续跑不重复历史；duplicate start 复用 job。测试仅调用 orchestrator 完成全链（rawCount 证明真实 acquisition；禁止测试预置 PUBLISHED）。
- **F5 Warning Cross-Clock（RESOLVED）**：`evaluatedAt` 改自输入血缘（当前周期 aggregate.calculatedAt / daily.updatedAt 最大值），业务 warning 字节不再依赖运行 Clock（Clock 仅用于 manifest generatedAt 等操作元数据）。测试：Clock A/Clock B 相同输入 → 同 warningId/fingerprint/evaluatedAt/逐字节相同持久化 warning。
- **F6 WarningRuleV1 Governance（RESOLVED）**：构造器 fail-closed：`demoRule=false` 直接拒绝（EXT-07/08 未确认；正式规则须未来 rule version + 冻结决策，不得静默改变语义）。测试：demoRule=false→SchemaValidationException；全部输出继续标注 NOT FINAL BUSINESS THRESHOLD。
- **F7 Evidence/docs（RESOLVED）**：本 R2 章节为 CURRENT 状态；前述 T01-T05 描述中与 F1/F2/F4 相关的旧实现事实由本章节 supersede（历史保留）。
- **最新回归（真实执行，feature/d5-core-opencode worktree）：68 classes / 366 tests / 0 failures / 0 errors / 7 skipped**（7=真实联网/真实 raw 门禁；Day5 核心测试全绿；AT-TIME-003/004 仍 PENDING_D10 未假造）。DAY5_STAGE_CANDIDATE_V2 待 integration merge 后以实际最终回归数字为准。
