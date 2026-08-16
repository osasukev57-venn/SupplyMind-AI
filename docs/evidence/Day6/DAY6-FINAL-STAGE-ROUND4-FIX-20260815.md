# Day 6 Final Stage — Round 4 Final Narrow Fix（2026-08-15）— M2 Row-to-Ref / M3 Full-Field Claim Guards

> 性质：D6 Stage Review=`CHANGES_REQUESTED` 后的第四轮最终窄修（FINAL NARROW FIX）。
> Base：`75e9dc2`；独立攻击：`8dc0c07`（20 targeted）在本分支直接运行 20/20 PASS。
> 框架不变：Java 17 / Spring Boot 3.5.15 / Spring AI 1.1.8（DEC-060 未改）。未开始 Day7、未 merge main。

## M2 — Row-to-EvidenceRef 与 configVersions（PASS）

- **行级 evidenceRefs**：HistoryQueryToolAdapter 每行携带该行 inputRefs 的 rawRefs（`evidenceRefs` 行字段），
  ToolResult.evidenceRefs 仍是工具级并集；`buildFacts` 每 Fact 只使用该行的 refs（经 VERIFIED +
  mode-allowed 过滤）——raw-A 永不支持只来自 raw-B 的事实（无行级 refs 的旧工具回退工具级 refs）。
- **行级 sourceFingerprint**：每行按自己的 providerType + actualSourceName + accessMethod 计算
  （`fingerprintOf(row)`），禁用第一行 fingerprint 填充其他行。
- **ref tombstone**：同一 ref 出现异构 lineage → 永久 tombstone（`tombstonedRefs`）——A→B→A 顺序
  永不把该 ref 加回 perRef map；文件级 `AMBIGUOUS_FILE_LINEAGE` 兜底。
- **configVersions 是有序列表**：每行规范化（去重、数值升序）；所有行**完整列表相等** → VERIFIED 且
  EvidenceRefEntry 保留完整列表；[1,2] vs [1,3] → `UNAVAILABLE/AMBIGUOUS_FILE_LINEAGE`；
  合法 [1,2]（含乱序 [2,1]）不误判（EvidenceRefVerifier 与 adapter 双处统一规范化）。

## M3 — Claim 全字段校验（PASS）

- **Secret 全字段扫描**：`secretInAnyPersistableField` 扫描 answer + claimId + text + factIds +
  evidenceRefs + sourceNames + businessDates——任一命中 → `MODEL_RESPONSE_REJECTED:SECRET_INJECTION`
  → JAVA_TEMPLATE，secret 零落盘。
- **位置感知数值/日期**：`extractBusinessNumbers` 只提取**不落在 ISO 日期 token span 内**的数字
  （日期自身的 2026/08/10 片段不是业务数值）；独立业务数值 20 即使同 claim 含 2026-08-10 也必须
  由引用 Fact 的 **value** 支持（旧的 `date.contains(number)` 豁免已删除）。
- **来源声明闭合**：`claimSourceDeclarationsClosed`——claim.text 中出现的已知来源名必须同时声明于
  sourceNames[]；`source|来源` 声明命名的任何未知来源（无法可靠验证）fail closed；sourceNames[]
  留空不允许 text 写入虚假来源；sourceNames 每项仍须被该 claim 引用 Fact 支持（既有校验保留）。

## Round4 新增攻击测试（12/12 PASS）

- `Day6FinalStageRowToRefConfigTest`（6）：两行来自 raw-A/raw-B、值 A/B——claim 声明值 B 只引用
  raw-A → 拒绝；每 Fact 的 evidenceRefs 精确等于该行 refs；同一 ref lineage A→B→A → 始终 tombstone
  且文件级 AMBIGUOUS；不同来源行 fingerprint 各自正确；两行 configVersions 均 [1,2]（含 [2,1] 乱序）
  → VERIFIED 且保留 ["1","2"]；[1,2] vs [1,3] → AMBIGUOUS_FILE_LINEAGE。
- `Day6FinalStageClaimFieldGuardAttackTest`（6）：answer 安全、claim.text 含 secret → JAVA_TEMPLATE
  且零落盘；sourceNames/businessDates 含 secret → JAVA_TEMPLATE；“值为20，日期2026-08-10”引用值非 20
  但日期正确的 Fact → 拒绝；日期自身 2026/08/10 token 不作业务数值（日期+引用值 → PASS）；claim.text
  声明 fabricated source、sourceNames=[] → 拒绝；claim.text/sourceNames 均为真实引用来源 → PASS。

## Preservation

- M1 ChatClient ToolCallback → ToolResult → EvidencePack 正式链；四态 EvidencePack；LLM context 独立过滤；
  M4 refType/evidenceRefId/RAW/LIFECYCLE/sha/lineage 完整绑定 + synchronized manifest attack fail closed；
  M5 用户输入 secret/mode guard——全部保持（定向 + 全量回归验证）。
- Tool Count=7；Write Tool=NONE；Cloud LLM=PENDING_EXTERNAL；DEC-060 未改。
- 8dc0c07 独立攻击套件 20/20 原样运行（无断言改动）。

## Regression（真实执行 `.\mvnw.cmd clean test`）

| 指标 | 结果 |
|---|---|
| classes | 105（103 + 2 新增） |
| tests | 534（522 + 12 新增） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与基线逐项相同，无新增无理由 skip） |
| 8dc0c07 targeted | 20/20 PASS（原样） |

## 状态

- Day6 = REVIEW_PENDING（不得 COMPLETE）；待 Final Delta Review。
