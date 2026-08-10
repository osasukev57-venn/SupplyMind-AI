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

## AT-SRC-006 FreePublic 全链 —— N/A_APPROVED_FALLBACK（Day3）

- D3-T03 调查无获认可免费源（NO_APPROVED_SOURCE，证据 `docs/evidence/D3-T03/`）；FreePublic 层为空，替代路线由 AT-SRC-007（Manual）证明；不绕过任何访问限制。

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

## AT-SRC-008 来源不可冒充与跨出口一致性 —— PASS（现有正式边界）

- 故意伪标：Manual 提交声明 actualSourceName="SMM官方页面报价（人工录入声明）" → providerType 恒 MANUAL、raw/candidate 身份恒"人工录入（Manual）"，声明来源仅作为用户声明/引用保留（declaredSourceName/sourceReference），未被转换为 OfficialWeb/FreePublic。
- SyntheticDemo：身份恒 SYNTHETIC_DEMO、正式查询不可见；正式无数据（PRIMARY/FREE_PUBLIC/MANUAL 均不可用）→ ROUTE_UNAVAILABLE，绝不自动补 synthetic 材料价格。
- 数据进入方式（providerType/accessMethod）与实际依据（actualSourceName/sourceReference/sourceUrl）全链分离。

## 外部依赖

EXT-04 / EXT-10 / EXT-11 = `OPEN_EXTERNAL_NON_BLOCKING`（保持；SMM/Asian Metal 自动能力未配置、FreePublic 无获认可源、Manual 复核未决——均不影响本任务 Day3 结论，未自行变更）。

## 边界遵守

- 未产生 material VERIFIED/PUBLISHED；未实现材料校验/范围/future/stale/规格可比性/unit-currency 业务判断/source trust（DEFERRED_TO_D4_T01）；未实现 D4 validation/publish/daily/aggregate 正式链。
- Publish Gate/ProcessingStage/ValidationStatus 未修改；PBOC 正式路径未触碰（无需重跑真实联网 AT-SRC-002）。
- 无伪造材料价格、无 fixture 冒充真实采集、无 synthetic 伪装真实。
