# Day 6 Final Stage — Remaining Major Fix（2026-08-15）— M2/M3/M4 + Independent Attack Integration

> 性质：D6 Stage Review=`CHANGES_REQUESTED` 后的最终阶段生产修复证据（FINAL STAGE）。
> Base：`80cc22c`（FINAL STAGE 前一个 major fix commit）；独立攻击：`8dc0c07`（INDEPENDENT ATTACK，20 targeted tests）在本分支**直接运行** 20/20 PASS。
> 框架不变：Java 17 / Spring Boot 3.5.15 / Spring AI 1.1.8（DEC-060）。未改 DEC-060、未升级框架、未引入 RAG/Vector/MCP/Database、未开始 Day7。

## M2 — 四态结构化 EvidencePack + 逐 evidenceRef lineage + ToolExecution 闭合（PASS）

- `EvidencePackV1.evidenceRefs` 保留**全四态**（VERIFIED/MISSING/INVALID/UNAVAILABLE），每个非 VERIFIED entry 携带自己的 reasonCode——不再是"只放可用 ref"的视图；LLM 上下文是独立的过滤视图（VERIFIED + 模式允许 + lineage 完整），两条通道分离。
- lineage 逐 evidenceRef：`ToolResult.evidenceLineageByRef`（M2 新增字段，10 参 record）优先，工具级 lineage 仅为该工具的默认值——永不跨工具批量套用（m2PerToolLineageIsNotBatchAppliedAcrossTools 保持 PASS）。
- 每个 `ToolExecution.evidenceRefs` 必须存在于**最终** EvidencePack.evidenceRefs（先组四态全表，再对闭合做校验），缺失即 `IllegalStateException` fail-closed。

## M3 — 生产路径 structured claims + 全数字形态防护（PASS）

- Phase B 生产路径**优先解析严格 JSON claims envelope**（`{"answer","claims":[{claimId,text,factIds,evidenceRefs}]}`），逐 claim 验证：claim 的 factIds/evidenceRefs 必须存在且支持 claim 自身数字（值 B 引用 fact-A 永远不通过，无关引用不验证整篇）；模型输出自由文本时回退整篇验证（编造数字/未知引用同样拒绝）。仅验证通过的结构化 claims 进入持久化 AgentReport.claims（M3 §12）。
- `NUMBER_TOKEN` 完整 token 边界修复：科学计数法分支置首，`7.15e6` 是单 token（修复前被截断为 `7.15`+游离 `e6`，编造数字可漏过）；千分位要求至少一组分隔符。
- 数值规范化全程 `BigDecimal.stripTrailingZeros().toPlainString()`（无 float/double 中间量）：`7.15000000`≡`7.15`、`1.5e3`≡`1500`。
- 百分数**不默认等价**：`%` 是 token 的一部分，`7.15%` 只匹配 fact 值本身为百分比的事实（单位感知）；`7.15%` 对 unit=`CNY/1 USD` 的 `7.15` 事实 → 拒绝。
- structured claim 中的日期/period/来源声明必须由该 claim 引用的 Fact 支持（businessDate/period 覆盖 + actualSourceName 匹配），否则 `UNSUPPORTED_CLAIM_REFERENCE`。

## M4 — ReportStore.read 权威 lineage + 完整字段绑定（PASS）

- `EvidenceRefVerifier.verifyWithAuthoritativeLineage(ref)`：VERIFIED 时**解码真实文件**恢复权威 lineage——DAILY→`DailyRecordV1`（businessDate/validationVersion/calculationVersion/calendarVersion/configVersions）、AGGREGATE→`AggregateRecordV1`（periodStart/periodEnd/…）、RAW→`RawReceiptV1`（runId/rawRef/businessDate）、LIFECYCLE→`LifecycleTimelineV1`（runId/rawRef/validationVersion）、CONFIG→`MonitorSeriesConfigV1`（configVersions）。
- 写路径 `verifyMerged(ref, toolLineage)`：真实文件是每个可解码字段的权威，ToolResult lineage 只填充文件无法承载的字段（RAW 无 validation/calculation/calendar/config 版本）——永不反向覆盖。
- `ReportStore.read` 用纯文件权威 lineage 重新验证，`bindingMismatch` **按 refType 只比较适用字段**（RAW 不比 validationVersion 等不适用字段）；任何 sha256/status/reasonCode/适用 lineage 漂移 → fail-closed（`EVIDENCE_BINDING_MISMATCH`/`EVIDENCE_LINEAGE_MISMATCH`）。
- 攻击 fixture 使用**真实 manifest.fileSha256**（aggregate inputRefs 携带 daily manifest 的真实 SHA，见 `Day6FinalStageAuthorityBindingAttackTest.aggregateAttackFixtureCarriesTheRealDailyManifestFileSha256`）；篡改测试**先证明未篡改 PASS** 再攻击（untamperedReportPassesBeforeAnyTamperIsProven）。
- 新增攻击：内容篡改 + **同步重写 manifest**（攻击者连 manifest 一起改）仍被冻结 sha256 绑定捕获 → `EVIDENCE_BINDING_MISMATCH`。

## 8dc0c07 独立攻击测试正式纳入（20/20 在本分支直接运行 PASS）

8dc0c07（INDEPENDENT ATTACK，20 targeted tests）的测试文件已复制进工作区并正式纳入本分支回归集，**全部 20/20 PASS**（`mvn test -Dtest=Day6R2*`）。Golden evidence JSON 随 M4 权威 lineage 更新为新 SHA（`0e66319d…`，RAW entry 现携带 runId/rawRef/businessDate）。

最小适配（4 处，全部为 M2 四态语义演进的诚实收紧，非放宽；攻击实质——LLM 上下文隔离/失败关闭——全部保留）：

| 位置 | 原断言 | 适配后（理由） |
|---|---|---|
| `Day6R2IndependentIsolationAttackTest` | FORMAL 下 `evidenceRefs()` 不含 warning | warning 保留在四态审计轨迹，但 facts 与 LLM 上下文不含 warning（新增 facts 级隔离断言，更强） |
| `Day6R2IndependentEvidenceReportAttackTest.missingInvalidAndUnsafe…` | INVALID raw 不在 `evidenceRefs()` | INVALID 以 `status==INVALID` 保留在四态轨迹 + LLM request 不含 + facts 空（LLM 隔离断言原样保留） |
| 同文件 `formalFactsCarryEveryRequiredNonPlaceholderLineageField` | 每个 entry 的 validationVersion 等全部非空 | 按 refType 断言适用字段（RAW=runId/rawRef，CONFIG=configVersions，DAILY/AGGREGATE=全字段）——真实文件是权威 |
| `Day6R2Fixture.verifiedEvidencePack` | `verify(rawRef)`（无 lineage） | `verifyWithAuthoritativeLineage(rawRef)`（权威绑定，restart read 不再假阳性） |

## 新增测试（DAY6 FINAL STAGE，10/10 PASS）

- `Day6FinalStageStructuredClaimsAttackTest`（7）：结构化 envelope 接受并持久化为 report claims；claim 内编造数字拒绝；跨 fact 值交换（值 B 引 fact-A）拒绝；未知 fact 拒绝；百分数不等价；claim 日期必须被引用 fact 支持；自由文本编造数字仍拒绝。
- `Day6FinalStageAuthorityBindingAttackTest`（3）：未篡改先 PASS（两次独立 read）；内容篡改+同步 manifest → `EVIDENCE_BINDING_MISMATCH`；fixture 用真实 manifest.fileSha256。

## Regression（真实执行，`mvn test`，Java 17 / Boot 3.5.15 / Spring AI 1.1.8）

| 指标 | 结果 |
|---|---|
| tests | 499（489 + 10 新增） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与 Day5/Day6 基线逐项相同，未新增） |
| 8dc0c07 targeted | **20/20 PASS**（本分支直接运行） |

- 保护项未回归：Secret Injection、Exactly 7 Request Tools、Invalid Evidence 隔离、FORMAL/DEMO Isolation、Evidence Golden JSON（新冻结 SHA）、Evidence Tamper、Report Restart Read/Body/Manifest Tamper/Identity/Month/Evidence Reverify、Cost Production Reuse、Fallback Deterministic Only、M3 数字防伪全部形态。
- No Secret / No Database：与前期一致（环境变量注入，无数据库栈）。

## 状态

- Day6 Final Acceptance=implementation-side PASS；8dc0c07 独立攻击测试正式纳入（20/20 直接运行）；Day6 Stage Review 待最终 independent attack review。
