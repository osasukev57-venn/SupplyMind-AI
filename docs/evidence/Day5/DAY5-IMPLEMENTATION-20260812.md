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
- 当前状态：Day5 Development Tasks=ALL_DONE（实施完成）；Day5 Final Acceptance=PASS（实施侧）；Day5 Stage Review=`PENDING_DELTA_REVIEW`（Sol Final Delta 已确认 M1/M2/M3=RESOLVED；Stage Gate 未收口）；Day 5=`NOT_COMPLETE`。最终冻结固定点=DAY5_FINAL_STAGE_CANDIDATE（8ec3aaa，见 §12/§13）。

> 【HISTORICAL FAILED STAGE CANDIDATE `2a5b878`】（2026-08-12，集成至 integration/day5；Stage Review=`CHANGES_REQUESTED`，已被 R2 STAGE FIX 修正）：OpenCode Candidate=`c0c8c86` 与 Terra Harness=`bcd1d6b` 曾并入 integration/day5（merge：a98649d + 875e414）。Terra 原 PENDING_IMPLEMENTATION harness 曾启用并绑定真实生产链（Day5FutureAcceptanceIntegrationHarnessTest 5 项、Day5AlertContractHarnessTest 阈值绑定）；唯一保持 PENDING：Day5TimeContractHarnessTest 的 **AT-TIME-003/004（物理系统时间，冻结至 D10-T02）**，Day5 Stage Blocking=NO。该候选回归=`72 classes / 372 tests / 0 failures / 0 errors / 8 skipped`（7=门禁；1=D10 依赖）——保留为历史运行，不再作为当前/最终。
>
> 【HISTORICAL FAILED V2 STAGE CANDIDATE `c8f38e4`】（2026-08-12，`test: complete Day5 R2 stage candidate`；Sol Delta Review=`CHANGES_REQUESTED`，4 个 MAJOR：**M1** Rotation guard 未进入真实 @Scheduled 必经路径（PbocDay2Scheduler 仍有未保护 @Scheduled 直连 runImmediateCycle）、**M2** DataProvider.supports() 默认 true 导致 capability fail-open、**M3** Backfill history acquisition 缺 date/range request + supportsHistoryData gate、**M4** docs/05/Evidence 当前状态不唯一。**该候选不再作为 current PASS candidate**；其回归=`75 classes / 386 tests / 0 failures / 0 errors / 8 skipped`=HISTORICAL FAILED V2 STAGE CANDIDATE REGRESSION（保留为历史）。M1-M3 由下章 DAY5_R2_NARROW_FIX_2 修复，M4 于本轮同步。）

## 10. DAY5_R2_NARROW_FIX_2（2026-08-12，base=`c8f38e4`；`fix: close remaining Day5 production integration gaps`）

- **M1 Scheduled Production Path（RESOLVED）**：生产环境仅保留**一个逻辑正式调度入口**。方案 B：`RotationGuardedScheduler.runGuardedCycle()`（`RotationGuardConfiguration` 内）成为**唯一** `@Scheduled(cron="${supplymind.scheduler.cron:...}")` 方法，且必经 `RotationGuardedCollectionService`（rollback 抑制、放行后真正执行 collection）；`PbocDay2Scheduler.collectOnSchedule()` **移除 `@Scheduled`**（仅保留为手动/运维触发，永不并行执行未保护周期），`scheduled` 使用 `Clock foundationClock` 使调度路径可测。**生产路径测试（真实 Spring context + @EnableScheduling + 真实 RotationGuardConfiguration + 真实 TimeRotationService/TimeStateStore + 真实 PbocDay2CollectionService（fixture transport，无网络）→ raw→validation→publish→daily→aggregate）：**上下文全 bean 扫描断言应用恰好 1 个 @Scheduled acquisition 方法（=runGuardedCycle）、PbocDay2Scheduler 无 @Scheduled；Aug31→Sep1 每次 scheduled invocation 恰好 1 个 cycle（transport 调用精确 +2）、rollback Sep1→Aug31 scheduled invocation **0 collection/publish/process**（transport 不变）、recover Sep1 不重复 Sep 月轮转、Sep2 不重复月轮转、高水位不回退。AT-TIME-001/002 现基于此真实生产入口成立（ScheduledGuardProductionPathTest）。
- **M2 Provider Capability Fail-Closed（RESOLVED）**：`DataProvider.supports()` 默认改为 **false**（fail-closed：只实现基本端口的 provider 一律视为无能力）；全部正式 Provider 显式声明 capability：PBOC Official=OFFICIAL_WEB+PUBLIC_OFFICIAL_HTML+人民币汇率中间价 rateKind（通用 metadata 判断，无 itemId 名单）、Manual=material+MANUAL、LocalImport=LOCAL_IMPORT+material、SyntheticDemo=SYNTHETIC_DEMO+material（DEMO 隔离）。**攻击测试（ProviderDefaultCapabilityFailClosedTest 4 项）**：仅基础端口未 override supports→BLOCKED；providerType 正确但 capability 未声明→BLOCKED（错误信息点名 capability 契约）；显式 generic supports=true+合法 metadata→ALLOWED；REPLACE 失败后旧 active config（configVersion/items）完全不变。H07 恢复语义：新增 target 无需改业务代码，但 Provider 必须显式声明通用 capability（fail-closed 契约）。
- **M3 Backfill History Range Contract（RESOLVED）**：`ProviderCollectRequest` 最小版本化扩展：新增 `CollectionMode`（CURRENT/HISTORY）+ `historyStartDate`/`historyEndDate`（HISTORY 必带 range 且 start<=end；CURRENT 必无 dates；1 参构造=兼容 CURRENT）；静态工厂 `current(...)`/`history(...)`。`BackfillOrchestrator` 自动历史回填前检查 **`provider.profile().supportsHistoryData()`**：不支持 history 的 provider（如 PBOC current-only）**不调用伪历史 collect、不得 SUCCEEDED**，落入冻结诚实状态 `AWAITING_MANUAL_INPUT`（无完成期）或 `PARTIAL_SUCCESS`（有完成期）+ `NO_HISTORY_CAPABILITY` 失败原因（不发明新状态）；每个自动 acquisition 均为 **HISTORY request 携带明确剩余范围 [cursor..to]**（provider 按 request date 返回数据，不依赖内部隐式顺序）；checkpoint 与 history range 绑定（=最近完成业务日期，resume 从 checkpoint+1 继续，已完成日期绝不重采）。**H08 生产测试（BackfillHistoryRangeContractTest 4 项，request-driven provider——读取 request 明确 target date/range 再返回对应数据，无"内部第N次返回第N天"）**：CURRENT/HISTORY 契约 fail-closed 校验；supportsHistoryData=false→0 collect、0 raw、AWAITING_MANUAL_INPUT；true→跨 3 日全链（provider→raw→validation→publish→daily→aggregate，每日 daily 文件+月度 aggregate）、每个请求都带 HISTORY mode 与真实 range end；PARTIAL（第 2 日注入中断）→restart 从 checkpoint+1 续跑、已完成日期请求计数=1（绝不二次请求）、最终 SUCCEEDED。
- **M4 Evidence/docs（RESOLVED）**：本章为 CURRENT；`c8f38e4`=HISTORICAL FAILED V2 STAGE CANDIDATE（上表登记，不再作 current）；`75/386`=HISTORICAL FAILED V2 STAGE CANDIDATE REGRESSION、`68/366`=HISTORICAL R2 FEATURE-BRANCH REGRESSION（`8d701c3`）、`72/372`=HISTORICAL FAILED STAGE CANDIDATE REGRESSION（`2a5b878`）——均明确 HISTORICAL；CURRENT 仅为本轮真实全量回归。
- **已关闭项保护（UNCHANGED_RESOLVED）**：History conflict=EXCLUDED_AND_REPORTED（a2 通过）、Warning cross-clock=BYTE_IDENTICAL（a6 通过）、WarningRule demoRule=false=REJECTED（a7 通过）、EXT-07/08=DEMO_ONLY、H09=PASS、Day4 Regression Protection=PASS（全量 0 failures/errors）、No Database=PASS。
- **当时回归（真实执行，DAY5_R2_NARROW_FIX_2 轮）：78 classes / 395 tests / 0 failures / 0 errors / 8 skipped——HISTORICAL/INTERMEDIATE，已被后续轮 supersede，不再作为当前回归**（7=真实联网/真实 raw 门禁；1=AT-TIME-003/004 D10 物理时间；Day5 核心测试 0 无理由跳过）。AT-TIME-001/002（真实生产调度入口）=PASS、AT-XR=PASS、AT-CFG=PASS、AT-ALT=PASS、H05-H09=PASS、AT-TIME-003/004=PENDING_D10（未假造）。
- **DAY5_STAGE_CANDIDATE_V3_FIX 已形成（生产修复固定点）**；后续步骤=独立 Terra attack + integrated candidate 冻结；Day5 Final Acceptance（实施侧）=PASS、Day5 Stage Review=PENDING、Day 5=NOT_COMPLETE。

## 9. R2 STAGE FIX（2026-08-12，`fix: close Day5 stage review findings`→`8d701c3`；Base failed candidate=`2a5b878`，Sol Stage Review=`CHANGES_REQUESTED`）

- **F1 Time High-Water（RESOLVED）**：`TimeStateV1` 分离 `lastObservedTime`（诊断事实，允许回拨）与 `effectiveHighWaterTime`/`effectiveBusinessDate`/`lastCompletedPeriod`（单调业务高水位，绝不回退）；rollback=observed<highWater，回拨不降水位、不重复触发边界、不重复 publish/daily/aggregate；生产链接入 `RotationGuardedCollectionService`（调度周期在 rollback 时被抑制、恢复后放行）。测试：Aug31→Sep1 仅一次 rotation；Sep1→Aug31 rollback=0 新 rotation；恢复 Sep1=0 重复；Sep2=0 重复月 rotation；guard 抑制/放行断言。AT-TIME-001/002 由真实生产路径成立。
- **F2 History Conflict（RESOLVED）**：同业务键不同内容 → 冲突键从 usable records **排除**（绝不返回任选记录）并在 conflictKeys 报告；相同记录仍确定性去重；结果与遍历顺序无关。测试：冲突文件多次查询 conflict outcome 恒定、usable 不含冲突键。
- **F3 Provider Capability（RESOLVED）**：`DataProvider.supports(MonitorSeriesItemV1)` 通用契约（默认无能力声明；Manual=material+MANUAL 路由、PBOC=人民币汇率中间价+OFFICIAL_WEB，rateKind 驱动，无 itemId 硬编码）；`ConfigManagementService` 激活前验证 Provider 存在**且** capability 支持目标，否则 fail-closed 且旧 active 保持。H07 正确语义：新 target 无需改 Java 代码，前提是已有 Provider 通用 capability 声明支持；Provider 无能力时配置不得假装激活成功。测试：supports=false→ADD rejected+旧配置不变；通用新汇率标的→accepted。
- **F4 Backfill Full Chain（RESOLVED）**：`BackfillOrchestrator` 真实编排 Provider acquisition→raw（外部 HTTP 先持久化 RawAcquisitionV1）→LifecycleValidationService→LifecyclePublishService→Daily→Aggregate；自动目标 WAITING→**RUNNING（真实持久化）**→SUCCEEDED/PARTIAL_SUCCESS/FAILED；Manual 目标 WAITING→AWAITING_MANUAL_INPUT（不伪装 RUNNING/SUCCEEDED）；checkpoint=最近完成业务日期（原子持久化），restart 从 checkpoint 续跑不重复历史；duplicate start 复用 job。测试仅调用 orchestrator 完成全链（rawCount 证明真实 acquisition；禁止测试预置 PUBLISHED）。
- **F5 Warning Cross-Clock（RESOLVED）**：`evaluatedAt` 改自输入血缘（当前周期 aggregate.calculatedAt / daily.updatedAt 最大值），业务 warning 字节不再依赖运行 Clock（Clock 仅用于 manifest generatedAt 等操作元数据）。测试：Clock A/Clock B 相同输入 → 同 warningId/fingerprint/evaluatedAt/逐字节相同持久化 warning。
- **F6 WarningRuleV1 Governance（RESOLVED）**：构造器 fail-closed：`demoRule=false` 直接拒绝（EXT-07/08 未确认；正式规则须未来 rule version + 冻结决策，不得静默改变语义）。测试：demoRule=false→SchemaValidationException；全部输出继续标注 NOT FINAL BUSINESS THRESHOLD。
- **F7 Evidence/docs（RESOLVED）**：本 R2 章节为 CURRENT 状态；前述 T01-T05 描述及 HISTORICAL FAILED STAGE CANDIDATE 中与 F1/F2/F4 相关的旧实现事实由本章节 supersede（历史保留）。
- **R2 feature-branch 回归（真实执行，`8d701c3` worktree）：68 classes / 366 tests / 0 failures / 0 errors / 7 skipped**——HISTORICAL R2 FEATURE-BRANCH REGRESSION（非当前/最终）。AT-TIME-003/004 仍 PENDING_D10 未假造。该 V2 集成基线及 `75/386` 回归已被 §10 DAY5_R2_NARROW_FIX_2 的 `78/395` supersede（历史保留）。
>
> 【DAY5_STAGE_CANDIDATE_V2 CURRENT】（2026-08-12，integration/day5 真实合并树）：**8d701c3**=R2 PRODUCTION FIX（`fix: close Day5 stage review findings`）；**4775559**=R2 INTEGRATED MERGE；**6ca96cb**=INDEPENDENT R2 ATTACK HARNESS（Terra，`test: attack Day5 R2 stage findings`，3 类 8 用例）。攻击 harness 全部真实 PASS 且无 disabled/PENDING（唯一未来依赖：Day5TimeContractHarnessTest AT-TIME-003/004=PENDING_D10）：a1 Rollback High-Water Attack（F1 单调水位+rollback 抑制）、a2 History Conflict Attack（F2 冲突键排除、分区顺序无关）、a3 Provider Capability Attack（F3 仅类型存在不足、通用新标的可激活）、a4 Backfill Full-Chain Attack（F4 真实 acquisition→raw→validation→publish→daily→aggregate，全 artifact 断言）、a5 Backfill Resume/Manual Attack（checkpoint 恢复不重复 artifact；Manual/无 provider history 诚实非成功状态）、a6 Cross-Clock Warning Attack（F5 跨 Clock 字节相同）、a7 WarningRule Governance Attack（F6 demoRule=false fail-closed）。**该 V2 候选已被 `c8f38e4`（HISTORICAL FAILED V2 STAGE CANDIDATE）supersede，其后又被 §10 DAY5_R2_NARROW_FIX_2 当时回归（78/395）supersede——本段为历史记录，不再作为当前状态。**（V2 当时回归=`75 classes / 386 tests / 0 failures / 0 errors / 8 skipped`=HISTORICAL FAILED V2 STAGE CANDIDATE REGRESSION。）

## 11. DAY5_R2_FINAL_PRODUCTION_FIX（2026-08-12，base=`e8b0a07`；`fix: close final Day5 provider and backfill gaps`）

> 触发：Terra 独立攻击 `7bbcf8c`（feature/d5-r2-second-attack-terra-v2，`test: attack remaining Day5 production integration gaps`，3 类 6 用例：Day5SecondDefaultCapabilityAttackTest 2、Day5SecondHistoryRangeAttackTest 3、ScheduledEntryPostFixAttackTest 1）在 e8b0a07 上稳定复现两个真实生产 Finding。攻击测试文件当时未 merge 本分支、未修改；本轮仅在临时 worktree 中以其验证修复（全部 PASS）。

- **F1 All Formal Provider Capability Explicit（RESOLVED）**：`DataProvider.supports()` 默认保持 fail-closed=false；`MaterialSourceConfiguration` 的 **smmAuthorizedApiProvider / amAuthorizedApiProvider**（SMM/Asian Metal AuthorizedApi 占位，无配置凭证）不再继承接口默认——显式实现 `supports(item)=false`（基于其 profile 语义：AUTHORIZED_API 访问 + 无真实 acquisition 能力，fail-closed；无 itemId 名单、无 ADC12/AZ91D 硬编码；未来配置真实凭证须按 generic metadata 重新声明）。全 Provider 扫描确认每个正式 Provider 均显式 override：PbocOfficialWebDataProvider（OFFICIAL_WEB+PUBLIC_OFFICIAL_HTML+人民币汇率中间价）、ManualDataProvider（material+MANUAL）、LocalImportDataProvider（LOCAL_IMPORT+material）、SyntheticDemoDataProvider（SYNTHETIC_DEMO+material）、SMM/AM AuthorizedApi（显式 false）。验证测试（Day5FinalProviderCapabilityDeclarationTest 2 项）：反射扫描 4 个正式类 + MaterialSourceConfiguration Spring context 全部 DataProvider bean，断言无任何 provider 继承接口默认 supports；AuthorizedApi 占位无凭证时显式 fail-closed。
- **F2 Backfill Range Completion Rule（RESOLVED）**：SUCCEEDED 不再由"月份文件存在/任意 raw/published 数量>0/完成月份数≥范围月份数"判定——改为 **requested range vs 连续完成高水位** 的确定性判定：`checkpoint` 只在当天成功**且**与前一完成日连续（cursor==contiguous+1）时推进；循环结束仅当 `checkpoint==job.toDate()`（整个范围逐日连续完成）才 SUCCEEDED，否则 PARTIAL_SUCCESS（有进展）/FAILED（无进展）；失败日期冻结 checkpoint，后续日期成功绝不越过失败缺口（resume 首先处理失败日）。验证测试（BackfillRangeCompletionRuleTest 4 项）：Case A 8/1+8/2 成功 8/3 失败→PARTIAL_SUCCESS、checkpoint=8/2、resume 只请求 8/3、最终 SUCCEEDED（resume 后 rawCount=3 不重采 8/1/8/2）；Case B 8/1 成功 8/2 失败 8/3 成功→checkpoint 冻结 8/1（8/3 成功不越过缺口）、resume 首先处理 8/2；Case C 全成功→SUCCEEDED；Case D duplicate start 复用 job 且不新增 acquisition。
- **保护项（UNCHANGED_PASS，全部真实执行）**：ScheduledGuardProductionPathTest（唯一正式 @Scheduled 必经 guard）1/1、TimeRotationService 11/11、Day5R2RotationHistoryCapabilityAttackTest a1/a2/a3、Day5R2WarningAttackTest a6/a7、WarningServiceTest 7/7、H09（Day5ImplementationIntegrationTest）、Day4 全套 0 failures、No Database。
- **当时回归（真实执行，FINAL_PRODUCTION_FIX feature run）：80 classes / 401 tests / 0 failures / 0 errors / 8 skipped**——HISTORICAL/INTERMEDIATE（按 src/test 下 .java 文件数统计 classes），已被 §12 最终集成 supersede，不再作为当前回归。
- **状态**：e8b0a07=上一 R2 fix point（HISTORICAL）；7bbcf8c=independent attack（两个 Finding 已由本轮关闭）；59533c4=FINAL PRODUCTION FIX（HISTORICAL，非当前候选）。Day5 Final Acceptance（实施侧）=PASS；Day5 Stage Review=PENDING；Day 5=NOT_COMPLETE（未提前 COMPLETE / Stage Review PASS）。

## 12. DAY5_FINAL_R2_INTEGRATED_STAGE_BUILD（2026-08-12，integration/day5 最终合并树；`59533c4`=FINAL PRODUCTION FIX、`7bbcf8c`=INDEPENDENT FINAL ATTACK TEST、`12119b6`=merge: add final Day5 attack tests）

> 攻击→修复→同一攻击 PASS 的完整事实链（失败历史保留，不删除、不伪装一次成功）：
> - `7bbcf8c` 首次执行（独立 Terra 攻击，e8b0a07 时代）：**Default Capability Attack=FAIL**（smmAuthorizedApiProvider/amAuthorizedApiProvider 继承 DataProvider 默认 supports()）、**History Range Contract Attack=FAIL**（8/1、8/2 成功 8/3 失败时 Backfill job 错误 SUCCEEDED，checkpoint/resume 无法只继续 8/3）——独立发现生产缺陷的历史事实。
> - `59533c4`=FINAL PRODUCTION FIX：F1 全部正式 Provider capability 显式声明（SMM/AsianMetal AuthorizedApi=EXPLICIT_UNSUPPORTED，无凭证 fail-closed，无 itemId/ADC12/AZ91D 硬编码；全 Provider 扫描 0 继承默认）；F2 Backfill range-completion rule（SUCCEEDED 仅当 requested range 全部 required date 连续成功且 checkpoint==endDate；checkpoint=连续高水位，失败日期绝不越过；resume 首先处理失败日；不按月份文件/任意 artifact 推断 SUCCEEDED）。
> - 最终 integration/day5 合并树同一攻击测试：**全部真实 PASS**——Default Capability Attack 2/2、History Range Contract Attack 3/3、Scheduled Entry Post-Fix Attack 1/1（7bbcf8c 原样执行，未修改）。

### 最终树验证（真实执行，Java 17，integration/day5 最终合并树）

- 攻击 harness 全部 PASS（29/29，含 Terra 6 项 + R2 a1-a7 8 项 + 本分支攻击 15 项）：ScheduledGuardProductionPathTest 1/1（唯一正式 @Scheduled=受 guard 保护入口，Unguarded Scheduled Entry=NONE）、Day5R2RotationHistoryCapabilityAttackTest 3/3（a1 rollback 高水位、a2 history conflict EXCLUDED_AND_REPORTED、a3 provider capability）、Day5R2BackfillAttackTest 3/3（a4/a5 全链+resume+manual 诚实）、Day5R2WarningAttackTest 2/2（a6 跨时钟 BYTE_IDENTICAL、a7 demoRule=false REJECTED）、BackfillRangeCompletionRuleTest 4/4（Case A/B/C/D）、Day5FinalProviderCapabilityDeclarationTest 2/2（全 Provider 显式声明）、BackfillHistoryRangeContractTest 4/4（H08 request contract）、ProviderDefaultCapabilityFailClosedTest 4/4（M2 fail-closed）。
- H05=PASS、H06=PASS、H07=PASS（新 target 纯配置+Provider 显式通用 capability）、H08=PASS（history range request contract + checkpoint resume）、H09=PASS（hide 保留历史）；AT-TIME-001/002=PASS（真实生产调度入口）、AT-TIME-003/004=PENDING_D10（未假造）、AT-XR=PASS、AT-CFG=PASS、AT-ALT=PASS。
- 已关闭 Finding 无回归：History Conflict=EXCLUDED_AND_REPORTED、Warning Cross-Clock=BYTE_IDENTICAL、WarningRuleV1 demoRule=false=REJECTED、EXT-07/08=DEMO_ONLY、Day4 Regression Protection=PASS（全量 0 failures/errors）、No Database=PASS。
- **当时集成回归统计（真实执行，Java 17，integration/day5 最终合并树）：85 classes / 407 tests / 0 failures / 0 errors / 8 skipped**（按 src/test 下 .java 文件数统计 classes，含 2 个非测试 support 类——统计口径与 clean Surefire 差异见 §13；本值由 §13 CURRENT 取代，HISTORICAL/SUPERSEDED）。Skipped 8 项逐项：AtSrc002AcceptanceTest（真实联网门禁）、PbocOfficialWebRealNetworkAttemptTest（真实联网门禁）、PbocRawClosedLoopSmokeGateTest 1 项（真实联网门禁）、AggregateRealRawEvidenceTest/DailyRealRawEvidenceTest/PublishRealRawEvidenceTest/PbocValidationRealRawEvidenceTest（真实 raw 门禁）=7 项；Day5TimeContractHarnessTest 1 项=AT-TIME-003/004 D10 物理系统时间（PENDING_D10）。Day5 核心测试 0 无理由跳过。
- **DAY5_FINAL_STAGE_CANDIDATE 已形成并冻结（8ec3aaa）**。Day5 Development Tasks=ALL_DONE、Day5 Final Acceptance=PASS（实施侧）、Day5 Stage Review=PENDING（后续 M4 closure 更新为 PENDING_DELTA_REVIEW）、Day 5=NOT_COMPLETE（未提前 COMPLETE / Stage Review PASS）。历史/中间运行（均非当前）：80/401=59533c4 feature run、78/395=e8b0a07 run、75/386=c8f38e4 FAILED V2、68/366=8d701c3 feature run、72/372=2a5b878 FAILED。

## 13. DAY5_FINAL_M4_EVIDENCE_CLOSURE（2026-08-12，base=`8ec3aaa`；`docs: reconcile Day5 final stage evidence`）

- **M4-1 当前候选唯一登记**：`8ec3aaa`=**CURRENT FINAL STAGE CANDIDATE**（唯一当前候选）。历史候选明确且不与 8ec3aaa 同时显示为 CURRENT：`2a5b878`=HISTORICAL FAILED STAGE CANDIDATE、`c8f38e4`=HISTORICAL FAILED V2 STAGE CANDIDATE、`e8b0a07`=HISTORICAL R2 FIX POINT、`59533c4`=FINAL PRODUCTION FIX（HISTORICAL）、`7bbcf8c`=INDEPENDENT FINAL ATTACK TEST（HISTORICAL）。Day5 状态：Development Tasks=ALL_DONE、Final Acceptance=PASS、Stage Review=**PENDING_DELTA_REVIEW**（Sol Final Delta 已确认 M1/M2/M3=RESOLVED、H05-H09=PASS、Acceptance=PASS、BLOCKER=无；Stage Gate 尚未收口）、Day 5=NOT_COMPLETE（未提前 COMPLETE）。
- **M4-2 回归状态唯一化**：全文档 CURRENT FINAL TECHNICAL REGRESSION 仅此一处（本节下方）。历史/中间回归一律 HISTORICAL/INTERMEDIATE/SUPERSEDED：68/366（8d701c3 feature run）、72/372（2a5b878 FAILED）、75/386（c8f38e4 FAILED V2）、78/395（e8b0a07 run）、80/401（59533c4 feature run）均不得再带 CURRENT/当前最终语义。
- **M4-3 clean Surefire 对账（真实执行，`mvn clean test`）**：按 `backend/target/surefire-reports/TEST-*.xml` 本次新生成报告统计：**83 suites（classes）/ 407 tests / 0 failures / 0 errors / 8 skipped**。与 Sol 核验一致（83/407/0/0/8）。**85→83 差异解释**：85=src/test/java 下 .java 文件数（含非测试 support 类）；83=Surefire 实际执行的 test suite 数；差异 2 项=`GoldenArithmeticHarness`（day4/foundation harness 辅助类，非测试）与 `DomainFixtures`（foundation/model fixture 辅助类，非测试）——均非 executed test class，不计入 classes。正式采用 **83 / 407 / 0 / 0 / 8** 为 CURRENT FINAL TECHNICAL REGRESSION。
- **M4-4 UTF-8 Evidence 修复**：本文件此前 §11/§12 因追加写入产生编码损坏（U+FFFD mojibake，1412 处替换字符）——已整体重写为有效 UTF-8，中文字符完整可读，无 replacement character；攻击 FAIL→FIX→PASS 历史链完整保留（见 §12 引言），未借修编码改写业务结论；`git diff --check`=PASS。
- **CURRENT FINAL TECHNICAL REGRESSION（真实执行，2026-08-12，`mvn clean test`，integration/day5 最终合并树 @ 8ec3aaa）：83 classes / 407 tests / 0 failures / 0 errors / 8 skipped**（7=真实联网/真实 raw 门禁；1=AT-TIME-003/004 D10 物理时间；Day5 核心测试 0 无理由跳过）。
- **DAY5_FINAL_M4_CLOSURE_CANDIDATE 已形成**；Day5 Stage Review=PENDING_DELTA_REVIEW（待 Sol+第二方 Stage Gate 收口）、Day 5=NOT_COMPLETE（不提前 COMPLETE）。