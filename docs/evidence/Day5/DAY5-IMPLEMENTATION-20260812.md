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
>
> 【Stage Candidate 追加】（2026-08-12，集成至 integration/day5）：OpenCode Candidate=`c0c8c86` 与 Terra Harness=`bcd1d6b` 已并入 integration/day5（merge：a98649d + 875e414）。Terra 原 PENDING_IMPLEMENTATION harness 对账：Day5FutureAcceptanceIntegrationHarnessTest 5 项（H05-H09 后端入口）已启用并绑定真实生产链真实执行（5/5 PASS）；Day5AlertContractHarnessTest 阈值包含关系已绑定生产 WarningService 严格比较语义（equal 不触发/above 触发，PASS）；唯一保持 PENDING：Day5TimeContractHarnessTest 的 **AT-TIME-003/004（物理系统时间，冻结至 D10-T02）**，Day5 Stage Blocking=NO。**最终回归（真实执行，Java 17.0.19）：72 classes / 372 tests / 0 failures / 0 errors / 8 skipped**（7=真实联网/真实 raw 门禁；1=AT-TIME-003/004 D10 依赖；无 Day5 核心测试无理由跳过）。H05-H09 全部 PASS（后端；物理系统时间留 D10）。DAY5_STAGE_CANDIDATE 已形成并冻结。
