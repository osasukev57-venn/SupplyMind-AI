# D3-T06 ADC12/AZ91D 合规接入闭环 —— Day3 Acceptance（2026-08-10）

> 性质：D3-T06（ADC12/AZ91D合规接入闭环）Day-3 合规接入验收证据。
> 执行方式：全部经生产路径（统一 Provider Registry、DEC-037 三层路由解析器、Manual 受控受理（DEC-057）、LocalImport CSV/XLSX 受理、SyntheticDemo 隔离、既有 Publish/Daily/Aggregate 门禁）；Java 17.0.19；测试 `MaterialDay3AcceptanceTest`（4/4 PASS）。
> 机器结果行：`AT_SRC_001_005 fourSequences=FALLBACK_MANUAL …`、`AT_SRC_007_DAY3 manual …`、`AT_SRC_007_DAY3 localImport …`、`AT_SRC_008 …`（surefire stdout）。

## AT-SRC-001 来源合法性与三层降级决策 —— PASS（Day3 可执行部分）

- PBOC 路线=OfficialWeb（providerId=`pboc-official-web`，不依赖材料商业授权）——断言确认。
- SMM/Asian Metal 指定源自动能力：AuthorizedApi 候选 `smm-authorized-api`/`am-authorized-api` 经能力探针如实记录 `credentials_missing`（NOT_CONFIGURED），未访问、未绕过登录/会员/验证码/反爬；自动能力不被标 PASS。
- FreePublic：D3-T03 冻结结论 `NO_APPROVED_SOURCE`（SMM/Asian Metal/CCMN/100ppi 调查）保持，不虚构可用 provider。
- 每个标的 routeDecision/fallbackReason/生效时间由 `MaterialRouteDecision` 可审计保存。

## AT-SRC-005 ADC12/AZ91D 三层路由与 P0 判定 —— DAY3_PARTIAL_PASS（正式 verified 链属 Day4）

| 序列（来源意图×材料） | selected tier | selected provider | fallbackReason | synthetic excluded |
|---|---|---|---|---|
| SMM × ADC12（MAT.ADC12.SMM） | fallback_manual | manual-material | smm-authorized-api=credentials_missing | 是 |
| SMM × AZ91D（MAT.AZ91D.SMM） | fallback_manual | manual-material | smm-authorized-api=credentials_missing | 是 |
| Asian Metal × ADC12（MAT.ADC12.AM） | fallback_manual | manual-material | am-authorized-api=credentials_missing | 是 |
| Asian Metal × AZ91D（MAT.AZ91D.AM） | fallback_manual | manual-material | am-authorized-api=credentials_missing | 是 |

- 四条 P0 序列均获得确定性、可解释、可审计的合法路由；切换不静默（fallbackReason 记录）；PBOC 被排除在材料路由外；SyntheticDemo 恒非候选。
- "至少一条非 synthetic 路线完成 raw 到已验证文件链"的 Day4 verified 部分：DEC-057 拆分后属 D4-T01/D4-T02；Day3 完成 raw→PARSED+PENDING 受理链（见 AT-SRC-007）。商业自动能力 N/A 未扩散为整体 P0 BLOCKED。

## AT-SRC-006 FreePublic 全链 —— BLOCKED（Day3；无获认可免费公开来源）

- 冻结 testcase（FreePublicDataProvider 自动获取→校验→VERIFIED 发布→daily/aggregate→页面字段变化模拟）当前无法合法执行：D3-T03 调查无获认可免费源（`NO_APPROVED_SOURCE`，证据 `docs/evidence/D3-T03/`），FreePublic 层为空，属既有外部依赖 EXT-10=`OPEN_EXTERNAL_NON_BLOCKING`（未创建新外部依赖）。
- 状态依据 docs/03 §3 `BLOCKED`：任何认可数据路线缺失，无法合法执行；不是 PASS。
- 技术事实区分：路由合法性（routeDecision/fallbackReason 可审计）、无伪造来源、Manual 合法替代链存在——由 AT-SRC-001（PASS）、AT-SRC-005（DAY3_PARTIAL_PASS）、AT-SRC-007（DAY3_PARTIAL_PASS）证明；**不**因其他测试证明 fallback 而把 AT-SRC-006 记为 PASS。
- 不绕过任何访问限制；无伪造自动来源。

## AT-SRC-007 ManualDataProvider 治理与门禁 —— DAY3_PARTIAL_PASS

- ADC12（MAT.ADC12.SMM）：Manual submission → immutable raw → RECEIVED+PENDING → `manual-material-normalization-v1` → **PARSED+PENDING**（value=19850.50 原样，声明来源/引用/operatorRef 保留）。
- AZ91D（MAT.AZ91D.AM）：同上 → **PARSED+PENDING**（value=24500）。
- 幂等：same key+same content=IDEMPOTENT_REUSE（复用 run/raw/timeline）；不同 content=NEW_PENDING_VERSION（旧版本保留）。
- PENDING 正式门禁：PublishedQueryService 空、daily 无行、aggregate 无文件、既有 Publish Gate=NOT_READY。
- 未产生 VERIFIED/VERIFIED_WITH_NOTICE/PUBLISHED；材料校验属 D4-T01（DEC-057）。

## AT-SRC-007 附：LocalImport CSV/XLSX 受理（ADC12/AZ91D）

- CSV：`IMP.ADC12.001`（123.456789012345678 精确 decimal）与 `IMP.AZ91D.001`（24500）→ RECEIVED+PENDING。
- XLSX：同两标的经 POI 文本单元格 → RECEIVED+PENDING；XLSX Source/Item Raw=ORIGINAL_FULL_FILE_BYTES、Item Raw SHA=source 原始 SHA（D3-T05 冻结语义保持）。
- PENDING 门禁：PublishedQueryService 空、daily 无行、aggregate 无文件。

## AT-SRC-008 来源不可冒充与跨出口一致性 —— DAY3_PARTIAL_PASS

### Day3 已验证范围（真实执行）

- 故意伪标：Manual 提交声明 actualSourceName="SMM官方页面报价（人工录入声明）" → providerType 恒 MANUAL、raw/candidate 身份恒"人工录入（Manual）"，声明来源仅作为用户声明/引用保留（declaredSourceName/sourceReference），未被转换为 OfficialWeb/FreePublic（预期 2、3 的 Day3 部分）。
- SyntheticDemo：身份恒 SYNTHETIC_DEMO、正式查询不可见；正式无数据（PRIMARY/FREE_PUBLIC/MANUAL 均不可用）→ ROUTE_UNAVAILABLE，绝不自动补 synthetic 材料价格（预期 2 的 Day3 部分）。
- 数据进入方式（providerType/accessMethod）与实际依据（actualSourceName/sourceReference/sourceUrl）全链分离（预期 1 的 raw/API/现有文件出口部分）。

### 未验证范围（冻结 testcase 依赖后续实现/出口）

- Dashboard、预警（warning）、Agent 报告、EvidencePack 未实现：跨出口全量一致性（raw/daily/API/UI/预警/EvidencePack/Agent 逐项对账，预期 1 与证据"跨出口字段对账表、页面/预警/Agent 截图"）尚不能执行；不得视为已验证。
- daily 级出口一致性：Day3 材料无已验证数据，daily 无行，无法执行 daily 出口对账。
- 预期 4（Agent 引用实际来源、不按用户提示改写）：依赖 Agent 实现，Day3 未执行。
- 结论：裸 PASS 不成立；本状态为 `DAY3_PARTIAL_PASS`（与 AT-SRC-005/007 同一冻结合法模式），完整 PASS 待对应出口实现后由后续 Day 窗口执行。

## 外部依赖

EXT-04 / EXT-10 / EXT-11 = `OPEN_EXTERNAL_NON_BLOCKING`（保持；SMM/Asian Metal 自动能力未配置、FreePublic 无获认可源、Manual 复核未决——均不影响本任务 Day3 结论，未自行变更）。

## 边界遵守

- 未产生 material VERIFIED/PUBLISHED；未实现材料校验/范围/future/stale/规格可比性/unit-currency 业务判断/source trust（DEFERRED_TO_D4_T01）；未实现 D4 validation/publish/daily/aggregate 正式链。
- Publish Gate/ProcessingStage/ValidationStatus 未修改；PBOC 正式路径未触碰（无需重跑真实联网 AT-SRC-002）。
- 无伪造材料价格、无 fixture 冒充真实采集、无 synthetic 伪装真实。
