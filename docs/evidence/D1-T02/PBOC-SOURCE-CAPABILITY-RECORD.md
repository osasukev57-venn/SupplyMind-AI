# D1-T02 PBOC 来源能力记录

> 任务：D1-T02 PBOC EUR/CNY、USD/CNY 数据契约与连通性验证  
> 证据运行号：d1-t02-pboc-contract-20260808T184207+0800  
> 记录时间：2026-08-08（Asia/Shanghai）  
> 字段契约结论：`fieldContractResult=CONFIRMED`；D1-T02 重放证据已通过技术负责人 Code Review，任务状态为`DONE`。  
> 当前目标 Windows 原生网络结论：两个币种均为 `connectionResult=EXTERNAL_ACCESS_BLOCKED`；不代表 Day 1/Day 2 PBOC 全链验收通过。

## 1. 实际确认的官方来源

| 项目 | 确认结果 |
|---|---|
| 官方站点 | 中国人民银行官网（www.pbc.gov.cn） |
| 公告列表 | <https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html> |
| 验证公告 | <https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html> |
| 公告类型 | 人民币汇率中间价公告，text/html |
| 公告表述 | 页面正文明确为“中国人民银行授权中国外汇交易中心公布”。 |
| 补充权威说明 | 中国外汇交易中心公开说明：<https://www.chinamoney.com.cn/chinese/bkccpr/index.html?tab=2>，说明交易中心根据中国人民银行授权每日计算和发布人民币对美元等主要币种汇率中间价。 |
| 来源类型 | OfficialWebDataProvider / official_web |
| 实际来源名 | 中国人民银行官网（授权中国外汇交易中心公布） |
| 访问方式 | 匿名 HTTPS 公开 HTML；验证时未使用登录、Cookie、令牌、验证码或规避机制。 |

## 2. 合法访问边界与能力结论

1. 当前可确认的是公开可浏览的官方 HTML 公告，不是已发现并获授权的公开 JSON/API。
2. 自动获取草案只能采用正常、低频的公告列表发现与详情页读取；不得猜测未公开接口、绕过访问控制、批量抓取受限内容或复用认证信息。
3. apiLicense=NOT_FOUND、redistributionPermission=UNVERIFIED。对外商业再分发、批量历史抓取或提高频率前，必须另行完成合规确认。
4. 建议的后续读取路径为：GET 公告列表 → 按业务日匹配公告标题并读取其实际链接 → GET 对应详情页。文章 ID 为不透明值，禁止按日期拼接或猜测详情 URL。
5. 建议运行时初始频率：每个公告业务日最多一次列表发现和一次详情读取；响应失败时保留诊断并由后续任务制定有界重试策略。D1-T02一次性双客户端重放属于有总上限的诊断例外，不得复制为运行时频率。

## 3. 本次样例与非工作日判断

- 当前日期为 2026-08-08；公告列表最新条目为 2026-08-07。该日为最近公告业务日，不能用自然日 2026-08-08 推断或补造汇率。
- 同一份 2026-08-07 公告同时发布 USD/CNY 与 EUR/CNY，故两条 Series 可共享同一 sourceUrl 与原始响应证据，但必须保留各自独立的 itemId、原始数值字符串和匹配锚点。
- 精确字段摘录见 pboc-2026-08-07-response-excerpt.txt；其 SHA-256 见同目录 .sha256 文件。该文件是从公开 HTML 保存的字段级原文摘录，不是完整 HTTP 实体；完整响应字节及其 payload hash 留待 D1-T04 的实际客户端写入链路生成。

### 3.1 逐币种结果（D1-T02 必填）

`fieldContractResult` 与 `connectionResult` 是两个不同结论：前者回答字段事实是否已确认，后者只回答指定客户端/网络环境是否实际连通。

| itemId | fieldContractResult | connectionResult（Windows 原生环境） | 共享来源响应 | 任务证据完整性 |
|---|---|---|---|---|
| FX.USD.CNY.PBOC_MID | CONFIRMED | EXTERNAL_ACCESS_BLOCKED | 2026-08-07 PBOC 官方公告 | 已完成：`d1-t02-windows-replay-20260808T204852+0800`；PowerShell/curl 均未获得 PBOC HTTP 实体。见本节 3.2。 |
| FX.EUR.CNY.PBOC_MID | CONFIRMED | EXTERNAL_ACCESS_BLOCKED | 2026-08-07 PBOC 官方公告 | 已完成：`d1-t02-windows-replay-20260808T204852+0800`；PowerShell/curl 均未获得 PBOC HTTP 实体。见本节 3.2。 |

两个 item 可以共享一次外部获取的 `acquisitionId` 和相同完整响应字节/hash，但每个 item 必须拥有独立 `runId`、`rawRef`、原始数值字符串、匹配锚点和 Lifecycle timeline。

### 3.2 Windows 原生重放追加结果

- `replayRunId=d1-t02-windows-replay-20260808T204852+0800`；Windows PowerShell 5.1 与 curl.exe 8.0.1 各对列表页和详情页执行一次请求，均 `retryCount=0`。
- PowerShell 两次均 `exitCode=1`，curl 两次均 `exitCode=35`、`http_code=000`；curl 保存的 `HTTP/1.1 200 Connection established` 仅为代理 CONNECT 协商，不是 PBOC HTTP 响应。
- 两个币种均分别记录为 `fieldContractResult=CONFIRMED`、`connectionResult=EXTERNAL_ACCESS_BLOCKED`。Java 客户端为 `NOT_RUN（D1-T04）`；未取得完整实体，故无本次 payload SHA-256。
- 完整命令、版本、时区、脱敏代理模式、逐次时间、退出码与错误证据：`docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-native-replay-summary.md`（SHA-256 `28B125524C5708C40B226263C210207F9DC571BB51E0BA1095DCBD89A2F1BB2F`；Review元数据更新后重算）。
- 本次结果仅完成 D1-T02 调查证据，不构成 Day 1、Day 2 或 `AT-SRC-002` 通过。

## 4. 仍需后续任务验证的事项

- D1-T02 当前为 `TaskExecutionStatus=DONE`、`statusReason=CODE_REVIEW_APPROVED`：本次 Windows 原生重放、逐币种结论、核心工件哈希与秘密扫描已由技术负责人复核通过（2026-08-08 22:00:40 Asia/Shanghai）。
- D1-T04 必须在 Java/Spring Boot 实际运行网络中复测 HTTPS，并保存完整原始字节、HTTP 状态、接收时间、payload SHA-256 与脱敏失败诊断。
- D2-T01 负责基础校验；在其完成前，任何样例均不得被标为 VERIFIED 或进入每日加工、聚合、面板、预警或 Agent。
- AT-SRC-002仍为`NOT_RUN`；本记录不构成Day 1或Day 2退出证据。
