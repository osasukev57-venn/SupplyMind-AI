# D1-T04 真实 PBOC Java 17 采集记录

> 证据性质：D1-T04 任务级真实采集与 Code Review 记录。  
> 不是 AT-SRC-002、Day 1 或 Day 2 总门禁的 PASS 证据；这些验收状态仍为 `NOT_RUN`。

## 执行范围

- 执行时间：`2026-08-09T18:18:03.8268996+08:00`（Asia/Shanghai）
- 运行时：Java `17.0.19`、Spring Boot `3.3.6`
- 访问方式：匿名公开 HTTPS；未使用 Cookie、token、登录、验证码、会员权限、未公开 API、TLS 绕过或第三方来源。
- 链路：PBOC 公告列表 → 从列表真实 `href` 发现详情链接 → 详情页实体 → 双币 item 级 raw 与初始 timeline。

## 真实尝试序列

这些是开发期显式执行的集成测试，不是运行时自动重试；`JdkPbocHttpTransport` 本身不重试。

| 时间（Asia/Shanghai） | 结果 | 已保存业务 raw |
|---|---|---|
| `18:12` | 列表 HTTP 200；解析器将同名、无日期的导航链接误作为公告候选，`PARSE_REJECTED/LIST` | 无 |
| `18:14` | 列表与发现的详情页均 HTTP 200；详情页使用 document `<title>` 而非 `<h1>`，`PARSE_REJECTED/DETAIL` | 无 |
| `18:18:03.8268996` | 在两处最小、PBOC 专用解析修复及合成回归后，双币采集成功 | USD 与 EUR 各一份 |

前两次没有生成值、raw、timeline 或验收 PASS；它们的脱敏结构摘要见本记录的“实现说明与风险”。

## HTTP 元数据与来源

| 项目 | 值 |
|---|---|
| 公告列表 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html` |
| 实际发现的详情页 | `https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html` |
| 详情页 HTTP 状态 | `200` |
| 详情页 Content-Type | `text/html` |
| 业务日期 | `2026-08-07` |
| 来源发布时间 | `2026-08-07T09:25:38+08:00` |
| 实际来源名 | `中国人民银行官网（授权中国外汇交易中心公布）` |
| 详情页实体字节数 | `28584` |
| 实体 SHA-256 | `f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` |

完整响应实体仅保存在两个 `RawReceiptV1.payloadBase64` 中；其中不包含 HTTP headers。本记录不复制响应实体。

## 双币 RawReceiptV1

共享 `acquisitionId`：

`pboc-acq-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82`

| itemId | runId | rawRef | sourceFieldKey | 原始 decimal string | unit |
|---|---|---|---|---|---|
| `FX.USD.CNY.PBOC_MID` | `pboc-usd-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` | `raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/pboc-usd-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82.json` | `1美元对人民币` | `6.7904` | `CNY/1 USD` |
| `FX.EUR.CNY.PBOC_MID` | `pboc-eur-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82` | `raw/formal/official_web/FX.EUR.CNY.PBOC_MID/2026/08/pboc-eur-20260807-f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82.json` | `1欧元对人民币` | `7.8067` | `CNY/1 EUR` |

本地独立核对：两个 raw 的解码响应实体逐字节相同，两个 SHA-256 都为上表值；`runId` 与 `rawRef` 均不同。

## LifecycleTimelineV1

两个 raw 各自创建独立的 `staging/<runId>.json` 与 manifest：

- `recordVersion=1`
- `ProcessingStage=RECEIVED`
- `ValidationStatus=PENDING`
- `candidate=null`

未执行验证、发布、daily、aggregate、warning、dashboard 或 Agent 流程。

## 测试与结果

| 命令/检查 | 结果 |
|---|---|
| `mvnw.cmd -q -Dtest=PbocOfficialWebDataProviderContractTest test`（Java 17） | PASS，6 tests |
| `mvnw.cmd -q -Dtest=PbocOfficialWebRealNetworkAttemptTest -Dpboc.real-network=true ... test`（Java 17） | PASS，真实 PBOC 链路成功 |
| `mvnw.cmd -q -Dtest=PbocOfficialWebDataProviderContractTest,RawAndConfigStoreTest,AtomicFileStoreWriteInvariantTest test`（Java 17） | PASS，13 tests |
| 解码实体 SHA-256 / 字节一致性 / raw 无 HTTP headers / timeline 状态核对 | PASS |

## 实现说明与风险

真实页面与合成夹具存在两处合法结构差异：公告列表带有同名但无日期的导航/历史链接；详情页使用 document `<title>` 而不是 `<h1>`。实现仅增加了这两种结构的严格、PBOC 专用处理：无日期链接不作为业务日期候选；仅当不存在唯一 `<h1>` 时接受唯一 document `<title>`。字段、数据模型、计算口径和文件基础设施均未改变。

风险仍然存在：PBOC HTML 结构或字段可能发生漂移，后续运行须继续 fail-closed；本次成功不构成 AT-SRC-002 或 Day 1/Day 2 全链验收通过。
