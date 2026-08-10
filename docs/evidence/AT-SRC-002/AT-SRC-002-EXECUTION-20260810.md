# AT-SRC-002 正式执行记录 —— Day1-Day2 PBOC 双币真实获取与文件闭环（2026-08-10）

> **状态说明（2026-08-10 晚，DEC-056 修复后）**：本文件正文第一轮（commit `24d24b6`，执行时间 14:12）的候选 PASS **经固定快照 Review + Fact Adjudication 判定无效**：A raw-first=FAIL（parse 失败无 source 证据落盘）、B idempotency=AMBIGUOUS（receivedAt 导致同 payload 重复采集误判 CONFLICT）、C gated evidence=FAIL（无 tracked runner 原件）。第一轮记录保留作为历史执行事实。**正式验收以本文档末尾"DEC-056 修复后正式重跑"为准**（commit 待本轮提交）。

> 性质：D2-T05（PBOC调度、幂等、重启端到端硬门）正式验收执行证据。
> 执行方式：真实联网采集（PBOC 官方网页，Java 17 JDK HTTPS），非 fixture、非 mock、非重放既有 raw。
> 机器证据：`docs/evidence/AT-SRC-002/at-src-002-summary.json`（由测试运行真实输出生成）。

## 执行环境

| 项 | 实际值 |
|---|---|
| 执行时间 | 2026-08-10T14:12（Asia/Shanghai）（第一轮，已判无效；DEC-056 重跑见文末） |
| Java 运行时 | 17.0.19（Microsoft OpenJDK） |
| Maven | 3.9.11 |
| Spring Boot | 3.3.6 |
| 验收 dataRoot | `D:\Dev\Temp\opencode\at-src-002-run`（空的独立目录，启动前清空） |
| 真实数据源 | 中国人民银行官网（授权中国外汇交易中心公布），`https://www.pbc.gov.cn` 公告详情页 |
| 列表页 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html` |
| 详情页 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009005136814/index.html` |
| 业务日期 | 2026-08-10（今日官方公告） |
| 官方响应 payload SHA-256 | `d7d03779bc630b8df6c2dffc02d3937f4ef4f7051022c41764724f828e30544a`（双币共享同一官方响应） |
| acquisitionId | `pboc-acq-20260810-d7d03779bc630b8df6c2dffc02d3937f4ef4f7051022c41764724f828e30544a` |

## 真实业务值（来自官方页面，raw 保留）

| 币种 | itemId | rawValue（官方锚点） | raw 文件 SHA-256 |
|---|---|---|---|
| USD/CNY | FX.USD.CNY.PBOC_MID | 6.7884 | `57904305667b8efdd42aaaad7dc821ad5e3b1b873c318d557d9b6922495917bd` |
| EUR/CNY | FX.EUR.CNY.PBOC_MID | 7.8171 | 见 rawRef（runId 同名文件） |

runId / rawRef（真实）：`pboc-usd-20260810-d7d03779…` / `pboc-eur-20260810-d7d03779…`；
raw 路由：`raw/formal/official_web/FX.{USD,EUR}.CNY.PBOC_MID/2026/08/….json`。

## 执行步骤与逐项结果（docs/03 冻结步骤 1-5）

| 冻结步骤 | 实际执行 | 结果 |
|---|---|---|
| 1. 触发真实采集并分别保存 EUR/CNY、USD/CNY 原始 JSON | Java 17 HTTPS 实时获取官方公告，双币 raw + manifest 原子写入；actualSourceName=中国人民银行官网（授权中国外汇交易中心公布）、businessDate=2026-08-10、receivedAt、sourceUrl、payloadSha256、configVersion=1、acquisitionId、runId、rawRef 全部保留；raw 文件 SHA=manifest.fileSha256，ManifestVerifier PASS | PASS |
| 2. 标准化、基础校验和发布门禁 | 双 runId 走 validation.process（PARSED/PENDING→VALIDATED/VERIFIED，pboc-basic-validation-v1）→ publish.process（PUBLISHED + publishRef=`staging/pboc-usd-…json#recordVersion=4`）；PublishedQueryService 仅暴露 PUBLISHED+VERIFIED 记录；PENDING 不可见（业务读模型构造保证 + D2-T02 已证） | PASS |
| 3. 生成两币种 daily CSV 及月/季/半年/年 aggregate CSV | daily：`processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv`（SHA-256 `5532c68b…65e7`）、`…FX.EUR…/2026-08.csv`（SHA-256 `75e1449a…dbba`），各 1 行；aggregate：双币 × 4 级共 8 文件（见下方 SHA 表），manifest COMMITTED、fileSha256/byteLength/rowCount/sourceRunIds 对账，ManifestVerifier PASS | PASS |
| 4. 重复同业务日期采集验证幂等 | 二次触发同一公告：receivedAt 不同→不同 hash→raw 层 fail-closed（PERSISTENCE_FAILED，cause=RawReceiptConflictException），仅新增冻结冲突证据 `runtime/conflicts/raw/…`；快照对比：无任何已有字节 changed/deleted；timeline（publishRef/publishedAt）字节未变；daily/aggregate CSV 字节未变；不重复发布 | PASS |
| 5. 关闭并重启程序，从本地文件查询历史和聚合 | Context A 完全关闭 → 全新 Context B 同一物理 dataRoot：PublishedQueryService 离线读出双币 PUBLISHED+VERIFIED 记录（publishRef 一致）；daily CSV 解码与 Phase A 逐字节等价；aggregate 经只读 `AggregateReadService` discover/verify/decode（未调用 processYear/processGrain/rebuild/write），四级 decode 与 Phase A 完全一致；重启后全 dataRoot 快照与重启前逐文件 SHA 一致（零写入） | PASS |

## 独立复算（冻结精度规则，BigDecimal 从 String 构造，无 float/double）

| 检查 | USD/CNY | EUR/CNY |
|---|---|---|
| raw 官方值 | 6.7884 | 7.8171 |
| expected daily avg（scale 8 HALF_UP） | 6.78840000 | 7.81710000 |
| actual daily sum / avg | 6.7884 / 6.78840000 | 7.8171 / 7.81710000 |
| aggregate sum/avg/min/max（四级全部） | 6.78840000 | 7.81710000 |
| daily.updatedAt（DEC-052=max(publishedAt)） | 2026-08-10T14:12:49.296279100+08:00 | 2026-08-10T14:12:49.549299600+08:00 |
| aggregate.calculatedAt（DEC-055=max(daily.updatedAt)） | 同上 | 同上 |
| configVersions | [1] | [1] |

结果：独立复算 PASS，无精度损失。

## 四级 aggregate 文件 SHA-256（真实运行产物）

| itemId | month | quarter | halfyear | year |
|---|---|---|---|---|
| FX.USD.CNY.PBOC_MID | `84586a98…163db` | `17b825be…cfc08` | `faa11b98…dcee` | `ab2ab1d0…1bab` |
| FX.EUR.CNY.PBOC_MID | `819e5216…3edb` | `7d9f8543…5bab` | `c7705dce…5f7a` | `1a58ec57…ff7c` |

（全部 8 个文件 + 相邻 manifest 均经 ManifestVerifier 与派生字段校验 PASS。）

## 关键约束事实

- 未创建 `data/normalized`、`data/published` 物理目录；未使用任何数据库（MySQL/PostgreSQL/SQLite/H2/Redis/MongoDB 均无）。
- 重启 Context B 未触发任何采集/处理写盘调用（aggregate 只经 `AggregateReadService` 只读读取）。
- 真实网络正式启用（`-Dat-src-002.real=true` 门禁），本次外部 PBOC 网络可达，非 EXTERNAL_ENVIRONMENT_BLOCKED。
- 断网/失败路径由既有 `PbocRawClosedLoopSmokeGateTest.networkFailureNeverFabricatesDataOrWritesArtifacts`（非 gated，常跑）覆盖：EXTERNAL_ACCESS_BLOCKED 时零产物。

## 结论（第一轮，已判无效）

第一轮 AT-SRC-002 = 候选 **PASS**（commit `24d24b6`）——经固定快照 Review + Fact Adjudication 判定无效（raw-first FAIL / idempotency AMBIGUOUS / gated evidence FAIL），不作为正式结论。

---

## DEC-056 修复后正式重跑（2026-08-10 14:56，Asia/Shanghai）

> 本轮为 DEC-056 修复后的正式重跑，独立空 dataRoot `D:\Dev\Temp\opencode\at-src-002-run-dec056`（启动前清空）。旧的 24d24b6 PASS 声明不复用。

### Raw-first（DEC-056）

- 生产顺序：`transport.get(detail)` → 持久化 `RawAcquisitionV1`（`raw/source/<acquisitionId>.json`，原子写入 + 相邻 manifest COMMITTED + SHA-256/length）→ `decodeHtml`/`parseDetail` → item `RawReceiptV1`（含 `acquisitionRef` 追溯）→ validation → publish → daily → aggregate。
- 本次真实 acquisitionRef：`raw/source/pboc-acq-2026081009005136814-d7d03779….json`（详情页 URL 段 `2026081009005136814` + payload SHA 派生，解析前可计算）。
- parse 失败路径（确定性契约，非真实网络）：`PbocOfficialWebDataProviderContractTest` 三个 parse-rejection 用例 + `PbocRawFirstContractTest`——失败时 acquisition 已落盘且 manifest 可验证，无 item raw/staging/downstream。

### 幂等（DEC-056）

- 相同 stable business key（provider+item+businessDate）+ 相同 payloadSha256 = **IDEMPOTENT REPLAY**：重复采集返回同一 acquisitionId/businessDate/payloadSha/runId/rawRef；dataRoot 快照逐文件 SHA 零变化（无新增、无删除、无改写）；无 RawReceiptConflictException；不重复 publish；daily/aggregate 字节不变。
- `receivedAt` 不再造成冲突（observation metadata 与业务 identity 分离；fileSha256 仅用于文件完整性，payloadSha256+业务键用于幂等/冲突判断）。
- 同业务键 + 不同 payload = **CONFLICT**：保留原正式 raw、写 `runtime/conflicts/raw/…` 证据（RawConflictEvidenceV1 + manifest）、fail-closed（确定性 fixture 测试 `PbocRawFirstContractTest.sameBusinessKeyWithDifferentPayload…` 证明；真实 AT 无法合法制造 PBOC 官方 payload 变更，故负向场景用确定性测试，不伪装真实联网）。

### 真实链路结果（与第一轮同为 2026-08-10 官方公告，payload SHA 相同）

| 项 | USD/CNY | EUR/CNY |
|---|---|---|
| 官方 rawValue | 6.7884 | 7.8171 |
| daily sum / avg（scale 8 HALF_UP） | 6.7884 / 6.78840000 | 7.8171 / 7.81710000 |
| month/quarter/halfyear/year sum/avg/min/max | 6.78840000 | 7.81710000 |
| configVersions | [1] | [1] |

- 完整链 PASS：raw acquisition → item raw → validation（VERIFIED, pboc-basic-validation-v1）→ publish（publishRef=`staging/pboc-usd-….json#recordVersion=4`）→ daily（双币各 1 行）→ 四级 aggregate（双币 × 4 = 8 文件，manifest COMMITTED 全对账）。
- 重复真实采集：**IDEMPOTENT**（快照零变化）。
- Restart：Context A 关闭 → 全新 Context B 同一物理 dataRoot 离线只读（PublishedQueryService/daily decode/`AggregateReadService`，无 processYear/rebuild/write），快照零变化。
- BigDecimal 独立复算 PASS；Manifest PASS；Traceability（rawRef/publishRef/dailyRef/aggregate inputRefs/configVersions）PASS。

### Gated runner 证据（DEC-056）

- `docs/evidence/AT-SRC-002/TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml`（本次真实 gated run 原件，未人工编辑）
- runner XML SHA-256：`2fa63b319def9c8b0588315a77d8d6306bae6bc95ff25f60807c53930624ea5f`
- `at-src-002-summary.json`：acceptanceTest=AT-SRC-002、realGateProperty=`at-src-002.real`、realGateValue=`true`、tests=1、failures=0、errors=0、skipped=0、result=PASS、businessDate=2026-08-10、USD=6.7884、EUR=7.8171、payloadSha、dataRoot、runnerEvidenceRef、runnerEvidenceSha256（计数来自本次实际 runner 结果，非硬编码假数据）。

### 结论（正式）

AT-SRC-002 = **PASS_CANDIDATE**（DEC-056 修复后真实双币全链正式运行 1/1，awaiting fixed-commit Review；D2-T05=REVIEW_PENDING，Day 2 未宣布 COMPLETE）。
