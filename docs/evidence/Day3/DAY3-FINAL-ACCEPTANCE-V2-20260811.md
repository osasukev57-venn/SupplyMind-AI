# Day 3 Final Acceptance V2（2026-08-11）

> 性质：Day3 阶段级 Acceptance 重新执行（V2），验证 Sol Stage Review（ab28a6c）全部 Finding 在 merged integration tree 真实解除，并形成 DAY3_STAGE_CANDIDATE_V2。
> Base：`integration/day3 @ 1a42e0d`（working tree clean；37c711c、ab42a48 均为 HEAD ancestor）。
> Failed Candidate（历史保留）：`ab28a6c`（V1，Stage Review=`CHANGES_REQUESTED`）——本 V2 重新执行，不 amend/不重写/不删除其 Evidence。
> Lane A=`37c711c`（第二方 Review=PASS）；Lane B=`ab42a48`（第二方 Review=PASS）；integration/combined verification base=`13a1fc2`→`1a42e0d`。
> 冻结依据：docs/01 §15 Day3 行与 Day3 Gate（DEC-057 边界）、docs/03 §3 Acceptance Status 定义与 §8 Day3 退出条件、docs/02 §9 正式验收门禁、DEC-037/050/056/057、FILE-SCHEMA-V1、docs/evidence/Day3/DAY3-STAGE-FINDING-FIX-INTEGRATION-20260811.md。

## 1. Regression（真实执行，Java 17.0.19，base=1a42e0d）

| 指标 | 结果 |
|---|---|
| classes | 46 |
| tests | 247 |
| failures | 0 |
| errors | 0 |
| skipped | 7（均为按设计门禁跳过，见 §8；无 Day3 Gate 核心测试被跳过） |

关键类真实结果（0 failures/errors）：`MaterialDay3AcceptanceTest`(5)、`MaterialRoutePlanProductionTest`(1)、`ManualMaterialIntakeTest`(13)、`LocalImportIsolationTest`(18)、`SyntheticDemoIsolationTest`(3)、`DataProviderRegistryTest`(8)、`FoundationStartupAcceptanceTest`(2)、`PublishedQueryServiceTest`(11)、`PublishGateTest`(9)、`DailyProcessingServiceTest`(19)、`AggregateProcessingServiceTest`(6)、`PbocOfficialWebDataProviderContractTest`(7)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`PbocValidationPipelineTest`(25)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)。

## 2. Sol Findings 在 merged tree 重新验证（非仅引用 Review）

| Finding | 重新验证方式（base=1a42e0d） | 结果 |
|---|---|---|
| F1 四P0仅 test harness | `MaterialRoutePlanProductionTest`（真实 Spring Boot 启动→ApplicationRunner→ConfigActivationStore→active config→Registry→MaterialRoutePlanService→Resolver）：四条 P0 itemId 在 active config；均 FALLBACK_MANUAL/manual-material、fallbackReason 含 credentials_missing、FREE_PUBLIC 层空、synthetic/pboc 非候选；PBOC 对保持 PRIMARY/pboc-official-web | PASS |
| F2 Manual/LocalImport actualSourceName | `ManualMaterialIntakeTest` 13/13（raw.actualSourceName=声明"某供应商报价单（测试）"、providerType 恒 MANUAL）、`LocalImportIsolationTest` 18/18（raw.actualSourceName=文件声明来源、identity 恒 LOCAL_IMPORT）、`MaterialDay3AcceptanceTest` 5/5（含伪标 SMM 不改 provider identity） | PASS |
| F3 XLSX MIME | `MaterialDay3AcceptanceTest`：CSV raw contentType=text/csv；XLSX raw contentType=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet（格式由字节 ZIP magic 识别后写入，非扩展名猜测） | PASS |
| F4 Synthetic Mode | `SyntheticDemoIsolationTest.syntheticOutputUsesDemoModeAndIsStablePerItemRegardlessOfRequestOrder`：Synthetic raw Mode=DEMO、providerType=SYNTHETIC_DEMO、accessMethod=SYNTHETIC_DEMO；无任何 FORMAL Synthetic raw | PASS |
| F5 Synthetic 顺序无关确定性 | 同测试（请求顺序无关断言：Request A/B 按 itemId 逐项相等）；fixed seed、无 list index/iteration order/system time/UUID/mutable Random | PASS |
| F6 状态文档/命名空间/Completeness/Storage | 本 Evidence §5-§7 复核 + docs 当前状态（docs/04、docs/05） | PASS |

## 3. 四P0生产配置与旧 Active Config 保护

- 生产启动路径：`SpringApplicationBuilder(SupplyMindApplication)` → `foundationStorageStartup` ApplicationRunner → `ensureInitialDefault()` 激活 `MonitorSeriesDefaults.initialDay3`（PBOC 双币 + 四条 P0 材料序列，FALLBACK_MANUAL/MANUAL）→ Registry（含 smm/am authorized-api、manual-material、local-import、synthetic-demo、pboc-official-web）→ `MaterialRoutePlanService` 派生三层 route config → `MaterialRouteResolver`。
- 预期路线：PRIMARY=`NOT_CONFIGURED`（credentials_missing）、FREE_PUBLIC=`NO_APPROVED_SOURCE`、selected=`FALLBACK_MANUAL`/`manual-material`——全部断言通过。
- 旧 Active Config 保护：`ensureInitialDefault` 在 active config 存在时直接返回既有配置（`Files.isRegularFile(active)` 分支），不覆盖、不重置、不强制迁移；四P0序列仅是 initial delivery defaults（配置驱动），不破坏 H07/H08 动态目标设计。该行为由 16 处测试调用点与 `FoundationStartupAcceptanceTest` 首启验证共同覆盖。

## 4. Manual / LocalImport / XLSX / Synthetic 逐项

- Manual ADC12/AZ91D：submission→immutable raw→RECEIVED+PENDING→`manual-material-normalization-v1`→**PARSED+PENDING**；operatorRef 服务端可信来源；sourceReference 非空、sourceUrl nullable；最大 lifecycle=PARSED+PENDING，无 VERIFIED/VERIFIED_WITH_NOTICE/PUBLISHED（DEC-057 保持 EFFECTIVE）。
- Manual Revision：same key+same content=IDEMPOTENT_REUSE；same key+不同 actualSourceName/content=NEW_PENDING_VERSION（旧 raw/timeline/operator 审计保留）；actualSourceName 修复未破坏 revision identity。
- LocalImport CSV/XLSX（ADC12+AZ91D）：providerType=LOCAL_IMPORT；actualSourceName=文件声明真实来源；CSV=text/csv、XLSX=正确 OOXML MIME；XLSX Source Raw=Item Raw=ORIGINAL_FULL_FILE_BYTES、Item Raw SHA=source 原始 SHA（MIME 修复未破坏 raw bytes）；XLSX same-key multi-row 各持独立 pending version、重复导入逐行精准复用自身历史、row order 不影响 identity。
- Synthetic：Mode=DEMO；formal route candidate 恒排除；PublishedQuery/Daily（Mode.FORMAL 防线）均 BLOCKED；正式无真实数据=ROUTE_UNAVAILABLE（NO_DATA），绝不自动 synthetic fallback。

## 5. Source Identity 与 RawReceiptStore Identity 保护

- 进入方式（providerType/accessMethod）与实际依据（actualSourceName/declaredSourceName/sourceReference/sourceUrl）全链分离：Manual actualSourceName 含 "SMM" 仍是 MANUAL；LocalImport 声明 SMM 仍是 LOCAL_IMPORT。
- RawReceiptStore（37c711c）identity 校验：允许 actualSourceName 逐记录变化，但 providerType/accessMethod/configVersion snapshot/manifest/HTTP acquisition 链接（`RawAcquisitionLinkVerifier`，DEC-056）约束全部保持——只改 actualSourceName 无法伪造 provider ingress（`RawAcquisitionLinkVerifierTest` 与 rawRef 派生校验全绿）。
- 旧 RawReceipt 兼容：declaredSourceName 缺失旧数据可读（nullable 未改强；`oldRawReceiptWithoutDeclaredSourceNameStaysReadable` 5/5 套件内断言）；PBOC 旧 raw 正常，无需 migration。

## 6. PENDING Gate / Publish Gate / PBOC Formal Chain

- Manual/LocalImport PENDING：PublishedQueryService 空（BLOCKED）、daily 无行（BLOCKED）、aggregate 无文件（BLOCKED）、既有 Publish Gate=NOT_READY；Mode 修复未形成绕过 lifecycle gate 的路径。
- Publish Gate：仅 `PUBLISHED+VERIFIED` 或 `PUBLISHED+VERIFIED_WITH_NOTICE` 进入正式链；本轮 Publish Gate/ProcessingStage/ValidationStatus=UNCHANGED。
- PBOC Formal Chain 回归：PublishedQueryService 的 Mode.FORMAL 过滤与 Publish Gate 一致（非第二套 eligibility）；合法 PUBLISHED+VERIFIED 仍可正常查询（`PublishedQueryServiceTest` 11/11、`DualCurrencyRawLifecycleAcceptanceTest` 3/3）；DailyProcessingService 的 FORMAL 防线拒绝 DEMO 但合法 formal PBOC daily 正常（`DailyProcessingServiceTest` 19/19；DEC-052 daily.updatedAt/grouping/BigDecimal 未回归）；DEC-050/056 保持（Validation 25/25、Contract 7/7、RawAndConfigStore 1/1）。

## 7. Storage Constraints / Determinism / Candidate Completeness / AcceptanceStatus Namespace

- Storage：`git ls-files` 无 data/、runtime/、logs/、target/、无 *.db/sqlite/h2/redis/mongo 依赖；仓库顶层仅 backend/docs/README/.gitattributes/.gitignore；运行时 `raw/import/<importId>.json` 为 data/raw 合法子目录（DataPaths 显式校验），无 data/import、data/normalized、data/published 等非法顶级目录 → PASS。
- Determinism：Synthetic per-item 顺序无关；LocalImport identity=内容哈希（runId 派生）；XLSX multi-row 版本隔离；raw 序列化固定字段顺序+单行 LF+SHA-256；manifest 确定性；default config 固定结构；无 request/filesystem/HashMap order、system time、UUID 影响冻结业务 identity/重放 → PASS。
- Candidate Completeness：HEAD 含 D3-T01～T06 全部 commit + 37c711c + ab42a48 + 状态修正，无遗漏 → PASS。
- AcceptanceStatus Namespace：仅用 docs/03 §3 冻结 token（PASS/FAIL/BLOCKED/N/A_APPROVED_FALLBACK/NOT_RUN）；无 DAY3_PARTIAL_PASS、无 REUSED_VERIFIED_EVIDENCE 作为 Status token；状态/Stage Scope/Evidence Basis 三者分离表达 → PASS。

## 8. Skipped Tests（7）

AtSrc002AcceptanceTest、AggregateRealRawEvidenceTest、DailyRealRawEvidenceTest、PbocOfficialWebRealNetworkAttemptTest、PbocRawClosedLoopSmokeGateTest（真实网络分支）、PublishRealRawEvidenceTest、PbocValidationRealRawEvidenceTest——均为真实联网/真实 raw 证据门禁（离线环境按属性跳过）；无 Day3 Gate 关键核心测试被跳过 → ACCEPTABLE。

## 9. AT-SRC 逐项（V2，真实核验）

| Case | Status（冻结 token） | Day3 Scope / Evidence Basis | Remaining Scope | Stage Blocking |
|---|---|---|---|---|
| AT-SRC-001 | PASS | 完整 Day3 可执行部分；Evidence=本候选真实执行 | 无 | NO |
| AT-SRC-002 | PASS | Evidence Basis=既有已固定验证证据（docs/evidence/AT-SRC-002/，DEC-056 runner 原件 SHA-256，businessDate=2026-08-10 USD=6.7884/EUR=7.8171）；Day3 退出条件不要求重复联网，本轮未伪造联网 | 无（Day2 已完整验收） | NO |
| AT-SRC-005 | PASS | Day3 部分（DEC-057）：四序列三层路由/降级/P0 判定/fallbackReason 可审计；Evidence=本候选真实执行 | 预期2"raw→已验证文件链"属 Day4（D4-T01/D4-T02） | NO |
| AT-SRC-006 | BLOCKED | 无获认可免费公开来源（NO_APPROVED_SOURCE，EXT-10=OPEN_EXTERNAL_NON_BLOCKING），testcase 无法合法完整执行；技术替代闭环由 AT-SRC-005/007 证明 | FreePublic 全链（有认可源后可执行） | NO（docs/01 Day3 Gate 不要求 AT-SRC-006 PASS；三选一由 AT-SRC-007 路线满足） |
| AT-SRC-007 | PASS | Day3 部分（DEC-057）：受理→immutable raw→RECEIVED+PENDING→PARSED+PENDING、真实来源、operator 审计、版本保留、PENDING 出口不可见；Evidence=本候选真实执行 | Day4 部分：material validation→VALIDATED→VERIFIED/VERIFIED_WITH_NOTICE→PUBLISHED→daily→aggregate | NO |
| AT-SRC-008 | PASS | Day3 部分：来源身份不可冒充、伪标拒绝、Synthetic 显式隔离、已实现出口（raw/API/PublishedQuery/PENDING 门禁）一致；Evidence=本候选真实执行 | Dashboard/warning/Agent/EvidencePack 跨出口全量一致性、daily 级出口对账、预期4（Agent 不改写来源）待对应出口实现 | NO |

## 10. Day3 Gate V2 逐条判定（docs/01 §15 + DEC-057 边界）

1. 四来源意图×材料序列各有生产配置下合法 non-synthetic route → PASS（§3）。
2. Manual fallback 具备可追溯 raw、PARSED+PENDING、source identity、operator、revision/version → PASS（§4）。
3. LocalImport 可追溯（CSV/XLSX、字节级 raw、身份/声明来源分离）→ PASS（§4）。
4. 所有 PENDING 数据被正式 Gate 拒绝 → PASS（§6）。
5. Synthetic 非正式 fallback（Mode.DEMO、恒非候选、无自动补值）→ PASS（§4/§5）。
6. 真实 source identity、不可冒充 → PASS（§5）。
7. Storage 合法 → PASS（§7）。
8. Determinism → PASS（§7）。
9. Acceptance 状态准确、无 fabricated PASS → PASS（§9）。
10. DEC-057 边界满足（Day3 不要求材料 VERIFIED/PUBLISHED/daily/aggregate）→ PASS。

## 11. 结论与状态

- Day3 Gate V2 = **PASS**；Day3 Final Acceptance V2 = **PASS**。
- Day3 Stage Review（V2）= PENDING（待 Sol Stage Final Review + 全新独立第二方 Stage Review，同一 Candidate V2）。
- Day 3 = NOT_COMPLETE（Stage Gate 未收口）；未开始 Day4、未 merge main、未调用 Sol。
- 生产代码修改 = NO（本轮纯验收 + 阶段状态记录）。
- FAILED_STAGE_CANDIDATE ab28a6c 与其 Evidence 保留为审计历史（docs/evidence/Day3/DAY3-FINAL-ACCEPTANCE-20260811.md 已标历史，未覆盖）。
- 本 commit（`test: complete Day3 final acceptance v2`）定义为 **DAY3_STAGE_CANDIDATE_V2**，创建后立即冻结。
