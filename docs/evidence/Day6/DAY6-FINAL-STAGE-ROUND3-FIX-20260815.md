# Day 6 Final Stage — Remaining Major Fix Round 3（2026-08-15）— M2/M3/M4

> 性质：D6 Stage Review=`CHANGES_REQUESTED` 后的第三轮生产修复证据（FINAL REMAINING MAJOR FIX ROUND 3）。
> Base：`8b1cc85`；独立攻击：`8dc0c07`（20 targeted）在本分支直接运行 20/20 PASS。
> 框架不变：Java 17 / Spring Boot 3.5.15 / Spring AI 1.1.8（DEC-060 未改）。未开始 Day7、未 merge main。

## M2 — 多行文件权威 lineage + 解码失败状态 + 生产 per-ref lineage（PASS）

- **禁止 `rows.get(0)` 代表文件**：daily/aggregate 读取全部行；冻结的文件级 lineage 字段
  （validationVersion / calculationVersion / calendarVersion / configVersions）必须全行一致，
  任一异构 → `UNAVAILABLE/AMBIGUOUS_FILE_LINEAGE`（首行永不代表文件，不静默选择）。
- **businessDate/period 是行级数据**：文件级权威来自 manifest 的 min/max businessDate
  （`businessDate=min`、`periodStart/periodEnd=min~max`）——多行多日期文件不歧义，
  版本字段异构才 fail closed（与 manifest derived-fields 校验同一权威）。
- **解码失败 fail-closed**：manifest 有效但 JSON/CSV 无法解码 → `INVALID/SCHEMA_DECODE_FAILED`
  （verifyWithAuthoritativeLineage 防御层；V1 冻结语义下 manifest derived-fields 校验
  （ManifestDerivedFieldsVerifier）会先拦截 → `INVALID/MANIFEST_MISMATCH`——两层均为明确失败码，
  永不 VERIFIED）。
- **RAW 权威 lineage**：runId / rawRef / businessDate / configVersions（RawReceiptV1.configVersion）。
- **LIFECYCLE 权威 lineage**：runId / rawRef / publishRef / businessDate / validationVersion
  （LifecycleTimelineV1.current()）。SOURCE 原始实体（raw/source|import）按非结构化处理，不解码。
- **`ToolResult.evidenceLineageByRef` 由真实生产 adapter 填充**：HistoryQueryToolAdapter 逐行
  逐 ref 填该 ref 真实 lineage；同一 ref 被异构行引用时移除（不掩盖，交由文件级 AMBIGUOUS 判定）。
- 不适用字段保持 null，不虚构。

## M3 — 严格 Structured Claims 生产合同（PASS）

- **Phase B 只接受严格 JSON envelope**（`{"answer","claims":[{claimId,text,factIds,evidenceRefs,
  sourceNames,businessDates}]}`）。普通自由文本 / malformed JSON / claims 缺失 / claims 空数组 /
  任一 claim 缺 claimId/text / 引用字段缺失或非字符串数组 / claim 无任何引用 →
  **整体** `MODEL_RESPONSE_REJECTED:MALFORMED_STRUCTURED_RESPONSE` → JAVA_TEMPLATE。
  禁止跳过坏 claim 接受剩余好 claim；`ModelDraftV1.untrusted(...)` 不再作为可接受 LLM 输出。
- **逐数字 per-claim 绑定**：`for every number in claim: referenced fact supports it`——
  全局其他 fact 永不帮助该 claim（fact-A=1 只引用 fact-A 时 "1和2" 拒绝；"值 B 引 fact-A" 拒绝）。
- **百分数只与百分比 Fact 匹配**；日期由引用 fact 的 businessDate/period 逐项支持
  （文本日期 token 与显式 businessDates[]）；**sourceNames[]/businessDates[] 显式 Java 验证**
  （不再依赖"文本恰好包含来源名"）。
- **AgentReport.claims 只来自完整验证通过的结构化 claims**；report explanation 由已验证
  claim.text 按序组合（绝不复用未验证 raw answer）；answer 字段仍被 secret/数字/未知引用扫描
  （answer 增伪造事实 → 拒绝）。
- Phase B prompt 明确强制 JSON 合同（SpringAiLlmService，toolCallingEnabled=false 分支）。

## M4 — 完整 Report/Evidence 字段绑定（PASS）

- `bindingMismatch` 逐项权威比对：**evidenceRefId / refType / ref / sha256 / status / reasonCode**
  （VERIFIED 的 reasonCode 也必须等于权威 null）；refType 被篡改 → `EVIDENCE_BINDING_MISMATCH`，
  永不落入宽松 default 分支——**lineage 规则按 current.refType（已证相等）选择**。
- **RAW 全绑定**：runId / rawRef / businessDate / configVersions。
- **LIFECYCLE 全绑定**：runId / rawRef / publishRef / businessDate / validationVersion。
- DAILY（businessDate/validation/calculation/calendar/configVersions）、AGGREGATE
  （periodStart/periodEnd/…）、CONFIG（configVersions）保持完整；不适用字段 null 不比较。
- 已通过的未篡改 restart read PASS 与 data+manifest 同步替换 → `EVIDENCE_BINDING_MISMATCH` 保留。

## 8dc0c07 独立攻击测试

20/20 直接运行 PASS。golden 随 RAW configVersions 权威化更新为新冻结 SHA `5f18e2b7…`。
3 处攻击断言最小适配（M3 严格合同语义演进，攻击实质与隔离断言全部保留）：

| 位置 | 原断言 | 适配后 |
|---|---|---|
| `Day6R2IndependentResponseAttackTest.fabricatedFormalNumber…` | 自由文本 → `FABRICATED_NUMBER` | 攻击 payload 移入 envelope claim（编造数字在 claim 内被逐 claim 拒绝），断言 `MODEL_RESPONSE_REJECTED:*` + 不落盘断言全保留 |
| 同文件 `injectedSecret…` | 自由文本 → `SECRET_INJECTION` | secret 移入 envelope answer（rawText 扫描仍 `SECRET_INJECTION`，断言不变） |
| 同文件 `unknownFactAndEvidenceNames…` | 自由文本 → `UNKNOWN_*` | envelope claims 携带未知 factId/evidenceRef → `UNKNOWN_*`（断言不变） |

非攻击测试诚实更新：`AgentPipelineIntegrationTest.llmSuccessPath`（Phase B 返回合法 envelope）、
`AgentR2StageFixAttackTest.a17`（secret 移入 envelope answer）、
`AgentF2MissingReferenceGuardTest.orchestratorDegrades…`（无引用 claim → MALFORMED 语义保留）。

## Round3 新增测试（23/23 PASS）

- `Day6FinalStageMultiRowLineageAttackTest`（7）：uniform 多行 VERIFIED + manifest 文件级日期、
  daily validationVersion 异构 AMBIGUOUS、aggregate calculationVersion 异构 AMBIGUOUS、
  损坏 CSV/JSON + 同步 manifest INVALID（两层失败码）、INVALID 在四态但不进 Phase B LLMRequest、
  真实 HistoryQuery adapter 填 per-ref lineage。
- `Day6FinalStageStrictEnvelopeAttackTest`（11）：自由文本/malformed/缺 claims/混合好坏 claim
  全部 MALFORMED；两值一 fact 拒绝；两值两 fact（或同值双引用）通过；answer 对 claim 错拒绝；
  claim 对 answer 伪造拒绝且不落盘；未知 sourceName 拒绝；未引用 businessDate 拒绝；
  单元级全局 fact 不得帮助未引用 claim。
- `Day6FinalStageFullBindingAttackTest`（5）：refType/evidenceRefId/VERIFIED reasonCode 篡改 →
  `EVIDENCE_BINDING_MISMATCH`；RAW businessDate/configVersions、LIFECYCLE publishRef/businessDate
  篡改 → `EVIDENCE_LINEAGE_MISMATCH`；每项攻击前先证明未篡改 PASS；每项唯一 failureCode。

## Regression（真实执行 `.\mvnw.cmd clean test`）

| 指标 | 结果 |
|---|---|
| classes | 103（100 + 3 新增） |
| tests | 522（499 + 23 新增） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与基线逐项相同，无新增无理由 skip） |
| 8dc0c07 targeted | 20/20 PASS |

- 保护项未回归：Tool Count=7、Write Tool=NONE、M1 ChatClient 生命周期、M2 四态/LLM 上下文隔离/
  ref 闭合、M5 secret/未知 mode、Day1-Day5 业务合同、FORMAL/DEMO 隔离、golden 契约（新冻结 SHA）。
- DEC-060 未改；Day6 状态 REVIEW_PENDING（不得 COMPLETE）。

## 状态

- Day6 Final Acceptance=implementation-side PASS（Round 3）；8dc0c07 20/20；待 Final Delta Review。
