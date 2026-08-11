# Day 3 Final Acceptance —— Source / Provider / Material Intake Integration（2026-08-11）

> 性质：Day 3 阶段级 Acceptance Execution（非任务 Review、非 Stage Review）。
> Candidate base：`integration/day3 @ ba9d706`（working tree clean，Acceptance 前固定）。
> 执行方式：全量 Maven regression 真实执行（Java 17.0.19，`mvnw test`）+ 既有阶段 Acceptance 测试（`MaterialDay3AcceptanceTest` 等）逐项核对；冻结依据：docs/01 §15 Day 3 行与 Day 3 Gate（DEC-057 边界）、docs/03 §8 Day 3 退出条件、docs/02 §9 正式验收门禁、DEC-037/050/056/057。
> 本 Evidence 不写 "Day 3 COMPLETE"：Stage Review（Sol + 第二方）尚未执行。

## 1. Candidate base 与任务状态

- branch=`integration/day3`，HEAD=`ba9d706`；Acceptance 执行前后 working tree clean。
- D3-T01=DONE、D3-T02=DONE、D3-T03=DONE、D3-T04=DONE、D3-T05=DONE、D3-T06=DONE；Day3 Development Tasks=COMPLETE。
- Day3 Acceptance=PASS（本轮）；Day3 Stage Review=PENDING；Day3=NOT_COMPLETE。

## 2. Regression（本轮真实执行）

| 指标 | 结果 |
|---|---|
| classes | 44 |
| tests | 242 |
| failures | 0 |
| errors | 0 |
| skipped | 7（均为按设计门禁跳过：AtSrc002AcceptanceTest/AggregateRealRawEvidenceTest/DailyRealRawEvidenceTest/PbocOfficialWebRealNetworkAttemptTest/PbocRawClosedLoopSmokeGateTest(真实网络分支)/PublishRealRawEvidenceTest/PbocValidationRealRawEvidenceTest——离线环境按属性跳过；确定性断网不造数分支实际运行） |

关键类真实结果（0 failures/errors）：`MaterialDay3AcceptanceTest`(4)、`LocalImportIsolationTest`(18)、`ManualMaterialIntakeTest`(13)、`DataProviderRegistryTest`(8)、`FreePublicSourceSurveyTest`(7)、`PbocOfficialWebDataProviderContractTest`(7)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`PublishGateTest`(9)、`DailyProcessingServiceTest`(19)、`AggregateCalculatorTest`(15)、`PbocValidationPipelineTest`(25)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)、`CsvV1CodecTest`(2)。

## 3. D3-T01 Provider 体系

- 六类 Provider 边界真实存在且经注册表唯一发现：OfficialWeb（`pboc-official-web`）、AuthorizedApi（`smm-authorized-api`、`am-authorized-api`）、FreePublic（当前无获认可实例）、Manual（`manual-material`）、LocalImport（`local-import`）、SyntheticDemo（`synthetic-demo`）。
- `DataProviderRegistry`：providerId 唯一、providerType/accessMethod 明确、无平行 framework；`DataProviderRegistryTest` 8/8 PASS。

## 4. D3-T02 材料路由（DEC-037）

- PRIMARY→FREE_PUBLIC→MANUAL 三层顺序真实运行；routeDecision/fallbackReason/生效时间经 `MaterialRouteDecision` 可审计。
- 无 silent fallback；Synthetic 恒非正式候选；未配置 source 不假装成功（AuthorizedApi 经能力探针如实记录 `credentials_missing`=NOT_CONFIGURED，未访问/未绕过）。

## 5. D3-T03 FreePublic 结论

- `NO_APPROVED_SOURCE` 保持（SMM/CCMN/100ppi=NOT_APPROVED，Asian Metal=UNVERIFIED；`docs/evidence/D3-T03/`）；SMM/Asian Metal/CCMN/100ppi 均未被测试或 fixture 冒充为 APPROVED_FREE_PUBLIC；`FreePublicSourceSurveyTest` 7/7 PASS。

## 6. 四 P0 序列 route matrix（真实测试断言，MaterialDay3AcceptanceTest）

| 序列 | selected tier | selected provider | fallbackReason | synthetic excluded |
|---|---|---|---|---|
| MAT.ADC12.SMM | fallback_manual | manual-material | smm-authorized-api=credentials_missing | 是 |
| MAT.ADC12.AM | fallback_manual | manual-material | am-authorized-api=credentials_missing | 是 |
| MAT.AZ91D.SMM | fallback_manual | manual-material | smm-authorized-api=credentials_missing | 是 |
| MAT.AZ91D.AM | fallback_manual | manual-material | am-authorized-api=credentials_missing | 是 |

- 每条序列存在合法 non-synthetic 可解释 route（PRIMARY=NOT_CONFIGURED、FREE_PUBLIC=NO_APPROVED_SOURCE → MANUAL）；不要求四序列自动真实报价（冻结允许，docs/01 Day3 Gate）。

## 7. Manual Acceptance（ADC12 / AZ91D）

- ADC12（MAT.ADC12.SMM）：Manual submission → immutable raw → RECEIVED+PENDING → `manual-material-normalization-v1` → **PARSED+PENDING**（value=19850.50 精确 decimal 原样）。
- AZ91D（MAT.AZ91D.AM）：同上 → **PARSED+PENDING**（value=24500）。
- 字段事实：operatorRef 来自认证上下文（服务端持久化，客户端不可指定）；actualSourceName、sourceReference（非空）、sourceUrl（可空）、businessDate、value、unit、currency 全链保留。
- 幂等：same key + same content = IDEMPOTENT_REUSE（复用 run/raw/timeline）；same key + different content = NEW_PENDING_VERSION（旧 raw/timeline/operator 审计永久保留）；无 CONFLICT/自动 latest/PUBLISHED（`ManualMaterialIntakeTest` 13/13 PASS）。
- DEC-057 边界：Day3 最大 PARSED+PENDING；无 VERIFIED/VERIFIED_WITH_NOTICE/PUBLISHED；无测试 bypass/手工状态升级/管理员特例（负向断言存在）。

## 8. LocalImport Acceptance（CSV / XLSX）

- CSV：ADC12（`IMP.ADC12.001`，123.456789012345678 精确 decimal）、AZ91D（`IMP.AZ91D.001`，24500）→ RECEIVED+PENDING。
- XLSX：同两标的 → RECEIVED+PENDING；XLSX Source Raw=ORIGINAL_FULL_FILE_BYTES、Item Raw=ORIGINAL_FULL_FILE_BYTES、Item Raw SHA=source 原始 SHA；item identity 来自持久化 structured business fields，不重新解析旧 XLSX first-match 猜行。
- providerType=LOCAL_IMPORT 与 actual source basis 分离。
- CSV item raw=原始逻辑记录精确字节 span 回归：quoted comma、escaped quote、quoted newline、UTF-8 multibyte、LF、CRLF 均覆盖（`crlfCsvIsValidAndRawKeepsOriginalCrlfBytes`、`quotedNewlineAndUtf8SpansMapToExactOriginalRowBytes` 等）。
- XLSX same-key multi-row：同 workbook 同 itemId 同 businessDate 不同 content 各自成为独立版本、绝不 dedupe/first-match（`sameXlsxSameKeyMultipleRowsAreSeparateVersionsAndNeverDedupe`、`xlsxRowOrderDoesNotAffectBusinessContentIdentity`）；重复导入各 row 精确复用自己的历史版本（`xlsxSameFileReimportIsIdempotentAndDifferentContentKeepsVersions`）。
- `LocalImportIsolationTest` 18/18 PASS。

## 9. SyntheticDemo

- providerType=SYNTHETIC_DEMO；Golden Scenario 确定性、fixed seed（`supplymind-demo-seed-v1`）保持。
- 不自动成为正式 route fallback（route 候选恒排除）；不进正式 PublishedQuery；不替代真实材料价格。

## 10. Formal No-Data

- PRIMARY unavailable + FREE_PUBLIC no approved source + Manual no input + LocalImport no data → 正式结果 `ROUTE_UNAVAILABLE`（NO_DATA）；无 missing→zero、无 Synthetic 填补（测试断言）。

## 11. PENDING 正式门禁

- Manual/LocalImport PENDING material：PublishedQueryService 空（BLOCKED）、daily 无行（BLOCKED）、aggregate 无文件（BLOCKED）、既有 Publish Gate=NOT_READY；无绕过 daily/Publish Gate 的 aggregate 直连入口（PublishGateTest 9/9 PASS、MaterialDay3AcceptanceTest 负向断言）。

## 12. Source Identity

- 进入方式（providerType/accessMethod）与实际依据（actualSourceName/sourceReference/sourceUrl）始终分离；Manual 声明 actualSourceName 含 "SMM" 字样不改变 providerType（恒 MANUAL/人工录入），不变成 OFFICIAL/FREE_PUBLIC（AT-SRC-008 Day3 部分断言）。

## 13. Raw / Manifest / Recovery 回归

- immutable raw、COMMITTED manifest、SHA-256/length integrity、atomic write、dirty marker、旧 RawReceipt 读取、`RawReceiptV1` nullable `declaredSourceName`/`acquisitionRef` 兼容（`RawAndConfigStoreTest`、`AtomicFileStoreWriteInvariantTest`、D3-T05 既有断言全部 PASS）；D3-T05 schema 扩展未破坏旧数据。

## 14. PBOC Regression（Day2 不回归）

- 离线全量回归中 PBOC 正式链全部 PASS：`PbocOfficialWebDataProviderContractTest`(7)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`PublishGateTest`(9)、`DailyProcessingServiceTest`(19)、`AggregateCalculatorTest`(15)、`PbocValidationPipelineTest`(25)。
- AT-SRC-002：Day3 退出条件（docs/03 §8 Day3 行）不要求重复真实联网；引用既有固定 PASS 证据（`docs/evidence/AT-SRC-002/`，DEC-056 runner 原件 SHA-256 + at-src-002-summary.json，realGateValue=true，USD=6.7884/EUR=7.8171，2026-08-10）→ **REUSED_VERIFIED_EVIDENCE**；本轮未伪造联网执行。

## 15. AT-SRC 逐项状态（与冻结 token 一致）

| Case | 状态 | 依据 |
|---|---|---|
| AT-SRC-001 | PASS | 来源合法性与三层降级决策：PBOC=OfficialWeb；SMM/AM 自动能力 NOT_CONFIGURED 不标 PASS；无绕过；routeDecision 可审计（本候选真实运行） |
| AT-SRC-002 | PASS（REUSED_VERIFIED_EVIDENCE） | Day2 已固定真实联网证据；Day3 退出条件不要求重复联网 |
| AT-SRC-005 | DAY3_PARTIAL_PASS | 四序列路由/降级/P0 判定 Day3 部分通过；已验证文件链属 Day4（DEC-057） |
| AT-SRC-006 | BLOCKED | 无获认可 FreePublic source（NO_APPROVED_SOURCE，EXT-10=OPEN_EXTERNAL_NON_BLOCKING）；testcase 无法合法完整执行；不记 PASS；Stage Non-Blocking（docs/01 Day3 Gate 不要求 AT-SRC-006 PASS；docs/02 §9 正式门禁三选一由 AT-SRC-007 路线满足，其 FULL 属 Day4） |
| AT-SRC-007 | DAY3_PARTIAL_PASS | Manual 受理/raw/RECEIVED+PENDING/PARSED+PENDING/来源/operator/版本保留/PENDING 出口不可见全部成立；Day4 部分（validation→VALIDATED→VERIFIED→PUBLISHED→daily→aggregate）未宣称 |
| AT-SRC-008 | DAY3_PARTIAL_PASS | Day3 已验证：来源身份不可冒充、Synthetic 显式隔离、故意伪标拒绝、已实现出口（raw/API/PublishedQuery/PENDING 门禁）一致；未验证：Dashboard/warning/Agent/EvidencePack 跨出口全量一致性（未实现出口）、daily 级出口对账、预期4——不得记 FULL PASS |

## 16. External Dependencies

- EXT-04（指定商业源自动能力）、EXT-10（免费材料信源及映射）、EXT-11（Manual 操作与复核责任）= `OPEN_EXTERNAL_NON_BLOCKING`（docs/02 保持；本轮未发现真实状态改变，未自行 CLOSED/PASS/BLOCKING；未新建外部依赖）。

## 17. Day3 Gate 逐条判定（docs/01 §15 Day3 行 + DEC-057 边界）

1. 四来源意图×材料序列各有合法 non-synthetic route → PASS（route matrix，§6）。
2. Manual fallback 具备可追溯 raw、PARSED+PENDING、source identity、operator、revision/version → PASS（§7）。
3. 所有 PENDING 数据被正式 Gate 拒绝 → PASS（§11）。
4. Synthetic 不能成为正式 fallback → PASS（§9）。
5. Provider/source identity 可追溯、不可冒充 → PASS（§12、AT-SRC-008 Day3 部分）。
6. Acceptance 状态准确、无伪造 PASS → PASS（§15；BLOCKED/DAY3_PARTIAL_PASS 如实记录）。
7. Day3 不要求材料 VERIFIED/PUBLISHED/daily/aggregate（DEC-057 边界）→ 满足，未误判缺失。

## 18. 结论

- Day3 Gate（允许当前阶段完成的条件）→ **PASS**。
- Day3 Acceptance → **PASS**（AT-SRC-006 BLOCKED 为 EXT-10 非阻断，不改变 Stage 结论；未改变 testcase 状态）。
- Day3 Stage Review = PENDING；Day3 = NOT_COMPLETE。
- 本轮未修改生产代码/测试逻辑；无 BLOCKER/MAJOR；未形成修复需求。
- Candidate：本 Evidence 随 `test: complete Day3 final acceptance` commit 形成 DAY3_STAGE_CANDIDATE，冻结供 Sol + 第二方 Stage Review。
