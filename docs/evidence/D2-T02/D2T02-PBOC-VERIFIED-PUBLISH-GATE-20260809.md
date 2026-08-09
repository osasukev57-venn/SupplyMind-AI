# D2-T02 PBOC VERIFIED 发布门禁实施记录

> 证据性质：D2-T02 任务级发布门禁、quarantine 投影与业务读模型实施/测试/真实 raw 证据记录。
> 不是 AT-SRC-002、Day 2 总门禁的 PASS 证据；AT-SRC-002 仍为 `NOT_RUN`。
> D2-T02 最终状态按冻结规则由正式 Review / Gate 裁决。

## 执行范围

- 执行时间：2026-08-09（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`
- 输入：D2-T01 校验结果（真实 D1-T05 双币 raw 校验后 `VALIDATED+VERIFIED`）、raw 引用、冻结发布契约（总计划 8.4.3、DEC-042、C28/C31、AT-PUB-001/002/003）
- 未使用真实网络：输入全部来自已落盘真实 raw 与合成 fixture

## 实现内容（最小范围，未改动 D1/D2-T01 基础）

| 组件 | 职责 |
|---|---|
| `foundation/storage/QuarantineStore.java`（新增） | 终态失败 quarantine 证据投影的 CREATE_NEW 不可变持久化：同 hash 幂等重放（可修复 manifest）、异 hash fail-closed 绝不覆盖；相邻 manifest，sourceRunIds=[runId] |
| `publish/LifecyclePublishService.java`（新增） | 最小发布边界编排：`VALIDATED+VERIFIED/VERIFIED_WITH_NOTICE` → 追加 `PUBLISHED` 快照（recordVersion 4，publishRef=`staging/<runId>.json#recordVersion=4`，publishedAt 必填，Candidate/validationVersion/validatedAt/reasonCode 保持）；三个失败终态 → `QuarantineProjectionV1.fromTerminal` 确定性投影并落盘（timeline 逐字节不动）；PENDING → `NOT_READY` 不动；PUBLISHED 重放幂等 no-op |
| `publish/PublishedQueryService.java`（新增） | 业务读模型：唯一业务入口，只暴露 `PUBLISHED+VERIFIED` 类记录（`findPublished` / `latestPublished`）；每条记录含 itemId/businessDate/value/currency/unit/来源身份/validationVersion/validatedAt/publishedAt/publishRef/runId/rawRef/recordVersion/rawPayloadSha256/rawFileSha256/stale；读取全部经 manifest 校验（timeline/raw/raw manifest）fail-closed |
| `publish/PublishOutcome.java`、`publish/PublishedRecord.java` | 结果与读模型载体 |

未创建 `data/published` 或任何隐藏"已发布仓储"；未修改 TimelineStore/CandidateV1/validation/DEC-050/Day 1 foundation；未实现 daily/aggregate/warning/Agent/Vue API。quarantine 物理目录 `data/quarantine/` 属冻结目录树（DEC-005/DEC-043），本任务按冻结契约写入。

## 核心执行链（实际实现数据流）

```
staging/<runId>.json
  ├─ VALIDATED+VERIFIED类 → TimelineStore.append(PUBLISHED 快照) → recordVersion=4
  │     publishRef=staging/<runId>.json#recordVersion=4；publishedAt=Clock.now
  │     CandidateV1/validationVersion/validatedAt/reasonCode 与快照3逐字段一致（模型强制）
  ├─ RECEIVED+REJECTED / VALIDATED+REJECTED / VALIDATED+CONFLICT
  │     → raw/manifest 校验读取（payloadSha256 + raw fileSha256）
  │     → QuarantineProjectionV1.fromTerminal（quarantinedAt=终态快照.updatedAt）
  │     → QuarantineStore.store（CREATE_NEW，quarantine/<itemId>/YYYY-MM/<runId>.json + manifest）
  │     → timeline 逐字节不动
  ├─ RECEIVED+PENDING / PARSED+PENDING → NOT_READY，不动
  └─ PUBLISHED → ALREADY_PUBLISHED 幂等 no-op

业务入口（PublishedQueryService）：
  staging 扫描（manifest 校验）→ 仅 PUBLISHED+VERIFIED 类 → PublishedRecord
  （raw payloadSha256 经 rawRef 解析、rawFileSha256 经相邻 raw manifest 解析）
```

## 生命周期变化

- `VALIDATED+VERIFIED → PUBLISHED+VERIFIED`（recordVersion 3→4）
- `VALIDATED+VERIFIED_WITH_NOTICE → PUBLISHED+VERIFIED_WITH_NOTICE`（reasonCode 保持）
- 三个失败终态（`RECEIVED+REJECTED`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT`）不发布、保持原状态，仅生成 quarantine 投影
- `RECEIVED+PENDING`、`PARSED+PENDING` 保持原状态（等待校验）
- 未进入 daily/aggregate；AT-PUB-001 唯一发布链 `1→2→3→4` 完整保持

## 文件路由（实际写入）

- `data/staging/<runId>.json`（+manifest）：PUBLISHED 追加（原子替换，旧快照保留）
- `data/quarantine/<itemId>/YYYY-MM/<runId>.json`（+manifest）：终态失败投影（CREATE_NEW，不可变）
- 无新增业务物理目录；未创建 `data/published`/`data/normalized`

## 真实数据验证（复用 D1-T05 真实 raw，无网络）

输入复制自 `backend/data/d1-t05-smoke`（config/raw/staging 连同 manifest，逐字节一致；真实页面 SHA-256=`f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82`）：

| runId | itemId | 值 | 结果 |
|---|---|---|---|
| `pboc-usd-20260807-f37cda1f…4f82` | FX.USD.CNY.PBOC_MID | 6.7904 | D2-T01 VERIFIED → `PUBLISHED+VERIFIED`（recordVersion=4，publishRef 正确，publishedAt=2026-08-09T22:50+08:00） |
| `pboc-eur-20260807-f37cda1f…4f82` | FX.EUR.CNY.PBOC_MID | 7.8067 | D2-T01 VERIFIED → `PUBLISHED+VERIFIED`（同上） |

- 发布后 raw 文件逐字节不变；无 quarantine 文件生成（合法数据不误入隔离）
- 业务入口可见：`latestPublished` 返回 6.7904/7.8067，rawPayloadSha256 与真实页面一致；按 DEC-051 计算 `calendarAgeDays=2`（businessDate 2026-08-07 → referenceDate 2026-08-09）→ `stale=false`（未硬编码，测试内按规则计算）
- 业务入口记录保留各自实际 publishRef：USD=`staging/pboc-usd-20260807-f37cda1f…4f82.json#recordVersion=4`、EUR=`staging/pboc-eur-20260807-f37cda1f…4f82.json#recordVersion=4`（与对应 PUBLISHED snapshot.publishRef 逐字一致）
- 重启重读（新 TimelineStore 实例）状态一致（PUBLISHED+VERIFIED，4 快照）
- 机器可读证据：`docs/evidence/D2-T02/d2-t02-real-raw-publish-summary.json`

## stale 语义说明（DEC-051 正式冻结）

`PublishedRecord.stale` 为查询时派生字段，正式语义（DEC-051，REPLACED 旧实现）：以 Asia/Shanghai 自然日计算 businessDate 与 referenceDate（查询参考日）的日期差，**超过 30 个自然日（日期差 >30）为 stale=true，等于或少于 30 日（日期差 <=30）为 stale=false**（当天、差 1 天、差 29 天、差 30 天均非 stale，差 31 天起 stale）。复用 DEC-050 的 30 个自然日阈值与边界，仅比较基准不同（validation 以 validationDate 为基准、查询以 referenceDate 为基准），不形成同名不同义。该规则不改变已持久化 validation 结果、不改变 Lifecycle 状态、不改变发布资格、不重写 timeline。

历史说明：初始实现曾采用"businessDate < referenceDate 即 stale"的非当日事实比较；正式 Review 判定该语义未获授权；随后经技术负责人裁决 REPLACED，当前正式语义为上述 >30 calendar days 规则（DEC-051）。旧算法不再有效。

## 测试结果（Java 17）

### `PublishGateTest`（9 tests，0 failures，0 errors）

| 用例 | 结果 |
|---|---|
| VERIFIED → PUBLISHED+VERIFIED，golden bytes 逐字节一致（`contracts/v1/valid/lifecycle-published-pboc-v1.json`），无 quarantine | PASS |
| VERIFIED_WITH_NOTICE → PUBLISHED+VERIFIED_WITH_NOTICE，reasonCode/审计字段保持 | PASS |
| RECEIVED+PENDING → NOT_READY，timeline 逐字节不变，无 quarantine | PASS |
| PARSED+PENDING → NOT_READY，同上 | PASS |
| VALIDATED+REJECTED → QUARANTINED：投影字段逐项对账（quarantineRef 路由、terminalRecordVersion=3、reasonCode、validationVersion 非 null、rawPayloadSha256/rawFileSha256 与 raw/manifest 一致、quarantinedAt=终态 updatedAt），timeline 不动，manifest 校验通过 | PASS |
| VALIDATED+CONFLICT → QUARANTINED（VALUE_CONFLICT） | PASS |
| RECEIVED+REJECTED → QUARANTINED（validationVersion=null，terminalRecordVersion=2） | PASS |
| quarantine 重放幂等：文件与 timeline 逐字节不变 | PASS |
| PUBLISHED 重放 → ALREADY_PUBLISHED，字节不变 | PASS |

### `PublishedQueryServiceTest`（6 tests，0 failures，0 errors）

| 用例 | 结果 |
|---|---|
| RECEIVED+PENDING / PARSED+PENDING / VALIDATED+REJECTED / VALIDATED+CONFLICT / 未发布的 VALIDATED+VERIFIED 在业务入口全部不可见 | PASS |
| 仅 PUBLISHED+VERIFIED 可见，全字段可追溯（runId/rawRef/recordVersion=4/validationVersion/validatedAt/publishedAt/publishRef/rawPayloadSha256/rawFileSha256） | PASS |
| latestPublished 返回最新 businessDate；stale 按 DEC-051 30 日窗口（31 天前值 stale、1 天前值非 stale） | PASS |
| stale 边界六档（referenceDate 固定 2026-08-10；31/52 天用例以更早校验时钟合法构造已发布记录）：当天/差1天/差29天/差30天 → false；差31天 → true；差52天（明显>30天） → true | PASS |
| PUBLISHED+VERIFIED_WITH_NOTICE 可见（同键双观测均可见） | PASS |
| 缺失 publishRef 的 PUBLISHED 快照按冻结模型 fail-closed（SchemaValidationException） | PASS |

### 真实 raw 门禁 `PublishRealRawEvidenceTest`（gated `-Dd2-t02.real-raw=true`，PASS）

见"真实数据验证"。

### 最小直接回归（7 类 56 tests，0 failures，0 errors，0 skipped）

`PublishedQueryServiceTest`(6)、`PublishGateTest`(9)、`PbocValidationPipelineTest`(25)、`DualCurrencyRawLifecycleAcceptanceTest`(3)、`RawAndConfigStoreTest`(1)、`AtomicFileStoreWriteInvariantTest`(6)、`PbocOfficialWebDataProviderContractTest`(6)。

## Review Fix 记录（publishRef MAJOR，2026-08-09）

正式 Review 判定发布读模型追溯字段不完整：`PublishedRecord` 未保留 PUBLISHED snapshot 的 `publishRef`。已修复：

- `PublishedRecord` 新增必填字段 `publishRef`；
- 字段来源：`PublishedRecord.of` 直接取当前 PUBLISHED snapshot 的 `snapshot.publishRef()`，不重新猜测或拼造；
- 测试：`PublishedQueryServiceTest.onlyPublishedVerifiedIsVisibleWithFullTraceability` 断言 `record.publishRef()` 非空、等于 `staging/<runId>.json#recordVersion=4`、且与对应 PUBLISHED snapshot.publishRef 逐字一致；新增 `missingPublishRefFailsClosedAtModelLevel` 验证缺失 publishRef 的 PUBLISHED 快照按冻结模型 fail-closed；
- 真实双币证据：USD/EUR 业务入口记录均保留实际 publishRef（见"真实数据验证"），与 snapshot 逐字一致。

本修复未改动发布资格、quarantine、TimelineStore、validation、DEC-050 与 stale 算法。stale 展示口径仍待技术负责人单独裁决（未关闭）。

## stale CHANGE_REQUEST 正式落地（DEC-051，2026-08-09）

技术负责人正式裁决 REPLACED 旧 stale 语义（`businessDate < referenceDate` 直接等同 stale 未获授权）：`PublishedRecord.stale` 冻结为查询时派生字段——Asia/Shanghai 自然日下 businessDate 距 referenceDate 超过 30 日 → true，等于或少于 30 日 → false（当天/差1/差29/差30 非 stale，差31 起 stale）；复用 DEC-050 阈值与边界，仅比较基准为查询 referenceDate；不改变已持久化 validation 结果、Lifecycle 状态与发布资格。已登记 `docs/06-DECISION-LOG.md` **DEC-051**。代码：`PublishedRecord.of` 的 stale 计算改为 `LocalDate.parse(businessDate).isBefore(referenceDate.minusDays(30))`。测试：六个可识别命名的独立边界测试（`staleAtReferenceDateIsFalse`、`staleOneDayBeforeReferenceIsFalse`、`staleTwentyNineDaysBeforeReferenceIsFalse`、`staleExactlyThirtyDaysBeforeReferenceIsFalse`、`staleThirtyOneDaysBeforeReferenceIsTrue`、`staleFarBeyondThirtyDaysIsTrue`），referenceDate 均固定 2026-08-10，30 与 31 天为关键边界（30→false、31→true），31/52 天用例以更早校验时钟合法构造已发布记录；真实双币按规则计算 `calendarAgeDays=2 → stale=false`（未硬编码）。

## D2-T02 DoD 逐项

| DoD 条款 | 状态 | 证据 |
|---|---|---|
| 所有非 PUBLISHED 或非 VERIFIED 类组合在业务入口不可见 | PASS | `PublishedQueryServiceTest.pendingRejectedConflictAndNotYetPublishedAreInvisibleAtBusinessEntry`；查询服务仅过滤 PUBLISHED+VERIFIED 类 |
| 发布记录仍能追溯到 PBOC raw 和 LifecycleRecord | PASS | PublishedRecord 含 runId/rawRef/recordVersion=4/validationVersion/validatedAt/publishedAt/rawPayloadSha256（经 rawRef 解析）/rawFileSha256（经相邻 raw manifest 解析）；真实 raw 门禁验证与真实页面 SHA-256 一致 |

## 状态与边界

- 数据生命周期：真实双币已到 `PUBLISHED+VERIFIED`；未进入 daily/aggregate（D2-T03）、warning、dashboard、Agent。
- 失败回退语义：发布动作仅追加 timeline 快照（原子、可追溯）；终态失败仅写 quarantine 投影，从不改写 timeline/raw；未降低 D2-T01 校验规则。
- 未修改 AT-SRC-002（保持 `NOT_RUN`）；未进入 D2-T03。
