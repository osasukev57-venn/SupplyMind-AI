# D1-T02 PBOC 连通性验证记录

> 证据运行号：d1-t02-pboc-contract-20260808T184207+0800  
> 验证窗口：2026-08-08 18:42（Asia/Shanghai）  
> 范围：来源和字段契约验证；不创建后端、不写业务 raw 文件、不执行校验或发布。
> Review 结论：字段事实有效；补充的 Windows 原生重放已闭合原始证据缺口，D1-T02 已由技术负责人批准为`DONE`。

## 1. 测试结果

| 编号 | 操作 | 结果 | 关键事实 |
|---|---|---|---|
| T01 | 读取 PBOC 公告列表页 | PASS | 页面返回 text/html，列出 2026-08-07 为最新公告业务日，并提供实际详情链接。 |
| T02 | 读取 2026-08-07 PBOC 公告详情页 | PASS | 页面返回 text/html，标题、文章来源、正文和落款均可读取。 |
| T03 | 从同一官方响应分别提取 USD/CNY | PASS | “1美元对人民币6.7904元”；业务日期 2026-08-07；发布时间 2026-08-07 09:25:38。 |
| T04 | 从同一官方响应分别提取 EUR/CNY | PASS | “1欧元对人民币7.8067元”；业务日期 2026-08-07；发布时间 2026-08-07 09:25:38。 |
| T05 | Windows PowerShell Invoke-WebRequest 直连详情页 | FAIL（环境） | 报错“基础连接已经关闭: 发送时发生错误”。 |
| T06 | Windows curl.exe 经当前本地代理请求详情页 | FAIL（环境） | curl: (35) schannel: failed to receive handshake, SSL/TLS connection failed；未获取响应体。 |

T05/T06 是当前工作区原生 HTTPS/TLS 路径风险，不是 PBOC 数据不存在或字段无法确认的证据。T01-T04 已从 PBOC 官方公开页面确认双币来源和解析契约；D1-T04 必须使用实际 Java 客户端重新验证，并在可获得完整实体时计算原始 payload SHA-256。

历史 T05/T06 未保存精确命令、PowerShell/curl 版本、代理模式、退出码、重试次数和逐次时间戳，因此只能支持“曾观察到环境失败”。本次 Terra 已在同一目标 Windows 原生环境以新的 replayRunId 补齐逐次证据；代理地址、凭据等敏感信息仍只记录“未配置/系统代理/显式代理（已脱敏）”，不得写入秘密值。

| itemId | fieldContractResult | connectionResult | 精确重放证据 |
|---|---|---|---|
| FX.USD.CNY.PBOC_MID | CONFIRMED | EXTERNAL_ACCESS_BLOCKED | `d1-t02-windows-replay-20260808T204852+0800`；PowerShell/curl 均无 PBOC HTTP 实体。 |
| FX.EUR.CNY.PBOC_MID | CONFIRMED | EXTERNAL_ACCESS_BLOCKED | `d1-t02-windows-replay-20260808T204852+0800`；PowerShell/curl 均无 PBOC HTTP 实体。 |

### 1.1 d1-t02-windows-replay-20260808T204852+0800 Windows 原生重放（追加）

- 环境：Windows 10 Pro 10.0.19045（64-bit）；PowerShell 5.1.19041.2673；curl.exe 8.0.1（Schannel）；时区 China Standard Time / Asia/Shanghai；显式代理（已脱敏）。
- 请求：PowerShell 与 curl 各访问 PBOC 公告列表和实际详情 URL 一次，均 `retryCount=0`；未使用 TLS/证书绕过、登录、Cookie、令牌、验证码或反爬绕过。
- PowerShell：2026-08-08T20:53:00+08:00 和 20:53:01+08:00，均 `exitCode=1`、未获得 HTTP 状态/实体。curl：2026-08-08T20:54:24+08:00 两次，均 `exitCode=35`、`http_code=000`、未获得 PBOC HTTP 实体。
- curl 的 headers 工件仅记录 `HTTP/1.1 200 Connection established` 代理 CONNECT 协商，不能视为 PBOC 成功响应；未生成或伪造 payload SHA-256。
- 完整命令、退出码、错误全文/结构摘要与证据哈希见 `docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-native-replay-summary.md`（SHA-256 `28B125524C5708C40B226263C210207F9DC571BB51E0BA1095DCBD89A2F1BB2F`；Review元数据更新后重算）。Java 客户端为 `NOT_RUN（D1-T04）`。
- 结果只证明当前 Windows 原生网络的 `EXTERNAL_ACCESS_BLOCKED`，不构成 Day 1、Day 2 或 `AT-SRC-002` 的 `PASS`。

## 2. 原始响应样例与哈希

| 项目 | 值 |
|---|---|
| 原始页面 URL | <https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html> |
| 保存证据 | pboc-2026-08-07-response-excerpt.txt |
| 证据性质 | 官方 HTML 的字段级原文摘录；不宣称为完整 HTTP 实体。 |
| SHA-256 | 5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4 |
| 完整响应 payload SHA-256 | 未取得：当前原生 HTTPS 客户端在 TLS 握手前失败；留待 D1-T04。 |

## 3. D1-T02 判定

- D1-T02 来源与解析契约事实：CONFIRMED。双币均有可核验的 PBOC 官方公开来源、字段解释、原文样例和 SHA-256 证据。
- D1-T02：`TaskExecutionStatus=DONE`、`statusReason=CODE_REVIEW_APPROVED`。逐币种重放、核心工件哈希与秘密扫描已由技术负责人复核通过（2026-08-08 22:00:40 Asia/Shanghai）。
- Day 1 raw闭环：`NOT_RUN`。
- Day 2 AT-SRC-002：`NOT_RUN`，绝不因本记录标为`PASS`。
- 回退规则：若 D1-T04 不能通过正常公开 HTTPS 读取，则保存脱敏错误、列表/详情 URL、尝试时间、重试次数和无响应结论；不得切换为非 PBOC 来源或伪造 raw。
