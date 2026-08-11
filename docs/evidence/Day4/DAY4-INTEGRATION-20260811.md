# Day 4 Integration（2026-08-11）— FAST-R0 连续实施汇总

> 性质：Day4 阶段汇总 Evidence（T01~T05 单一 Evidence，FAST-R0；非任务级 Review、非 Stage Review）。
> Base：`c9c89f0`（main=`merge: complete Day3`）；Branch：`feature/d4-t01-validation-opencode`。
> Checkpoints：T01=`108a228`（DEC-059）→ T02=`a8840f5` → T03=`39f8db1` → Terra harness merge=`2eec930`（e78d852）。
> 冻结依据：docs/01 §15 Day4 行与 Day3 Gate 延伸、docs/03 §8 Day4 退出条件、DEC-057（§6-§9 职责边界）、DEC-058（阶段子用例）、DEC-059（材料校验规则）、FILE-SCHEMA-V1、CALCULATION-RULES、GD-01~GD-07。
>
> 【Stage Candidate 追加】（2026-08-11，集成至 integration/day4）：OpenCode Core `2ebe8c6` 与 Terra Extended Harness `562d437` 已并入 integration/day4（merge=`0ce512f`）；Terra `WAIT_PRODUCTION_CONFIG`（unit/currency 映射）已绑定生产默认配置并启用（MaterialBasicValidationV2ContractHarnessTest 5/5）；3 个原 PENDING_IMPLEMENTATION 测试（AT-SRC-005-D4/007-D4/008-D4 入口）前置已存在，已启用并真实执行（FutureMaterialDay4ContractTest 3/3）；**HISTORICAL REGRESSION RUN（superseded）**：60 classes/311 tests/0 failures/0 errors/7 skipped（7 项全为真实联网/真实 raw 门禁，无 Day4 核心测试跳过）；Day4 Gate（实施侧）=PASS；DAY4_STAGE_CANDIDATE 已形成并冻结。
>
> 【R2 Stage Fix 追加】（2026-08-11，`fix: close Day4 stage review findings`）：Candidate `a9fe94a` Sol Stage Review=`CHANGES_REQUESTED`（4×MAJOR，本轮修复）：1) publish 门禁改为正式版本白名单（material 仅 material-basic-validation-v2；v1/未知/未来版本伪造 VALIDATED+VERIFIED 也 NOT_READY；PBOC 自有版本不受影响）；2) conflict 判定先于 stale notice（同键异值 age>7=CONFLICT；同键同值 age>7 保留 DUPLICATE 信息；invalid spec/unit+stale 仍 REJECTED）；3) 生产 DailyGroupKey/AggregateGroupKey 加入 canonicalSpecCode（daily/aggregate CSV 新增 canonicalSpecCode 列并保留行级血缘；同 itemId/日期/来源不同 spec=独立 group 绝不混算；旧文件无该列仍可读，spec=null）；4) docs/04 同步 DEC-059（v2=当前正式、v1=历史 superseded）。**HISTORICAL REGRESSION RUN（superseded）**：60 classes/317 tests/0 failures/0 errors/7 skipped（真实执行；连同此前 55/298/0/0/10 均为历史运行，已被 CURRENT FINAL 60/320/0/0/7 supersede）。
>
> 【Final Closure】（2026-08-11，`docs: close Day4 stage`）：**Final Reviewed Candidate=`a1bbb00`**（Sol Final Delta=`PASS`、Independent Final Delta=`PASS`、BLOCKER=无、MAJOR=无）；**Day4 Stage Review=`PASS`**；**Day 4=`COMPLETE`**（Stage Gate 收口）。Current Final Technical Regression=`60 classes/320 tests/0 failures/0 errors/7 skipped` 保持；父用例 AT-SRC-005/007/008=`NOT_RUN`（Final P0 UNCHANGED）、AT-SRC-005-D4/007-D4/008-D4=`PASS`、AT-SRC-006=`BLOCKED`（Stage Blocking=NO）。历史 Review 链不重写；不创建新 Candidate。

## 1. DEC-059（T01）

- `material-basic-validation-v2`=当前正式材料 validationVersion；`material-basic-validation-v1` 仅历史保留（语义未改、不得用于新发布——publish 门禁强制拒绝）。
- MonitorSeriesItemV1 新增显式 `materialValidation`（MaterialValidationConfigV1：valueMinExclusive="0"、valueMaxInclusive=null、staleThresholdDays=7、canonicalSpecCode、acceptedSpecAliases=[]）；材料 item 缺失配置=构造 fail-closed（配置激活 fail-closed）；非材料 item 不得携带。
- 校验（确定性顺序，历史执行记录；顺序语义已由 R2 修正，见下方 CURRENT EFFECTIVE RULE）：CONFIG_MISSING→mode→item/Provider 身份→来源字段一致性（payload 声明↔raw↔candidate）→payload 完整性→unit/currency→future date→spec normalized-exact（NFKC+trim+ASCII uppercase vs canonicalSpecCode；aliases=[] 不推定等价）→value（<=valueMinExclusive REJECTED；valueMaxInclusive=null 无上限）→stale（calendarAgeDays>7=VERIFIED_WITH_NOTICE；==7 不 stale；不回退 DEC-050 30 日）→duplicate（NOTICE）/conflict（CONFLICT）。
- **CURRENT EFFECTIVE RULE（supersede 上一条校验顺序中的 stale/duplicate/conflict 相对顺序）**：duplicate/conflict 判定先于 stale notice——同业务键不可兼容事实（同键异值）=`CONFLICT/VALUE_CONFLICT`（无论 age）；同键同值=保留 `DUPLICATE_OBSERVATION` notice（即使 age>7，duplicate 信息不丢失）；stale（calendarAgeDays>7）仅在通过 required fields/value/unit/currency/spec/source identity/future/conflict 全部合法性判断后，才把 VERIFIED 提升为 `VERIFIED_WITH_NOTICE/STALE_BUSINESS_DATE`；stale 绝不把 REJECTED/CONFLICT 覆盖成 VERIFIED 类（MaterialCandidateValidatorV2，`fix: close Day4 stage review findings`）。
- 四个 P0 series（MAT.ADC12.SMM/AM、MAT.AZ91D.SMM/AM）在 MonitorSeriesDefaults.initialDay3 显式携带配置。

## 2. T01~T04 实现与测试

| Task | 生产变更 | 验证（真实执行） |
|---|---|---|
| T01 validation | MaterialCandidateValidatorV2、MaterialCandidateStandardizer（local-import-material-normalization-v1）、LifecycleValidationService 按 rateKind=material 分发、MaterialValidationConfigV1+MonitorSeriesItemV1 | MaterialValidationPipelineTest 24/24：ADC12/AZ91D VERIFIED、缺字段/非法 decimal intake REJECTED、unit/currency mismatch、future、来源字段不一致、伪标 SMM 身份保持、FreePublic 伪标签不自动可信、revision/conflict/duplicate、LocalImport CSV+XLSX 同 gate、Synthetic DEMO 隔离、value=0/<0 REJECTED、age=7 不 stale、age=8 NOTICE、缺配置 fail-closed、normalized spec 兼容、unknown spec/别名不推定 REJECTED、v1 历史保持、validationVersion/configVersion 追溯 |
| T02 publish | LifecyclePublishService：拒绝 material-basic-validation-v1 结果与 DEMO-mode（Synthetic 永不正式发布）；Manual/LocalImport/FreePublic 同门禁无旁路 | MaterialPublishGateTest 6/6：v2 材料（Manual/LocalImport/FreePublic）→PUBLISHED+PublishedQuery 可见；v1→NOT_READY；PENDING→NOT_READY、REJECTED/CONFLICT→QUARANTINED；Synthetic DEMO→NOT_READY |
| T03 daily | 无生产变更（D2-T03 通用服务经材料输入验证：grouping=item+来源身份绑定，cross-spec/source 不可能混入） | MaterialDailyAggregateTest：daily CSV 冻结固定表头、逐日行、avg/sum/validCount/inputRefs(recordVersion=4)/validationVersion 上下文、缺失日不补 0、不同声明来源分行、确定性排序 |
| T04 aggregate | 无生产变更（D2-T04 通用服务验证） | MaterialDailyAggregateTest：month/quarter/halfyear/year 四级从合法 daily 重建、冻结固定表头、sourceFingerprint+inputRefs、无展示舍入回写 |

## 3. Terra Golden/Contract Harness（e78d852 merge=2eec930）

- `Day4GoldenFixtureManifestTest` 2/2：GD-01~GD-07 黄金 fixture SHA-256 清单校验（SHA256SUMS 已含全部 7 项：GD-01=a5366545…、GD-02=a9a2e465…、GD-03=71faed41…、GD-04=1a00e8e8…、GD-05=e19904a4…、GD-06=4f0f166a…、GD-07=6a424919…）。
- `AtPubDay4ContractHarnessTest` 3/3、`AtPrecDay4ContractHarnessTest` 4/4、`AtAggDay4ContractHarnessTest` 3/3、`Day4DailyAggregateSchemaContractTest` 2/2（冻结表头/精度契约）。
- `FutureMaterialDay4ContractTest` 3 项 `@Disabled(PENDING_IMPLEMENTATION)`：Terra 占位入口（空断言）；D4 真实子用例验证由本 Lane 已实现测试承担（T01~T04 全绿），未修改 Terra 文件。

## 4. Regression（HISTORICAL REGRESSION RUN，superseded；真实执行，Java 17.0.19）

| 指标 | 结果 |
|---|---|
| classes | 55 |
| tests | 298 |
| failures | 0 |
| errors | 0 |
| skipped | 10（7 门禁跳过：真实联网/真实 raw 证据；3 Terra @Disabled 占位） |

PBOC 无回归：PbocValidationPipelineTest 25/25、DailyProcessingServiceTest 19/19、AggregateProcessingServiceTest 6/6、PublishGateTest 9/9、PublishedQueryServiceTest 11/11、Contract 7/7、DualCurrency 3/3、RawAndConfigStore 1/1。

## 5. AT-SRC 子用例（DEC-058）

- AT-SRC-005 Parent=`NOT_RUN`；**AT-SRC-005-D4=PASS**（D4-T01 validation→D4-T02 publish 的 raw→已验证文件链：Manual/LocalImport/FreePublic 材料均经 material-basic-validation-v2→PUBLISHED+VERIFIED 类；证据=MaterialValidationPipelineTest+MaterialPublishGateTest 真实执行）。
- AT-SRC-007 Parent=`NOT_RUN`；**AT-SRC-007-D4=PASS**（完整正式链 validation→VALIDATED→VERIFIED 类→PUBLISHED→daily→aggregate；证据=MaterialValidationPipelineTest+MaterialPublishGateTest+MaterialDailyAggregateTest）。
- AT-SRC-008 Parent=`NOT_RUN`；**AT-SRC-008-D4=PASS**（材料 daily/aggregate 出口一致性：daily/aggregate 行保存 providerType/accessMethod/actualSourceName/validationVersion/inputRefs/sourceFingerprint，来源身份跨出口一致；不同声明来源分行、cross-source 不混入）。
- AT-SRC-006=`BLOCKED`、Stage Blocking=`NO`（EXT-10=OPEN_EXTERNAL_NON_BLOCKING 保持）。

## 6. Day4 Gate 判定

- docs/03 §8 Day4 行：AT-PUB、AT-PREC、AT-AGG 及选定 AT-SRC-006/007 通过；GD-01~GD-07 均有 SHA-256 → 满足（Terra harness 14/14 + 本 Lane 材料链测试；GD SHA 清单完整）。
- docs/01 Day4 行：Day1-2 最小链推广到全部 Provider（validation/publish/daily/aggregate 对 Manual/LocalImport/FreePublic 材料实测）、手工/免费源不得绕门禁（无旁路测试）、H01/H02 核心计算（BigDecimal 精度契约 harness 通过）。
- DEC-058：Day4 Gate 引用 AT-SRC-005-D4/007-D4/008-D4（各自 PASS），不要求 parent 提前 PASS；Final P0 UNCHANGED。
- **Day4 Gate = PASS（实施侧）**；Day4 Stage Review/Stage Gate 收口待 Sol+第二方对最终 integration candidate 大审（本窗口不执行）。

## 7. 边界

- 无 PUBLISHED 之外的越界（发布仅 VALIDATED+VERIFIED 类、material-basic-validation-v2）；无 v1 新材料发布；Synthetic DEMO 永不进入正式链；无隐藏 DB/新目录（Storage 与 D3 基线一致）；BigDecimal 全链 string 构造；无 cross-spec/source/context 混算；历史结果不改写（timeline immutable；v1 语义冻结）。
