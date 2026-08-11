# Day 3 Stage Finding Fix Integration（2026-08-11）

> 性质：Day3 Stage Fix 集成验证（Combined Regression + Sol Finding 映射 + Storage/Determinism/Completeness 检查 + AcceptanceStatus 命名空间清理）。
> 目的：确认两条 Stage Fix Lane 合并后无互相回归，并为 Day3 Final Acceptance V2 准备干净基线。
> 本轮不形成 Stage Candidate V2；Day3 Final Acceptance V2=`PENDING`；Day 3=`NOT_COMPLETE`。

## 1. Candidate / Merge 事实

- FAILED_STAGE_CANDIDATE：`ab28a6c`（Day3 Final Acceptance V1，Sol Stage Review=`CHANGES_REQUESTED`）。
- Lane A：`37c711c`（fix: correct Day3 material delivery provenance；第二方 Review=`PASS`）→ merge `6508ca3`。
- Lane B：`ab42a48`（fix: isolate deterministic synthetic demo data；第二方 Review=`PASS`）→ merge `13a1fc2`。
- Merged integration base（本 Evidence 验证对象）：`integration/day3 @ 13a1fc2`，working tree clean；`git merge-base --is-ancestor 37c711c HEAD` 与 `git merge-base --is-ancestor ab42a48 HEAD` 均成功。

## 2. Sol Findings → Fix → Review → Integrated Verification 映射

| Sol Finding（ab28a6c Stage Review） | Fix commit | Review result | Integrated verification |
|---|---|---|---|
| F1 四P0材料序列仅存在于 test harness，生产默认交付配置只有 PBOC | 37c711c（`MonitorSeriesDefaults.initialDay3` 交付默认 + `MaterialSourceConfiguration` 注册 smm/am authorized-api + `MaterialRoutePlanService` 从 active config+registry 派生三层 route） | 第二方 PASS | `MaterialRoutePlanProductionTest`（真实 Spring Boot 启动）：active config 含四条 P0 itemId；四序列经生产 registry/resolver/probe 均 FALLBACK_MANUAL/manual-material、fallbackReason 含 credentials_missing、FREE_PUBLIC 空、synthetic/pboc 非候选；PBOC 对保持 PRIMARY |
| F2 Manual/LocalImport raw actualSourceName 写 ingress label | 37c711c（raw/candidate actualSourceName=声明真实来源；`RawReceiptStore` identity 校验改为仅比对 providerType/accessMethod） | 第二方 PASS | `ManualMaterialIntakeTest` 13/13、`LocalImportIsolationTest` 18/18、`MaterialDay3AcceptanceTest` 5/5：actualSourceName=声明来源、provider identity 恒 MANUAL/LOCAL_IMPORT、伪标 SMM 不改身份 |
| F3 XLSX RawReceipt contentType 硬编码 text/csv | 37c711c（`LocalImportService.CONTENT_TYPE_XLSX`，格式由字节 ZIP magic 识别后写入） | 第二方 PASS | `MaterialDay3AcceptanceTest`：CSV=text/csv、XLSX=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet；XLSX Raw bytes=ORIGINAL_FULL_FILE_BYTES 保持 |
| F4 Synthetic Mode.FORMAL 泄漏 | ab42a48（SyntheticDemo 落盘 Mode.FORMAL→Mode.DEMO） | 第二方 PASS | `SyntheticDemoIsolationTest` 新增 205 行：Mode.DEMO 边界断言全绿 |
| F5 Synthetic 顺序相关确定性 | ab42a48（`request.itemIds().stream().sorted()`） | 第二方 PASS | 同测试：请求顺序无关断言全绿 |
| Storage Constraints = FAIL | 非代码缺陷：由候选/配置缺失关联；Lane A/B 未新增任何存储路径 | — | 见 §4：PASS |
| Determinism = FAIL | ab42a48（Synthetic 排序） | 第二方 PASS | 见 §5：PASS |
| Candidate Completeness = FAIL | 37c711c+ab42a48+状态修正全部并入 | — | 见 §6：PASS |
| AcceptanceStatus 命名空间（DAY3_PARTIAL_PASS/REUSED_VERIFIED_EVIDENCE 非冻结 token） | 本轮 docs 状态清理 | — | 见 §7：PASS |

## 3. Combined Regression（真实执行，Java 17.0.19，merged base=13a1fc2）

- 关键组合验证：四 P0 生产 route 与 Synthetic formal isolation 同时成立（`MaterialRoutePlanProductionTest` 1/1 + `SyntheticDemoIsolationTest` + `MaterialDay3AcceptanceTest` 5/5 + `DataProviderRegistryTest` 8/8 + `FoundationStartupAcceptanceTest` 2/2）。
- Manual × Synthetic 防线：Manual 最大 lifecycle 仍 `PARSED+PENDING`；Terra 的 PublishedQuery/Daily `Mode.FORMAL` 防线只拒绝非 FORMAL，不改变合法 Manual/LocalImport FORMAL lifecycle（`ManualMaterialIntakeTest` 13/13、`LocalImportIsolationTest` 18/18、`PublishGateTest` 9/9）。
- PBOC formal chain 无回归：`PbocOfficialWebDataProviderContractTest` 7/7、`DualCurrencyRawLifecycleAcceptanceTest` 3/3、`PbocValidationPipelineTest` 25/25、`DailyProcessingServiceTest` 19/19、`AggregateCalculatorTest` 15/15、`RawAndConfigStoreTest` 1/1、`AtomicFileStoreWriteInvariantTest` 6/6；合法 `PUBLISHED+VERIFIED` 仍进入正式业务链（mode=FORMAL 不受防线影响）。
- 全量结果：

| 指标 | 结果 |
|---|---|
| classes | 46 |
| tests | 247 |
| failures | 0 |
| errors | 0 |
| skipped | 7（按设计门禁跳过：真实联网/真实 raw 证据类） |

## 4. Storage Constraints（Sol FAIL 项复核）

- 仓库顶层仅 `backend/`、`docs/`、README、.gitattributes、.gitignore；`git ls-files` 无 `data/` 内容、无 `*.db`/sqlite/h2、无 target/ 或 runtime/generated 文件误入库。
- LocalImport 导入文件证据路径 `DataPaths.importRef` = `raw/import/<importId>.json`，是 `data/raw` 的合法子目录（DataPaths 路径校验显式允许 raw/import 三段结构），不是新的非法顶级 data 目录。
- 结论：原 FAIL 由候选/配置缺失关联导致（生产默认配置只有 PBOC、Synthetic 泄漏），Lane A/B 修复后不存在真实存储约束问题 → **PASS**。

## 5. Determinism（Sol FAIL 项复核）

- Synthetic per-item output：ab42a48 对 `request.itemIds()` 排序，输出与请求顺序无关；Golden Scenario fixed seed 不变。
- LocalImport identity：runId=内容哈希（`import-<itemId>-<date>-<sha256(row)>`），与请求/目录顺序无关；XLSX same-key multi-row 逐行独立版本（`sameXlsxSameKeyMultipleRowsAreSeparateVersionsAndNeverDedupe`、`xlsxRowOrderDoesNotAffectBusinessContentIdentity` 全绿）。
- Raw 序列化/manifest：`JsonV1Codec` 固定字段顺序 + 单行 LF + SHA-256；manifest 由确定性字节生成；无 current-time/UUID 进入业务 identity（receivedAt/updatedAt 仅运行审计；UUID 仅 dirty-marker/conflict 事务 id，不进业务结果）。
- Production default config：`MonitorSeriesDefaults.initialDay3` 为固定结构，激活时以激活时间戳落盘（config 语义），不含随机/顺序依赖。
- 结论 → **PASS**。

## 6. Candidate Completeness

- HEAD（13a1fc2）含 D3-T01～T06 全部任务 commit（86c8e3f/ee7cbc7/0fbe48d→2c7398d/9611c66/0e8165c→…→c6ec283/60e6925→bca29b8）以及 Lane A 37c711c、Lane B ab42a48 与其 merge commits（6508ca3、13a1fc2）；无遗漏 feature commit → **PASS**。

## 7. AcceptanceStatus 命名空间清理

- 依据 docs/03 §3 冻结状态定义：合法 token 仅 `PASS`/`FAIL`/`BLOCKED`/`N/A_APPROVED_FALLBACK`/`NOT_RUN`。
- 状态表示法统一为：`Status`（冻结合法 token）+ `Scope`（阶段范围说明）+ `Evidence Basis`（证据来源说明）三者分离：

| Case | Status（冻结合法 token） | Scope / Stage Note | Evidence Basis |
|---|---|---|---|
| AT-SRC-001 | PASS | 完整 Day3 可执行部分 | 本候选真实执行 |
| AT-SRC-002 | PASS | Day2 已完整验收；Day3 退出条件不要求重复联网 | 既有已固定验证证据（`docs/evidence/AT-SRC-002/`，DEC-056 runner 原件 SHA-256；非本次联网执行） |
| AT-SRC-005 | PASS | Day3 部分（DEC-057 阶段拆分）：路由/降级/P0 判定；预期2"raw→已验证文件链"属 Day4 未执行 | 本候选真实执行 |
| AT-SRC-006 | BLOCKED | 无获认可免费公开来源（EXT-10=OPEN_EXTERNAL_NON_BLOCKING），testcase 无法合法完整执行；不等于任务/阶段 FAIL | D3-T03 调查证据保持 |
| AT-SRC-007 | PASS | Day3 部分：受理→raw→RECEIVED+PENDING→PARSED+PENDING/来源/operator/版本/门禁；Day4 部分（validation→VERIFIED→PUBLISHED→daily→aggregate）未执行 | 本候选真实执行 |
| AT-SRC-008 | PASS | Day3 部分：身份不可冒充、Synthetic 显式隔离、已实现出口一致；Dashboard/预警/Agent/EvidencePack 跨出口全量一致性未执行 | 本候选真实执行 |

- 已清理 `DAY3_PARTIAL_PASS` 与 `REUSED_VERIFIED_EVIDENCE` 作为当前状态 token 的残留（docs/04 状态行、docs/05 当前状态表、docs/evidence/D3-T06/、新证据）；旧 Day3 Final Acceptance Evidence（ab28a6c）作为历史审计保留并标注 FAILED_STAGE_CANDIDATE，其内部历史状态表达未改写。
- 未修改 docs/03 冻结定义；未修改生产代码/业务 Decision；未改变任何真实执行范围（未夸大为完整验收）。

## 8. 剩余阶段状态

- Day3 Development Tasks = ALL_DONE（D3-T01～T06 全部 DONE）。
- FAILED_STAGE_CANDIDATE `ab28a6c` 记录 = PRESERVED（docs/05 当前表 + 旧 Evidence 历史标注）。
- Stage Findings = `FIXED_PENDING_REACCEPTANCE`。
- Day3 Final Acceptance V2 = `PENDING`（本轮不执行、不形成新 Stage Candidate）。
- Day3 Stage Review（V2）= `PENDING`。
- Day 3 = `NOT_COMPLETE`（未执行 Stage Gate；未开始 Day4；未 merge main）。

## 9. 结论

- Combined integration verification = **PASS**（46 classes / 247 tests / 0 failures / 0 errors / 7 skipped，真实执行）。
- Sol Finding 映射：F1～F5 及 Storage/Determinism/Completeness/命名空间项全部 RESOLVED（技术项经第二方 Review；非技术项经本 Evidence 复核）。
- 本 commit（`test: integrate Day3 stage finding fixes`）形成 **DAY3_STAGE_FIX_INTEGRATED_BASE**（非 Stage Candidate V2）。
