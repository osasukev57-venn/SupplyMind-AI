# Terra 执行提示词：补完 D1-T02 证据

你现在只执行 SupplyMind AI 的 `D1-T02 PBOC EUR/CNY、USD/CNY数据契约与连通性验证`，不要执行 D1-T03，不要创建或修改 backend、业务 data、Provider、Spring Boot 或任何产品代码。

## 1. 必读基线

开始前完整读取：

- `docs/00-OFFICIAL-REQUIREMENTS.md`
- `docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`
- `docs/01-PROJECT-MASTER-PLAN.md`
- `docs/02-REQUIREMENT-TRACEABILITY.md`
- `docs/03-ACCEPTANCE-TEST-PLAN.md`
- `docs/04-DEVELOPMENT-TASKS.md`
- `docs/05-PROGRESS-LEDGER.md`
- `docs/06-DECISION-LOG.md`
- `docs/evidence/D1-T02/` 下全部文件

以当前docs为唯一基线，不根据旧聊天补设计。若发现新冲突，停止并报告，不自行修改冻结架构。

## 2. 当前事实与任务边界

- PBOC字段事实已确认，不要重新设计字段：USD/CNY=`6.7904`、EUR/CNY=`7.8067`，业务日`2026-08-07`，发布时间`2026-08-07 09:25:38`。
- 官方详情页：`https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html`
- 官方列表页：`https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html`
- 当前字段摘录已补齐标题、文章来源、正文和落款；期望SHA-256为`5EA4C13175E2D17773675006A8FCE7A0F8FE63EA09C7D68745C4C80EE406ABB4`。
- 本任务要补的是“可复现的Windows原生连通性证据”和“逐币种结果”，不是实现Java客户端。Java网络复测属于D1-T04，必须记为`NOT_RUN`。
- 允许最终连接结果为`EXTERNAL_ACCESS_BLOCKED`；证据完整的失败是合法调查结果，但不是Day1、Day2或AT-SRC-002通过。

## 3. 执行步骤

1. 领取任务时把D1-T02从`READY`改为`IN_PROGRESS`，同步更新`docs/04`顶部进度锚点与D1-T02任务行、`docs/05`当前快照；只改状态副本，不改其他冻结结论。
2. 为本次重放创建新的Windows文件名安全ID：`replayRunId=d1-t02-windows-replay-YYYYMMDDTHHmmss+0800`（Asia/Shanghai，不含冒号），所有新命令转录、响应和hash都引用该ID并追加保存。不得删除、改写或伪装原证据运行号`d1-t02-pboc-contract-20260808T184207+0800`、原T01-T06观察及既有不可变附件；允许在两份现有Markdown记录中追加新replay章节，并按第10步更新当前摘要、占位符和状态。
3. 在当前目标Windows原生环境记录：
   - Windows版本；
   - PowerShell版本；
   - `curl.exe --version`完整输出；
   - 时区；
   - 代理模式只能写`未配置`、`系统代理`或`显式代理（已脱敏）`，不得写代理地址、用户名、密码、Cookie或令牌；若精确命令含代理参数，证据中必须把值替换为`<REDACTED_PROXY>`，或让命令引用不落值的专用环境变量并只记录变量名；
   - 每次尝试的Asia/Shanghai时间戳。
4. 使用PowerShell和`curl.exe`分别重放列表页与详情页。必须保存脱敏后的精确命令、退出码、重试次数、重定向结果、HTTP状态、content-type、错误全文或响应结构摘要。本次是一次性D1-T02诊断例外，不是运行时调度频率：每种客户端每个URL最多一次初始请求；只有可归因于瞬时网络错误时才允许一次重试，理论总上限8个请求。禁止绕过TLS/证书校验、验证码、登录、访问控制或反爬。
5. 按以下唯一规则汇总该Windows原生环境的`connectionResult`：
   - 只有至少一个同一原生客户端完整走通“列表页HTTP 2xx并发现真实详情链接→详情页HTTP 2xx并取得完整实体→双币锚点核对成功”，两个币种才分别记`CONFIRMED`；
   - PowerShell与curl一成一败时，只要其中一个单独走通上述完整路径，汇总结果仍为`CONFIRMED`，失败客户端保留独立失败记录；
   - 仅列表成功、仅直接详情成功、两个客户端拼接后才勉强覆盖完整路径、非2xx、未取得完整实体或双币锚点任一失败，两个币种均记`EXTERNAL_ACCESS_BLOCKED`；
   - 两币共享同一公告，所以本次环境的connectionResult应相同，但仍必须写两行。
6. 如果成功取得详情响应：
   - 使用PowerShell `-OutFile`、`curl.exe --output`或等价的二进制安全流式方式直接保存完整HTTP实体原始字节，不得只保存字段摘录；
   - 禁止读取`Invoke-WebRequest.Content`后通过`Set-Content`/文本编码重新写回并声称是原始payload；响应头必须另存，不能拼到实体文件前；
   - 落盘后立即对该实体文件执行`Get-FileHash -Algorithm SHA256`或等价字节哈希；
   - 保存HTTP状态、content-type、接收时间、最终URL、字节长度和payload SHA-256；
   - 该文件只是D1-T02调查证据，不是业务raw；
   - 核对标题、正文、落款三处日期一致，并核对USD/EUR值、单位和发布时间。
7. 如果未取得HTTP响应：
   - 不得伪造HTTP状态、响应文件或payload hash；
   - 保存客户端错误全文、退出码、请求条件、重试次数和“未获得响应实体”结论；
   - 两个币种的`connectionResult`在该Windows原生环境均记录为`EXTERNAL_ACCESS_BLOCKED`。
8. 无论成功或失败，都必须在证据中各写一行：
   - `FX.USD.CNY.PBOC_MID | fieldContractResult=CONFIRMED | connectionResult=...`
   - `FX.EUR.CNY.PBOC_MID | fieldContractResult=CONFIRMED | connectionResult=...`
   两币可引用同一详情响应，但不能只写一个全局混合结论。
9. 复算`pboc-2026-08-07-response-excerpt.txt`的SHA-256，并与`.sha256`文件及上述期望值核对。若不匹配，立即停止并报告基准损坏；不得改写excerpt或`.sha256`来掩盖差异。
10. 将`PBOC-SOURCE-CAPABILITY-RECORD.md`和`PBOC-CONNECTIVITY-VALIDATION.md`中的全部`PENDING_TERRA_REPLAY`/`INCOMPLETE`替换为本次真实结果、replayRunId和证据相对路径；把证据内D1-T02状态同步为`TaskExecutionStatus=REVIEW_PENDING`、`statusReason=EVIDENCE_REPLAY_SUBMITTED`。若走通完整路径，将两币connectionResult同时改为CONFIRMED，否则保持EXTERNAL_ACCESS_BLOCKED。不得留下待办占位符或旧READY状态。

## 4. 允许修改的文件

- `docs/evidence/D1-T02/PBOC-SOURCE-CAPABILITY-RECORD.md`
- `docs/evidence/D1-T02/PBOC-CONNECTIVITY-VALIDATION.md`
- 可在`docs/evidence/D1-T02/`新增脱敏命令转录、完整响应证据和对应`.sha256`
- `docs/04-DEVELOPMENT-TASKS.md`（仅D1-T02状态）
- `docs/05-PROGRESS-LEDGER.md`（当前快照及本次执行记录）

不要修改`docs/01`、`docs/02`、`docs/03`、`docs/06`的冻结实现契约；发现问题时只报告给技术负责人。

## 5. 交付状态与汇报格式

完成上述工作和自检后：

- 把D1-T02设为`TaskExecutionStatus=REVIEW_PENDING`、`statusReason=EVIDENCE_REPLAY_SUBMITTED`，并同步`docs/04`顶部锚点/任务行和`docs/05`当前快照，不要自行设为`DONE`；
- D1-T03保持`TaskExecutionStatus=NOT_STARTED`、`blockedByTask=D1-T02`；
- AT-SRC-002保持`NOT_RUN`；
- 明确写出Java客户端=`NOT_RUN（D1-T04）`；
- 报告修改文件、每条实际命令、成功/失败次数、两个币种的最终逐行结果、证据路径和SHA-256；
- 明确声明未创建backend、业务data或产品代码。

技术负责人Code Review通过后，才会把D1-T02改为`DONE`并决定是否将D1-T03改为`READY`。
