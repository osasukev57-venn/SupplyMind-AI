# D1-T05 PBOC 双币 raw 闭环冒烟门禁记录（Review BLOCKER 修复后重跑）

> 证据性质：D1-T05 任务级真实采集、幂等、重启与失败路径冒烟门禁记录。
> 本记录为 Sol Review BLOCKER（重启读取未真实启动第二个 Spring Context）修复后的重新执行证据，只描述实际发生的行为。
> 不是 AT-SRC-002、Day 1 或 Day 2 总门禁的 PASS 证据；这些验收状态仍为 `NOT_RUN`。
> Day 1 退出与 D1-T05 最终状态按冻结规则由正式 Review / Gate 裁决。

## 执行范围

- 执行时间：`2026-08-09T22:49:27 ~ 22:49:30`（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`（Maven surefire 3.2.5）
- 独立 dataRoot：`D:\Dev\Projects\SupplyMind AI\backend\data\d1-t05-smoke`（每次门禁运行前强制清空；与 D1-T04 的 `backend/data` 完全独立，未复用其任何 raw）
- 访问方式：匿名公开 HTTPS；未使用 Cookie、token、登录、验证码、会员权限、未公开 API、TLS 绕过或第三方来源
- 链路：PBOC 公告列表 → 从列表真实 `href` 发现详情链接 → 详情页实体 → 双币 item 级 raw/manifest 与独立初始 timeline

## 执行阶段（实际行为）

| 阶段 | 实际行为 | 结果 |
|---|---|---|
| Context A 启动 | `SpringApplicationBuilder(SupplyMindApplication.class).web(NONE).run("--supplymind.data-root=<独立dataRoot>")` 创建第一个全新 Spring ApplicationContext，经生产配置类（FoundationStorageConfiguration / PbocOfficialWebConfiguration）装配全部 Bean | 启动成功 |
| 首次真实采集 | 从 Context A 获取 `PbocOfficialWebDataProvider` Bean，执行真实 PBOC 双币采集，raw/manifest/timeline 落盘 | SUCCESS |
| 首次落盘核对 | 经 Context A 的 `DataRoot` Bean 从磁盘读取，核对 raw/manifest/timeline 与结果对象一致 | PASS |
| 重复触发 | 仍在 Context A 内第二次真实触发 | FROZEN_CONFLICT_EVIDENCE（既有证据逐字节未动） |
| Context A 关闭 | `contextA.close()`；随后断言 `contextA.isActive()==false`（已关闭、已销毁 Bean、已释放单写锁） | 已关闭 |
| Context B 启动 | 以【同一物理 dataRoot】再次调用 `SpringApplicationBuilder(SupplyMindApplication.class).web(NONE).run(...)`，创建第二个全新 Spring ApplicationContext；断言 `assertNotSame(contextA, contextB)`、`contextB.isActive()`、`contextB.getBean(DataRoot.class).path()==同一dataRoot` | 新 Context 启动成功（含重新获取单写锁） |
| 重启读取 | 仅从 Context B 重新获取存储/配置相关 Bean（DataRoot / RawReceiptStore / AtomicFileStore / ConfigActivationStore / PbocOfficialWebDataProvider / SingleWriterGuard），经 Context B 的 DataRoot Bean 从磁盘读取 Context A 已写数据 | PASS |
| Context B 关闭 | `contextB.close()` | 已关闭 |
| 失败路径 | Context B 关闭后，以同一 dataRoot 上手动构造的断开传输 Provider 触发真实 ConnectException | EXTERNAL_ACCESS_BLOCKED，未造数、未写任何文件 |

## HTTP 元数据与来源

| 项目 | 值 |
|---|---|
| 公告列表 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html` |
| 实际发现的详情页 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html` |
| 详情页 HTTP 状态 | `200` |
| 详情页 Content-Type | `text/html` |
| 业务日期 | `2026-08-07` |
| 来源发布时间 | `2026-08-07T09:25:38+08:00`（文章来源字段） |
| 实际来源名 | `中国人民银行官网（授权中国外汇交易中心公布）` |
| 响应实体 SHA-256（双币共享） | `f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` |

完整响应实体仅保存在两个 `RawReceiptV1.payloadBase64` 中；本记录不复制响应实体。

## 双币 RawReceiptV1

共享 `acquisitionId`：`pboc-acq-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82`

| itemId | runId | rawRef | matchAnchor | 原始 decimal string | unit |
|---|---|---|---|---|---|
| `FX.USD.CNY.PBOC_MID` | `pboc-usd-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` | `raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/pboc-usd-20260807-<sha>.json` | `1美元对人民币` | `6.7904` | `CNY/1 USD` |
| `FX.EUR.CNY.PBOC_MID` | `pboc-eur-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` | `raw/formal/official_web/FX.EUR.CNY.PBOC_MID/2026/08/pboc-eur-20260807-<sha>.json` | `1欧元对人民币` | `7.8067` | `CNY/1 EUR` |

`<sha>` 即上述响应实体 SHA-256。runId 与 rawRef 均独立；`receivedAt=2026-08-09T22:49:28.969681300+08:00`，`updatedAt` 与之相等。

门禁断言（真实数据交叉核对）：

- 两个 raw 的解码响应实体逐字节相同，且 `payloadSha256` 与实体 SHA-256 一致；
- `rawValue` 与保留官方页面可见文本中的锚点值一致（`1美元对人民币6.7904元` / `1欧元对人民币7.8067元`，独立正则提取，剔除 meta/标签后唯一命中）；
- 来源、业务日期、落款发布时间、单位、币种、HTTP 状态、Content-Type 与冻结契约一致。

## 文件级 SHA-256（本轮独立 dataRoot）

| 文件 | SHA-256 |
|---|---|
| USD raw 文件 | `67b67421d257a10c1391ad4d6eb3b2ca9b51df66076af295f1c489d8ad73a48b` |
| USD raw manifest 文件 | `dc07cc9d570906c4ba3af886317e5b5df742c96befb366dfdfd225f642b4fb57` |
| USD staging timeline 文件 | `3e49a77267908f706c90b31ba213471800edb7187ad956795d4aefb5acc1cb74` |
| EUR raw 文件 | `507a4c8ee61b85c0db7d2b7acf2b8d7a9689b3d3dc23c07307340766c7a0ac4b` |
| EUR raw manifest 文件 | `bf5f31cc57df2ec842948700baacdf869320835cef080173daca8682ca263419` |
| EUR staging timeline 文件 | `3c8cabcf5e991270c4c157fbb6dea4581076bf1f8ed43e4c4daee7c5256cece7` |

每个 manifest 的 `fileSha256`/`byteLength`/`fileName` 与对应业务文件逐项核对一致，并通过 `ManifestVerifier.matches`（含 `sourceRunIds`）校验。

## LifecycleTimelineV1

两个 raw 各自创建独立的 `staging/<runId>.json` 与 manifest：

- `recordVersion=1`
- `ProcessingStage=RECEIVED`
- `ValidationStatus=PENDING`
- `candidate=null`，无 reasonCode / validationVersion / validatedAt / publishedAt / publishRef
- `isPublishedForDailyInput=false`

未执行验证、发布、daily、aggregate、warning、dashboard 或 Agent 流程；数据生命周期停留在 `RECEIVED+PENDING`。

## 重复触发（冻结幂等语义验证）

第二次真实触发与首次完全相同（同一公告、同一 payload SHA-256、确定性 runId/acquisitionId），但 raw 含必填 `receivedAt`（纳秒级新时间戳），因此 incoming 完整文件字节 hash 与既有不可变 raw 不同。按冻结规则（总计划 8.5.6：raw 同 hash 幂等、异 hash 冲突、绝不覆盖；DEC-044；AT-FILE-000 冻结冲突路径）触发：

- 既有 USD/EUR raw、manifest、staging timeline 全部逐字节不变（before/after 全量 SHA-256 对比）；
- 未创建任何非法记录；仅新增一份冻结格式的冲突证据：
  - `runtime/conflicts/raw/FX.USD.CNY.PBOC_MID/2026-08/pboc-usd-20260807-<sha>/raw-conflict-5c880ddb-be55-4af8-bc3d-fb844bdcdbad.json`（含相邻 manifest）
  - `existingFileSha256=67b67421…`（= 既有 USD raw 文件 hash，原样保留）
  - `incomingFileSha256=f248d7136a7ff5703ad6295ba90279f661c0d9bd430506bd98e401addf08c4e5`（= incoming receipt 重编码后 hash，与证据字段一致）
  - `incomingReceivedAt=2026-08-09T22:49:29.4268806+08:00`、`detectedAt=2026-08-09T22:49:29.4318826+08:00`
  - `RawConflictEvidenceV1` schema 校验通过，`incomingReceipt` 完整嵌入，`runId/rawRef/itemId` 相互一致
- 采集调用以 `PERSISTENCE_FAILED`（cause 链含 `RawReceiptConflictException`）fail-closed 终止，绝不静默放行或改写证据。

## 重启读取（Review BLOCKER 修复后的真实执行方式）

实际执行（与本记录前述"执行阶段"一致）：

1. 真实 Spring Context A（`SpringApplicationBuilder(SupplyMindApplication.class)`）完成采集后 `close()`；断言 `contextA.isActive()==false`，确认已释放 Bean 与单写约束；
2. 使用【同一物理 dataRoot】`D:\Dev\Projects\SupplyMind AI\backend\data\d1-t05-smoke` 重新执行 `SpringApplicationBuilder(SupplyMindApplication.class)` 启动全新 Spring Context B；断言 `assertNotSame(contextA, contextB)`、`contextB.isActive()`、`contextB.getBean(DataRoot.class).path()==同一dataRoot`。Context B 启动成功本身即证明单写锁已被 A 释放并可重新获取；
3. 从 Context B 重新获取实际存储/配置相关 Bean：`RawReceiptStore`、`AtomicFileStore`、`ConfigActivationStore`、`PbocOfficialWebDataProvider`、`SingleWriterGuard`，并仅经 Context B 的 `DataRoot` Bean 解析路径从磁盘读取；
4. 核验结果：
   - USD raw、EUR raw 与 Context A 关闭前磁盘文件逐字节一致（全量 SHA-256 对比通过，`filesUnchanged=true`）；
   - 解码后对象与 Context A 首次采集结果完全相等（`rawValue=6.7904` / `7.8067`）；
   - raw 的 manifest 重新校验通过（fileName/fileSha256/byteLength/sourceRunIds/ManifestVerifier）；
   - staging timeline 逐字节一致，解码后 `RECEIVED+PENDING`、`candidate=null`，manifest 重新校验通过；
   - 全部业务文件（含冲突证据）在重启前后全量 SHA-256 一致；
5. Context B 完成验证后 `close()`。

未使用 `DataRoot.forTest(root)` 或手工存储对象替代 Context B；未复用 Context A 的任何 Bean/store/codec/内存对象。

## 失败路径（断网重试不造数）

使用同一 dataRoot 上的真实 JDK HTTPS 传输（代理指向本机关闭端口）触发真实 `ConnectException`：

- `PbocCollectionException`：`EXTERNAL_ACCESS_BLOCKED / HTTP`；
- 脱敏诊断事件：`outcome=EXTERNAL_ACCESS_BLOCKED`、`url=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html`（无 query/凭据）、`exception=ConnectException`、`httpStatus=null`；
- 未创建任何 raw/staging/conflict 文件，既有证据逐字节未动（全量对比通过）；
- 另以空 dataRoot 独立验证：断网重试不产生 `raw/`、`staging/`、`runtime/conflicts/` 目录。

## 测试与结果

| 命令/检查（Java 17） | 结果 |
|---|---|
| `mvnw.cmd -q '-Dtest=PbocRawClosedLoopSmokeGateTest' '-Dpboc.real-network=true' '-Dd1-t05.data-root=...\backend\data\d1-t05-smoke' '-Dd1-t05.evidence-dir=...\docs\evidence\D1-T05' test` | PASS，2 tests（真实门禁 + 断网不造数） |
| 门禁定向回归：`PbocOfficialWebDataProviderContractTest`（6）、`RawAndConfigStoreTest`（1）、`AtomicFileStoreWriteInvariantTest`（6）、`DualCurrencyRawLifecycleAcceptanceTest`（3）、`AtFile000DualArtifactImmutabilityAcceptanceTest`（3）、`PbocRawClosedLoopSmokeGateTest`（1，真实门禁按属性跳过） | 21 tests，0 failures，0 errors |
| surefire 测试计数报告 | `backend/target/surefire-reports/com.supplymind.provider.pboc.PbocRawClosedLoopSmokeGateTest.txt`（Tests run: 2, Failures: 0, Errors: 0） |

可持续证据以本记录的磁盘产物与 SHA-256、`d1-t05-smoke-gate-summary.json`（含 `restartRead.secondSpringContext=true`、`distinctFromContextA=true`、`contextAClosedBeforeRestart=true`、`contextBDataRoot`、`beansResolvedFromContextB`、`filesUnchanged=true`）、surefire 测试计数为准；本记录不声称任何终端输出文件被完整冻结。

## Day 1 退出条件逐项判断

冻结口径（总计划、docs/03 第 938 行）：**Day 1 退出仅以 EUR/CNY、USD/CNY 真实 OfficialWeb 采集均生成可追溯 raw 为准**。

| 条件 | 证据状态 |
|---|---|
| EUR/CNY 真实 PBOC 采集并生成可追溯 raw JSON | 满足：raw + manifest + RECEIVED+PENDING timeline，SHA-256/来源/日期/单位/原始值核对通过 |
| USD/CNY 真实 PBOC 采集并生成可追溯 raw JSON | 满足：同上 |
| 重复执行满足冻结幂等规则 | 满足：异 hash 走冻结冲突证据路径，既有证据逐字节未动，绝不覆盖 |
| 重启后仍可读取 | 满足：真实 Context A 关闭 → 全新 Spring Context B（同一 dataRoot）重新初始化 → 经 Context B Bean 从磁盘读取内容一致 |
| 失败可诊断 | 满足：真实 ConnectException + 脱敏诊断事件 + 不造数 |

Day 1 是否正式退出：按冻结规则由正式 Review / Gate 裁决（本窗口提交 `REVIEW_PENDING`，不自行宣布 Day 1 退出）。AT-SRC-002 仍为 `NOT_RUN`（其 PASS 需要 D2 全链，本任务未越权）。

## D1-T05 DoD 判断

| DoD 条款 | 状态 |
|---|---|
| EUR/CNY、USD/CNY 均有真实 PBOC raw 证据 | 满足 |
| 任一缺失则 Day 1 不退出 | 不适用（均存在）；最终 Day 1 退出状态由正式 Gate 裁决 |

## 实现说明与风险

本次为 Sol Review BLOCKER 定点修复：`PbocRawClosedLoopSmokeGateTest` 重启读取阶段不再以 `DataRoot.forTest(root)` 直接读盘代替，而是真正执行 Context A → close（断言 inactive）→ 同一 dataRoot 启动全新 Spring Context B → 从 Context B 重新获取存储/配置 Bean 并经其 DataRoot Bean 从磁盘读取校验 → 关闭 Context B。门禁真实测试通过 `-Dpboc.real-network=true` 显式开启；`networkFailureNeverFabricatesDataOrWritesArtifacts` 为确定性测试，普通回归始终运行。修复仅涉及测试文件，未修改任何生产代码。

风险仍然存在：PBOC HTML 结构或字段可能发生漂移，后续运行须继续 fail-closed；本次成功不构成 AT-SRC-002 或 Day 1/Day 2 全链验收通过。真实重复触发因 raw 必填 `receivedAt` 走冻结冲突证据路径（绝不覆盖），符合总计划 8.5.6 与 DEC-044；D2 的采集窗口幂等批次消重属后续任务职责。
