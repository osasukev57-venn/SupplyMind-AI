# D1-T02 Windows 原生重放最终汇总

- replayRunId: d1-t02-windows-replay-20260808T204852+0800
- submittedAt: 2026-08-08T21:05:51+08:00
- evidenceRevision: 2026-08-08T21:16:30+08:00（仅澄清`connectionResult`表述；未发起新的网络请求）
- submissionTaskExecutionStatus: REVIEW_PENDING
- submissionStatusReason: EVIDENCE_REPLAY_SUBMITTED
- codeReview: APPROVED（2026-08-08T22:00:40+08:00）；当前TaskExecutionStatus以docs/05实时台账为准
- JavaClient: NOT_RUN（D1-T04）
- AcceptanceStatus AT-SRC-002: NOT_RUN
- scope: 仅 D1-T02 调查证据；未创建 backend、业务 data、Provider、Spring Boot 或产品代码。

## 环境与请求限制

- Windows: Microsoft Windows 10 Pro 10.0.19045（build 19045，64-bit）
- PowerShell: 5.1.19041.2673 Desktop
- curl.exe: 8.0.1，libcurl 8.0.1，Schannel
- timeZone: China Standard Time / Asia/Shanghai
- proxyMode: 显式代理（已脱敏）；仅记录环境变量名 ALL_PROXY、HTTP_PROXY、HTTPS_PROXY、NO_PROXY，不记录其值、地址、用户名、密码、Cookie 或令牌。
- 请求总数: 4（PowerShell 与 curl 各访问列表、详情一次）；全部 retryCount=0；未使用 TLS/证书绕过、登录、验证码、访问控制或反爬绕过。

## 实际重放结果

| 客户端 | 列表页 | 详情页 | 完整 PBOC HTTP 实体 | 单一客户端完整路径 |
|---|---|---|---|---|
| Windows PowerShell Invoke-WebRequest | exitCode=1；HTTP=NOT_OBTAINED | exitCode=1；HTTP=NOT_OBTAINED | 未获得 | false |
| Windows curl.exe | exitCode=35；http_code=000 | exitCode=35；http_code=000 | 未获得 | false |

curl 保存的两个 headers 文件仅为 HTTP/1.1 200 Connection established 代理 CONNECT 协商，不是 PBOC HTTP 2xx 响应。两客户端均在 TLS 握手前失败，因此没有伪造 HTTP 状态、响应实体或 payload SHA-256。

## 逐币种最终结果

FX.USD.CNY.PBOC_MID | fieldContractResult=CONFIRMED | connectionResult=EXTERNAL_ACCESS_BLOCKED

FX.EUR.CNY.PBOC_MID | fieldContractResult=CONFIRMED | connectionResult=EXTERNAL_ACCESS_BLOCKED

字段契约保持已确认的 PBOC 官方 HTML 事实；本次 connectionResult 只针对当前 Windows 原生环境。没有任何一个客户端完成“列表页 2xx 并发现真实详情链接 → 详情页 2xx 并取得完整实体 → 双币锚点核对”，故两个币种均不得将`connectionResult`记为`CONFIRMED`。此调查结果不构成 Day 1、Day 2 或 AT-SRC-002 通过。

## 字段摘录 SHA-256 复核

- excerpt: docs/evidence/D1-T02/pboc-2026-08-07-response-excerpt.txt
- computed: 5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4
- declared token: 5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4
- declared file: 5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4 *pboc-2026-08-07-response-excerpt.txt
- expected: 5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4
- result: PASS

已确认字段事实仍为：业务日 2026-08-07；发布时间 2026-08-07 09:25:38；USD/CNY=6.7904（CNY/1 USD）；EUR/CNY=7.8067（CNY/1 EUR）。本次未获得新的完整 PBOC HTTP 实体。

## 证据工件

- 命令与 PowerShell 错误全文: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-replay.md；SHA-256=07D512FBE282414C389EC09FFCA54D6651BC8DA539717B0DFDA65DE1EF13F6E2
- 命令与 curl 结构摘要: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-replay.md；SHA-256=F17EEEE125EE248B5D354068C43406381839C59D6EBAB37EAD87F0B572ED18DA
- PowerShell 错误: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-list.error.txt 与 docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-detail.error.txt
- curl 结构摘要: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.client-output.txt 与 docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.client-output.txt
- curl CONNECT 协商头: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.headers.txt 与 docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.headers.txt

本任务未写入业务 raw 或 LifecycleRecord；因此没有本次业务记录的 ProcessingStage 或 ValidationStatus。二者仍为后续数据链的独立字段，不能与 TaskExecutionStatus 或 connectionResult 混用。
