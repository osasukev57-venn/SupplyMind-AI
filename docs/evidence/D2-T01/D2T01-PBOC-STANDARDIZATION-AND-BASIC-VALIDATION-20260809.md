# D2-T01 PBOC 标准化与基础校验实施记录

> 证据性质：D2-T01 任务级标准化/校验实现、测试与真实 raw 证据记录。
> 不是 AT-SRC-002、Day 1 或 Day 2 总门禁的 PASS 证据；AT-SRC-002 仍为 `NOT_RUN`。
> D2-T01 最终状态按冻结规则由正式 Review / Gate 裁决。

## 执行范围

- 执行时间：2026-08-09（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`
- 输入：D1-T05 真实双币 raw（复制自 `backend/data/d1-t05-smoke`，逐字节一致）；冻结 Series 定义（formal configVersion 1）与 D2-T01 校验规则
- 未使用真实网络：D2-T01 不要求新采集，全部输入来自已落盘真实 raw 与合成 fixture

## 实现内容（最小范围，未改动 Day 1 基础）

| 组件 | 职责 |
|---|---|
| `foundation/storage/TimelineStore.java`（新增） | LifecycleTimelineV1 初始创建与原子追加（staging data+manifest 单文件事务，immutableData=false）；幂等重放不追加相同状态快照 |
| `validation/ActiveConfigReader.java` | manifest 校验后读取活动 monitor-series 配置 |
| `validation/PbocCandidateStandardizer.java` | raw → 统一 CandidateV1（`normalizationVersion=pboc-standardization-v1`）；字段缺失或 rawValue 非十进制文本 → `STANDARDIZATION_FAILED`（可解析的 0/负值构造候选，由范围校验判 `OUT_OF_RANGE`） |
| `validation/PbocBasicValidator.java` | 候选基础校验（`validationVersion=pboc-basic-validation-v1`），见规则表 |
| `validation/LifecycleValidationService.java` | 编排：RECEIVED+PENDING → PARSED+PENDING → VALIDATED（或解析失败 RECEIVED+REJECTED）；重复处理幂等、终态 no-op |
| `validation/ValidationOutcome/ValidationVerdict/StandardizationResult/ValidationReasonCodes` | 结果与原因码载体 |

生产代码仅新增上述最小组件；未修改 storage foundation、PBOC transport、HTML parser、atomic file、recovery、manifest、raw 不可变契约、冻结计划与数据字典。未创建 `data/normalized`、`data/published` 或任何新物理目录；未生成 quarantine 投影（终态隔离属 D2-T02 契约职责）。

## 校验规则（DEC-050 正式冻结口径，`validationVersion=pboc-basic-validation-v1`）

校验顺序确定、首错即停（fail-closed）：

| 顺序 | 规则 | 结论 |
|---|---|---|
| 1 | 来源：itemId/providerType/accessMethod/actualSourceName 与配置一致、mode 与配置一致 | 否则 `REJECTED/SOURCE_MISMATCH` |
| 2 | 字段完整性：httpStatus=200、contentType=text/html、sourcePublishedAt 非空、payload SHA-256 重算一致 | 否则 `REJECTED/FIELD_INVALID` |
| 3 | 单位：candidate.unit == item.unit | 否则 `REJECTED/UNIT_MISMATCH` |
| 4 | 币种：candidate.currency == item.currency | 否则 `REJECTED/CURRENCY_MISMATCH` |
| 5 | 日期：businessDate 不得晚于校验日（Asia/Shanghai） | 否则 `REJECTED/FUTURE_BUSINESS_DATE` |
| 6 | 时效：businessDate 距校验日超过 30 个自然日 → stale；等于 30 日仍有效（`staleThresholdDays=30`，DEC-050 正式批准） | 否则 `REJECTED/STALE_BUSINESS_DATE` |
| 7 | 范围：`(0,100]`，下界开放上界闭合；0 与负值不允许，100 允许（DEC-050 正式批准） | 否则 `REJECTED/OUT_OF_RANGE` |
| 8 | 重复/冲突：同 itemId+businessDate+来源 的其他 run 候选 | 值不同 → `CONFLICT/VALUE_CONFLICT`；值相同 → `VERIFIED_WITH_NOTICE/DUPLICATE_OBSERVATION` |
| 9 | 全部通过 | `VERIFIED` |

解析（标准化）失败：raw 缺 sourceBusinessDate/sourcePublishedAt/rawValue/rawUnit/rawCurrency 或 rawValue 非十进制文本 → `RECEIVED+REJECTED/STANDARDIZATION_FAILED`，candidate 保持 null。可解析的 0/负值不属于标准化失败：先经 `PARSED+PENDING` 构造候选，再由范围校验按 DEC-050 判 `VALIDATED+REJECTED/OUT_OF_RANGE`。

## 核心执行链（实际实现数据流）

```
staging/<runId>.json（RECEIVED+PENDING，manifest 校验读取）
  → raw/manifest 校验读取 RawReceiptV1
  → 活动配置（manifest 校验）取 item
  → PbocCandidateStandardizer
      ├─ 失败 → 追加 RECEIVED+REJECTED（candidate=null, reasonCode）
      └─ 成功 → 追加 PARSED+PENDING（不可变 CandidateV1）
  → PbocBasicValidator（含对其它 staging 候选的同键同源扫描）
  → 追加 VALIDATED+VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT
      （validationVersion=pboc-basic-validation-v1、validatedAt、按冻结矩阵 reasonCode）
```

状态机完全复用冻结模型：`LifecycleTimelineV1.append` 强制执行合法迁移（RECEIVED→PARSED 或 RECEIVED→REJECTED、PARSED→四种 VALIDATED），跳级（RECEIVED→VALIDATED、PARSED→PUBLISHED）在模型层 fail-closed；CandidateV1 同 run 逐字段不可变由模型强制。

## 测试结果

### 合成确定性矩阵（`PbocValidationPipelineTest`，14 tests，0 failures，0 errors）

| 用例 | 结果 |
|---|---|
| 正常双币（USD 6.7904 / EUR 7.8067）→ VALIDATED+VERIFIED，3 快照，CandidateV1 跨快照逐字段一致，重启重读一致 | PASS（含 golden bytes） |
| 缺 sourceBusinessDate → RECEIVED+REJECTED/STANDARDIZATION_FAILED，candidate=null | PASS（含 golden bytes） |
| 单位错误（CNY/100 EUR）→ VALIDATED+REJECTED/UNIT_MISMATCH | PASS（含 golden bytes） |
| 未来日期 → REJECTED/FUTURE_BUSINESS_DATE | PASS |
| 过期日期（2026-06-01）→ REJECTED/STALE_BUSINESS_DATE | PASS |
| 异常范围（500.0）→ REJECTED/OUT_OF_RANGE | PASS |
| 来源不匹配 → REJECTED/SOURCE_MISMATCH | PASS |
| 币种不匹配 → REJECTED/CURRENCY_MISMATCH | PASS |
| 字段完整性（contentType 非 html）→ REJECTED/FIELD_INVALID | PASS |
| 重复观测（同键同值）→ VERIFIED_WITH_NOTICE/DUPLICATE_OBSERVATION，首条逐字节未动 | PASS |
| 值冲突（同键异值）→ VALIDATED+CONFLICT/VALUE_CONFLICT，合法值逐字节未动 | PASS |
| 重复处理幂等：重复 process 结果一致、staging 字节不变、不追加重复快照 | PASS |
| 从既有 PARSED 快照恢复 → VALIDATED+VERIFIED | PASS |
| RECEIVED→VALIDATED、PARSED→PUBLISHED 跳级被拒绝（模型 + 非法 golden 解码） | PASS |

### Golden bytes（`backend/src/test/resources/contracts/v1/`）

- `valid/lifecycle-validated-verified-pboc-v1.json`、`valid/lifecycle-validated-rejected-unit-mismatch-pboc-v1.json`、`valid/lifecycle-received-rejected-standardization-pboc-v1.json`：管线持久化字节与手工黄金字节逐字节一致（fixed bytes，LF，无 BOM）。
- `invalid/lifecycle-received-validated-skip.json`、`invalid/lifecycle-parsed-published-skip.json`：解码即拒绝（SchemaValidationException）。

### 真实 D1-T05 raw 证据（`PbocValidationRealRawEvidenceTest`，gated `-Dd2-t01.real-raw=true`）

输入复制自 `backend/data/d1-t05-smoke`（config/raw/staging 连同 manifest），断言复制逐字节一致、真实页面 SHA-256=`f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82`、businessDate=2026-08-07：

| runId | itemId | 值 | 结果 |
|---|---|---|---|
| `pboc-usd-20260807-f37cda1f…4f82` | FX.USD.CNY.PBOC_MID | 6.7904 | PARSED+PENDING → `VALIDATED+VERIFIED`（recordVersion=3，pboc-basic-validation-v1） |
| `pboc-eur-20260807-f37cda1f…4f82` | FX.EUR.CNY.PBOC_MID | 7.8067 | PARSED+PENDING → `VALIDATED+VERIFIED`（recordVersion=3，pboc-basic-validation-v1） |

验证后 raw 文件逐字节不变（校验前/后 SHA-256 一致）；新实例重读 timeline 状态一致（restart-read PASS）。机器可读证据：`docs/evidence/D2-T01/d2-t01-real-raw-validation-summary.json`。

### 最小直接回归（Java 17）

`PbocValidationPipelineTest`(14)、`PbocValidationRealRawEvidenceTest`(1，无门禁属性时 skip)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)、`PbocOfficialWebDataProviderContractTest`(6)：共 31 tests，0 failures，0 errors（1 skipped）。

## 校验报告说明

D2-T01 的校验结论持久化于 Lifecycle timeline 快照本身：`validationVersion=pboc-basic-validation-v1`、`validatedAt`、按冻结矩阵的 `reasonCode`，以及每次失败的标准化原因码；终态失败的 quarantine 证据投影属 D2-T02 契约职责，本任务未生成。任务级校验报告即本文档与 `d2-t01-real-raw-validation-summary.json`。冻结目录树中不存在独立的 validation-report 物理目录，未新增任何竞争性目录或格式。

## D2-T01 DoD 逐项

| DoD 条款 | 状态 | 证据 |
|---|---|---|
| 同输入结果确定（确定性、可复算） | PASS | fixed clock 合成矩阵 + golden bytes 逐字节一致；幂等重放不追加重复快照 |
| PARSED 及以后每条快照持久化 CandidateV1 | PASS | 全部 golden/合成/真实用例 3 快照中快照 2、3 均含 CandidateV1，且逐字段相同 |
| VALIDATED 记录有 ProcessingStage、ValidationStatus、规则版本、按状态要求的原因码/时间 | PASS | validationVersion=pboc-basic-validation-v1、validatedAt 非空；REJECTED/CONFLICT/VERIFIED_WITH_NOTICE 有 reasonCode，VERIFIED 为 null（符合冻结条件必填矩阵） |
| 无效数据不覆盖合法值 | PASS | 值冲突/重复/异常用例中断言首条合法 timeline 逐字节不变 |

## 状态与边界

- 数据生命周期：真实双币已到 `VALIDATED+VERIFIED`；未进入 PUBLISHED、daily、aggregate、warning、dashboard、Agent；未生成 quarantine（属 D2-T02）。
- 本任务不涉及发布门禁（D2-T02）、每日加工（D2-T03）与调度（D2-T05）。
- 未修改 AT-SRC-002（保持 `NOT_RUN`）。

## 正式业务参数（DEC-050 批准，2026-08-09）

`staleThresholdDays=30` 个自然日（businessDate 距校验日期超过 30 日 → stale，等于 30 日仍有效）、合法数值区间 `(0,100]`（0 与负值不允许，100 允许）、`validationVersion=pboc-basic-validation-v1` 已由技术负责人正式批准（APPROVED）并登记为 `docs/06-DECISION-LOG.md` 的 **DEC-050**，作为 D2-T01 PBOC USD/CNY、EUR/CNY 基础校验的正式业务口径；适用范围仅限 D2-T01 当前双币基础校验，不得扩展为材料价格规则、所有未来 Provider 通用规则、warning 阈值或其他业务口径。历史已生成 validation 结果继续由其持久化的 validationVersion 保持可追溯，未来规则变化不得静默改写旧结果。

审计链：原实现以版本化默认值（30 天、(0,100]）实施并明确标注待裁决 → Review Finding 4 判定不得自行决定业务参数并上报 `CHANGE_REQUEST_REQUIRED` → 技术负责人正式批准 → 本记录与 DEC-050 正式冻结。此前"版本化实现默认/等待确认"的表述由此正式口径取代。

## Review Fix 记录（CHANGES_REQUESTED 后修复，2026-08-09）

| Finding | 结论 | 修复内容 |
|---|---|---|
| 1（BLOCKER）重复/冲突基准 | FIXED | 历史扫描只纳入当前快照为 VERIFIED 类的 run（`VALIDATED+VERIFIED`、`VALIDATED+VERIFIED_WITH_NOTICE`、两种 PUBLISHED 组合）作为合法历史基准；`PARSED+PENDING`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT` 等不可作为基准。依据：DEC-012/C16"合法历史值"、AT-PUB-003"不覆盖已发布/合法值"、D2-T01 DoD"无效数据不覆盖合法值"。新增反例测试：REJECTED 历史不使新合法记录判 conflict、CONFLICT 历史不污染同值新记录、PARSED+PENDING 历史不参与判断。不修改任何既有历史 timeline。 |
| 2（MAJOR）configVersion 追溯 | FIXED | 校验改由 `VersionedConfigReader.readVersion(dataRoot, raw.configVersion())` 精确读取对应不可变 `config/history/<configVersion>.json`（manifest 校验 + 版本一致校验），不再读取当前 active config。新增测试：raw.configVersion=1 在 active 切换到 V2（USD 单位变更）后重新校验结果与 V1 时一致、新 configVersion=1 raw 仍按 V1 判 VERIFIED（若误用 active 将 UNIT_MISMATCH）、configVersion=2 raw 按 V2 判 VERIFIED。 |
| 3（MAJOR）0/负数归属 | FIXED | `PbocCandidateStandardizer` 不再把可解析的 0/负数判为标准化失败；只要 decimal text 可被合法解析即构造 CandidateV1 进入 `PARSED+PENDING`，由 `PbocBasicValidator` 范围检查判 `VALIDATED+REJECTED/OUT_OF_RANGE`。仅字段结构/格式不可标准化（缺字段、非十进制文本）才 `RECEIVED+REJECTED/STANDARDIZATION_FAILED` 且 candidate=null。新增测试：`rawValue=0` 与 `rawValue=-1.5` 均经 PARSED（3 快照、candidate 非空）→ OUT_OF_RANGE；`rawValue=abc` → RECEIVED+REJECTED（2 快照、candidate=null）。 |
| 4（BUSINESS DECISION）stale/range 阈值 | CLOSED（DEC-050） | 检查冻结 docs 后确认当时不存在已冻结或新裁决的阈值，上报 `CHANGE_REQUEST_REQUIRED` 并等待裁决。技术负责人随后正式批准：`staleThresholdDays=30`、`(0,100]`（0/负不允许、100允许）、`validationVersion=pboc-basic-validation-v1`，登记为 DEC-050；实现参数与裁决逐字一致，生产代码无需修改。 |

### Review Fix 后测试结果

- `PbocValidationPipelineTest`：25 tests，0 failures，0 errors（原 14 项 + Finding 用例 + DEC-050 边界用例全部通过）。
- DEC-050 参数边界（固定时钟 today=2026-08-10）：日期差=30（2026-07-11）→ 非 stale VERIFIED；日期差=31（2026-07-10）→ STALE_BUSINESS_DATE；value=0 与负值 → 先 PARSED+PENDING 后 `VALIDATED+REJECTED/OUT_OF_RANGE`；value=100 → VERIFIED 合法；value=101/500 → `VALIDATED+REJECTED/OUT_OF_RANGE`。
- 真实 raw 门禁 `PbocValidationRealRawEvidenceTest`（gated）重跑 PASS：双币经 `config/history/1.json` 读取配置仍 `VALIDATED+VERIFIED`（USD=6.7904、EUR=7.8067，businessDate=2026-08-07），raw 逐字节未动。
- 最小直接回归 6 类 42 tests，0 failures，0 errors，0 skipped。
