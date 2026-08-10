# SupplyMind AI 跨窗口进度台账

> 文档性质：跨 Codex 窗口的唯一进度事实源  
> 当前阶段：Day 3（D1-T01～D1-T05、D2-T01～D2-T05、D3-T01、D3-T02 均`DONE`；Day 1=`COMPLETE`、Day 2=`COMPLETE`、AT-SRC-002=`PASS`；DEC-050～056 生效；D3-T03=`NOT_STARTED`/`READY`，未开始；Day 3 阶段 Gate 未执行）  
> 更新规则：每个开发任务结束前必须更新本文件；不得只在聊天中报告进度。

## 1. 使用规则

1. 新窗口开始工作前，依次读取：
   - `docs/00-OFFICIAL-REQUIREMENTS.md`
   - `docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`
   - `docs/01-PROJECT-MASTER-PLAN.md`
   - `docs/04-DEVELOPMENT-TASKS.md`
   - 本文件
2. 一次只领取一个处于 `READY` 状态且依赖已完成的任务。
3. 开始任务时更新“当前快照”；结束时填写一条“任务执行记录”。
4. 只有完成任务文档规定的调查/实现、任务级测试并保存证据后，`TaskExecutionStatus` 才可以改为 `DONE`；`DONE` 不等于任何正式验收 `PASS`。
5. 任务失败时记录实际结果、回退动作和阻塞项，不得把部分完成标记为`DONE`。D1-T02必须逐币种记录字段事实、环境限定的connectionResult和可复现重放证据；外部失败证据可完成调查任务，但AT-SRC-002必须单独记录为NOT_RUN、BLOCKED、FAIL或PASS。
6. 指定商业源自动能力不可用时，按“合法公开/授权自动 → 同类免费公开 → Manual”受控降级；它不再是整体P0阻塞，且不得绕过登录、验证码、会员或反爬。
7. Day 8 功能冻结后，只允许修复 P0 验收缺陷，不得新增业务功能。
8. 如果代码或任务与官方需求、H01-H09、冻结决策冲突，立即停止该任务并登记偏离。

## 2. TaskExecutionStatus（任务执行状态）

| 状态 | 含义 |
|---|---|
| `NOT_STARTED` | 尚未开始 |
| `READY` | 依赖已完成，可以领取 |
| `IN_PROGRESS` | 当前正在执行 |
| `REVIEW_PENDING` | 执行人已提交约定产物和自测，等待技术负责人Code Review；依赖任务仍不得视为完成 |
| `BLOCKED_EXTERNAL` | 被外部环境或业务口径阻塞，任务自身无法完成 |
| `BLOCKED_TECHNICAL` | 被可复现的技术问题阻塞 |
| `FAILED` | 已执行但未达到本任务Definition of Done，且已回退 |
| `DONE` | 本任务约定的调查/实现、任务级测试和证据归档已完成；**不等于AT PASS** |
| `DEFERRED_P1` | 延后到P1 |
| `DEFERRED_P2` | 延后到P2 |

## 2.1 AcceptanceStatus（验收用例状态）

| 状态 | 含义 |
|---|---|
| `NOT_RUN` | 尚未执行独立AT用例 |
| `PASS` | AT全部预期与证据均满足 |
| `FAIL` | 已执行AT但至少一项预期不满足 |
| `BLOCKED` | 外部访问、业务口径或环境使AT无法合法执行；不是PASS |
| `N/A_APPROVED_FALLBACK` | 仅用于指定商业材料源自动能力；不等于该自动能力PASS，替代路线仍须单独PASS |

D1-T02即使为`DONE`，若只有外部失败证据，AT-SRC-002仍只能是`NOT_RUN`、`BLOCKED`或`FAIL`，绝不能为`PASS`。

## 2.2 数据生命周期与追踪命名空间

业务记录另用 `ProcessingStage`（RECEIVED/PARSED/VALIDATED/PUBLISHED）与 `ValidationStatus`（PENDING/VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT）双字段；只有PUBLISHED+VERIFIED类组合进入业务读模型。完整映射以`docs/01-PROJECT-MASTER-PLAN.md`第8.2节为准。`TraceabilityStatus`和`OPEN_EXTERNAL_NON_BLOCKING`等外部状态以`docs/02-REQUIREMENT-TRACEABILITY.md`为准。

## 3. 当前快照

| 字段 | 当前值 |
|---|---|
| 当前开发日 | Day 2 |
| 当前任务编号 | D3-T02 材料三层路由与AuthorizedApi能力（`TaskExecutionStatus=DONE`，`statusReason=R1_REVIEW_PASS_20260810`；implementation commit=`ee7cbc7`、Review Level=R1+、R1+ Review=`PASS`、BLOCKER/MAJOR=无、BUSINESS_DECISION_REQUIRED=无、R2_REQUIRED=NO）。 |
| 当前任务状态 | D1-T01～D1-T05、D2-T01～D2-T05、D3-T01、D3-T02 均为`TaskExecutionStatus=DONE`；Day 1=`COMPLETE`、Day 2=`COMPLETE`、AT-SRC-002=`PASS`、DEC-050～056 生效（DEC-056 implementation=`PASS`）。D3-T03=`NOT_STARTED`/`READY`（冻结依赖 D3-T01+D3-T02 均 DONE；EXT-10=`OPEN_EXTERNAL_NON_BLOCKING` 不阻断）。 |
| 编码前基线对齐 | `v1.4 FROZEN`：状态命名空间、唯一目录、RawReceiptV1、LifecycleTimelineV1/CandidateV1、QuarantineProjectionV1、完整config/history、inputRefs/sourceFingerprint、显式计算上下文、data+manifest/DirtyMarkerV1原子提交与自恢复、日期路由及BigDecimal契约已冻结（DEC-041至DEC-049、C27至C34）；DEC-050（PBOC基础校验v1）、DEC-051（业务读模型stale）、DEC-052（daily.updatedAt确定性语义）、DEC-053（arithmetic-mean-v1接受版本化默认）、DEC-054（weekday-asia-shanghai-v1接受版本化默认）、DEC-055（aggregate.calculatedAt=max(daily.updatedAt)确定性语义）、DEC-056（raw-first acquisition boundary + 业务键幂等 + AT runner 证据保存）已生效 |
| 已完成任务 | BASELINE-DOCS；D1-T01～D1-T05（Day 1 全部DONE，Day 1 Gate=PASS）；D2-T01（Sol最终Review PASS）；D2-T02（Sol最终固定快照Review PASS）；D2-T03（Implementation Review PASS + EXT Gate PASS，commit=607e859）；D2-T04（1ac8233→1178307，Sol/Second-party Final Delta Review 双PASS，commit=1178307）；D2-T05（24d24b6→2b7d2f4→a482087→79680ec，Sol+Second-party 最终双PASS，commit=79680ec）。 |
| 正在进行任务 | 无。 |
| 阻塞项 | 无（D3-T02 R1+ Review=`PASS` 已收口 `DONE`）。D3-T03 可领取（READY）但未开始。EXT-04=`OPEN_EXTERNAL_NON_BLOCKING`、EXT-10=`OPEN_EXTERNAL_NON_BLOCKING`（均不阻塞 P0/领取）。 |
| 最近验收结果 | D2-T01～D2-T05=`DONE`；AT-SRC-002=`PASS`；D3-T01（86c8e3f）R1 Review=`PASS` 收口 `DONE`；D3-T02（ee7cbc7）R1+ Review=`PASS`（BLOCKER/MAJOR=无、R2_REQUIRED=NO）收口 `DONE`。 |
| 新增风险 | PBOC页面结构或字段漂移；Windows PowerShell/curl代理TLS失败（Java 17路径成功）；免费源合法性/字段漂移与规格不可比；Manual误录漏录；来源冒充。D2-T03 计算/日历口径已接受版本化默认（DEC-053/054）；weekday-asia-shanghai-v1 不构成完整法定节假日/调休/停报/特殊交易日日历，未来以新 calendarVersion 升级。EXT-04/EXT-10/EXT-11 外部确认项（OPEN_EXTERNAL_NON_BLOCKING）。 |
| 下一任务 | D3-T03 FreePublicDataProvider与真实来源追踪（`TaskExecutionStatus=NOT_STARTED`、`readyState=READY`：冻结依赖 D3-T01+D3-T02 均 DONE；EXT-10=`OPEN_EXTERNAL_NON_BLOCKING` 不阻断，DoD 允许 NO_APPROVED_SOURCE 调查结论仍 DONE）；可领取但本窗口不实施。 |
| 最近一次可运行版本 | backend：Java 17 + Spring Boot 3.3.6；全套 200 项测试（40 classes，0 failures，0 errors，7 skipped 门禁）通过（D3-T02 全量回归结果，前序已固定）。 |
| 最近一次Git提交 | 本轮将提交 `docs: close D3-T02 after R1 review`；前序 checkpoint=`ee7cbc7`（D3-T02 implementation，R1+ Review=`PASS`）。 |
| 是否偏离计划 | 否 |
| 最后更新人/窗口 | OpenCode实施工程师窗口，D3-T02 R0 最终状态收口：`DONE`（ee7cbc7，第二方 R1+ Review=`PASS`，BLOCKER/MAJOR=无，R2_REQUIRED=NO；任务级 PASS，Day 3 阶段 Gate 待收尾统一执行）；D3-T03=`NOT_STARTED`/`READY`（冻结依赖 D3-T01+D3-T02 均 DONE，EXT-10=`OPEN_EXTERNAL_NON_BLOCKING` 不阻断）；Day 3=`NOT_COMPLETE`；未开始 D3-T03，未修改生产代码/测试/Evidence，未调用 Sol。 |
| 最后更新时间 | 2026-08-10（Asia/Shanghai） |

## 4. 外部阻塞快照

详细定义以 `docs/02-REQUIREMENT-TRACEABILITY.md` 的“外部待确认事项”表为准。

| 编号 | 摘要 | 当前状态 | 影响 | 临时处理 |
|---|---|---|---|---|
| EXT-01 | PBOC双币字段、单位与报价方向 | `EXTERNAL_CONFIRMED`（字段事实；非数据ValidationStatus或AT PASS） | PBOC硬门 | D1-T02重放证据已通过Review；D1-T04 Java 17已保存真实双币raw，D2仍须完成全链验收 |
| EXT-02 | 各实际材料来源的规格口径 | 待确认 | 材料值可比性 | 按实际来源×品种配置；不跨规格混算 |
| EXT-03 | 每日均值业务定义 | `ACCEPTED_VERSIONED_DEFAULT`（via DEC-053；arithmetic-mean-v1） | H01、H02 | DEC-053 已生效；sum完整精度、avg仅最终除法舍入、displayScale不回写、missing不补0 |
| EXT-04 | 指定商业源自动采集能力 | `OPEN_EXTERNAL_NON_BLOCKING` | 对应自动适配器声明 | 合法自动可用则接入，否则记录证据并转FreePublic→Manual |
| EXT-05 | 历史回填范围 | 待确认 | H08 | 区间配置化；Manual/LocalImport同样经过发布门禁 |
| EXT-06 | 节假日/未发布日 | `ACCEPTED_VERSIONED_DEFAULT`（via DEC-054；weekday-asia-shanghai-v1，当前版本口径） | 完整率、均值 | DEC-054 已生效；缺失不补0，不代表完整法定节假日日历，未来以新calendarVersion演进 |
| EXT-07 | 预警阈值 | 待确认 | 预警验收 | 使用显式测试规则，不冒充最终业务阈值 |
| EXT-08 | 动态调价公式 | 待确认 | 成本影响、建议 | P0仅规则预警和非约束建议，不自动调价 |
| EXT-09 | “跨卷”含义 | 待确认 | H06 | 临时解释为同一data根下多个轮转文件 |
| EXT-10 | 免费材料信源及映射 | `OPEN_EXTERNAL_NON_BLOCKING` | FreePublic能力 | 保存URL/许可/频率/字段映射；未认可时Manual保底 |
| EXT-11 | Manual操作与复核责任 | `OPEN_EXTERNAL_NON_BLOCKING` | 审计深度 | P0记录operatorRef、实际来源、时间、版本；复核深度配置化 |

## 5. 任务执行记录

### `D1-T01 项目方实施补充基线与PBOC优先级冻结`

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（文档基线） |
| 开始时间 | 2026-08-08，本轮文档窗口 |
| 结束时间 | 2026-08-08，本轮文档窗口 |
| 执行窗口/执行人 | Codex |
| 开始状态 | `READY` |
| 结束状态 | `DONE` |
| 对应需求 | SUP-01至SUP-08、F02-F07、F12-F14、C17-C19、C25-C26 |
| 已完成内容 | 独立保存项目方实施补充说明；更新总计划、追踪矩阵、验收计划、10天任务、决策日志、风险与外部事项 |
| 创建/修改文件 | 新增`docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`；修改`docs/01`至`docs/06`规划文档 |
| 实际测试命令或步骤 | 原文逐字核对；旧冲突短语扫描；任务/验收ID唯一性与引用检查；官方原需求文件完整性检查 |
| 测试结果 | 任务级文档检查通过（非正式AT）；补充说明与原需求正文分离，PBOC优先级和材料三层降级已贯穿文档 |
| 验收证据路径 | `docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`及`docs/01`至`docs/06` |
| 失败与回退 | Windows补丁助手因沙箱权限锁失败；改用UTF-8统一差异补丁，失败尝试未写入内容 |
| 新增风险 | RISK-01至RISK-06 |
| 阻塞项变化 | EXT-04改为指定源自动能力风险；新增EXT-10、EXT-11；三者均不阻塞整体P0 |
| 最近可运行版本 | 无；本任务只更新文档，未创建业务代码 |
| Git提交 | 无；当前目录尚未初始化Git仓库 |
| 是否偏离计划 | 否；依据项目方正式实施补充说明更新基线 |
| 下一建议任务 | D1-T02 PBOC EUR/CNY、USD/CNY数据契约与连通性验证 |

### D1-T02 PBOC EUR/CNY、USD/CNY数据契约与连通性验证（初始调查记录）

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-08 18:42:07 Asia/Shanghai（证据时间锚点） |
| 结束时间 | 2026-08-08，本窗口 |
| 执行窗口/执行人 | Codex |
| 开始状态 | READY |
| 结束状态 | 当时记为DONE；后续Code Review发现DoD证据缺口，当前任务状态已重开为READY |
| 对应需求 | SUP-01、SUP-02、F02、F03、EXT-01 |
| 已完成内容 | 确认 PBOC 公告列表→实际详情页的公开 HTML 路径；确认双币字段、方向、单位、业务日、发布时间及解析契约；分离 ProcessingStage 与 ValidationStatus；D1-T02仅提交目录映射调查记录，后续编码前基线已冻结唯一物理目录解释。 |
| 创建/修改文件 | 新增 docs/evidence/D1-T02/ 下五份证据文件；更新 docs/01、docs/02、docs/04、本台账及 docs/06。未创建 backend 或业务 data 目录。 |
| 实际测试命令或步骤 | 读取 PBOC 公告列表和 2026-08-07 官方详情页；分别定位 USD/EUR 字段；保存字段级原文摘录并计算 SHA-256；以 PowerShell 和 curl 进行本机 HTTPS 诊断。 |
| 测试结果 | PBOC官方页面读取与双币字段解析已确认（调查产物，非AT PASS）；PowerShell/curl本机TLS握手失败记录为环境风险；未将Day1 raw闭环或AT-SRC-002标为PASS。 |
| 验收证据路径 | docs/evidence/D1-T02/PBOC-SOURCE-CAPABILITY-RECORD.md、PBOC-SERIES-CONTRACT-DRAFT.md、PBOC-CONNECTIVITY-VALIDATION.md 及响应摘录/哈希文件。 |
| 失败与回退 | 未使用非 PBOC 来源、未绕过代理/登录/验证码/反爬；本机 TLS 失败时仅保留脱敏诊断，不生成业务 raw 或伪造值。 |
| 新增风险 | RISK-07：当前工作区原生 HTTPS/TLS 路径无法与 PBOC 完成握手。 |
| 阻塞项变化 | EXT-01 字段契约已确认；D1-T04 前仍须解决或验证 Java/目标网络 HTTPS。EXT-02至EXT-11保持原状态。 |
| 最近可运行版本 | 无；本任务仅生成调查文档，未创建业务代码。 |
| Git提交 | 无；当前目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；严格限定为 D1-T02，未进入 D1-T03。 |
| 下一建议任务 | 已被后续Review替代；当前下一任务是D1-T02证据补完。 |

### D1-T02 Code Review与D1-T03编码契约对齐

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（Review/文档对齐，不是业务开发） |
| 开始时间 | 2026-08-08，本窗口 |
| 结束时间 | 2026-08-08，本窗口 |
| 执行窗口/执行人 | Codex技术负责人窗口 |
| 开始状态 | D1-T02=`DONE`（待审） |
| 结束状态 | D1-T02：`TaskExecutionStatus=READY`、`statusReason=REVIEW_REOPENED`；D1-T03：`TaskExecutionStatus=NOT_STARTED`、`blockedByTask=D1-T02` |
| 对应需求 | SUP-01、SUP-02、H02-H04、F03、F07-F09、C27-C33、EXT-01 |
| 已完成内容 | 独立核对PBOC官方字段事实与摘录SHA；发现逐币种connectionResult和失败重放字段缺失；统一聚合item-first路径、完整状态迁移、Raw/Lifecycle/Candidate/Quarantine边界、多item响应基数、完整config与币种映射、日期路由、inputRefs/sourceFingerprint、data+manifest事务、BigDecimal与无数据库口径。 |
| 创建/修改文件 | 修改docs/01至06及docs/evidence/D1-T02；新增Terra D1-T02补完brief；未创建backend或业务data。 |
| 实际测试命令或步骤 | 官方来源/证据复核；摘录补落款并重算SHA-256；跨文档关键词、任务ID、路径、状态与格式审计。 |
| 测试结果 | 字段事实有效；D1-T02任务级Review不通过并重开。D1-T03实现契约已唯一化，但依赖未满足，不得领取。 |
| 验收证据路径 | docs/evidence/D1-T02/；docs/01至06。正式AT仍为NOT_RUN。 |
| 失败与回退 | 未伪造缺失命令或网络成功结果；保留Windows原生EXTERNAL_ACCESS_BLOCKED结论，交Terra重放。 |
| 新增风险 | RISK-09：文档歧义会造成存储层返工；已通过DEC-044至DEC-047和C30-C33缓解，最终冻结前仍需全量复扫。 |
| 阻塞项变化 | D1-T02成为唯一READY任务；D1-T03显式阻塞。 |
| 最近可运行版本 | 无；仍未创建业务代码。 |
| Git提交 | 无；当前目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；属于技术负责人Review与编码前消歧。 |
| 下一建议任务 | Terra执行D1-T02证据补完；完成后交回本窗口Review。 |

### D1-T02 Windows 原生重放证据补完（Terra 提交技术负责人 Code Review）

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-08 20:48:52（Asia/Shanghai） |
| 结束时间 | 2026-08-08 21:16:30（Asia/Shanghai） |
| 执行窗口/执行人 | Terra / Codex |
| 开始状态 | `READY` → `IN_PROGRESS` |
| 结束状态 | `REVIEW_PENDING`；`statusReason=EVIDENCE_REPLAY_SUBMITTED`。执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-01、SUP-02、F02、F03、EXT-01 |
| 已完成内容 | 以新的`d1-t02-windows-replay-20260808T204852+0800`追加 Windows 原生 PowerShell/curl 重放证据；逐币种记录字段契约与连接结论；复核字段摘录 SHA-256。 |
| 创建/修改文件 | 新增`docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-*`重放工件及哈希；修改`docs/evidence/D1-T02/PBOC-SOURCE-CAPABILITY-RECORD.md`、`PBOC-CONNECTIVITY-VALIDATION.md`、`docs/04-DEVELOPMENT-TASKS.md`和本台账。未创建backend、业务data、Provider、Spring Boot或产品代码。 |
| 实际测试命令或步骤 | Windows PowerShell 5.1 与 curl.exe 8.0.1 各对 PBOC 公告列表和实际详情 URL 请求一次；每次`retryCount=0`；核对命令、版本、时区、脱敏代理模式、退出码、重定向、HTTP/Content-Type及错误/结构摘要；复核字段摘录 SHA-256。 |
| 测试结果 | PowerShell 列表/详情均`exitCode=1`且未获得 HTTP 响应；curl 列表/详情均`exitCode=35`、`http_code=000`且未获得 PBOC HTTP 实体。curl 的`HTTP/1.1 200 Connection established`仅为代理 CONNECT 协商。USD/CNY、EUR/CNY 均为`fieldContractResult=CONFIRMED`、`connectionResult=EXTERNAL_ACCESS_BLOCKED`。Java 客户端`NOT_RUN（D1-T04）`。 |
| 验收证据路径 | `docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-native-replay-summary.md`（Review元数据更新后SHA-256 `28B125524C5708C40B226263C210207F9DC571BB51E0BA1095DCBD89A2F1BB2F`）；同 runId 的 PowerShell/curl 命令、错误/结构摘要、headers 和 `.sha256` 文件。字段摘录哈希复核为 PASS。 |
| 失败与回退 | 未绕过 TLS、证书、登录、验证码、会员或反爬；未改用非 PBOC 来源；未生成或伪造响应实体、payload hash、业务 raw 或验收 PASS。 |
| 新增风险 | 当前 Windows 原生 HTTPS/TLS 经脱敏显式代理无法完成 PBOC TLS 握手；D1-T04仍须在 Java/目标网络复测。 |
| 阻塞项变化 | D1-T03继续`NOT_STARTED`、`blockedByTask=D1-T02`，等待技术负责人 Review；Day 1 raw 闭环及 AT-SRC-002均保持`NOT_RUN`。 |
| 最近可运行版本 | 无；本任务未创建业务代码。 |
| Git提交 | 无；当前目录尚未初始化 Git 仓库。 |
| 是否偏离计划 | 否；严格只执行 D1-T02。 |
| 下一建议任务 | 技术负责人对 D1-T02 本次重放证据执行 Code Review；仅 Review 通过并将 D1-T02 改为`DONE`后，D1-T03才可由技术负责人改为`READY`。 |

### D1-T02 Code Review通过与v1.4编码前基线冻结

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（技术负责人Review/文档冻结，不是业务开发） |
| 开始时间 | 2026-08-08 21:16:30（Asia/Shanghai） |
| 结束时间 | 2026-08-08 22:10:41（Asia/Shanghai） |
| 执行窗口/执行人 | Codex技术负责人窗口 |
| 开始状态 | D1-T02=`REVIEW_PENDING`；D1-T03=`NOT_STARTED`且blockedByTask=D1-T02 |
| 结束状态 | D1-T02=`DONE`、statusReason=`CODE_REVIEW_APPROVED`；D1-T03=`READY` |
| 对应需求 | SUP-01、SUP-02、F02、F03、C27-C34、EXT-01 |
| 已完成内容 | 复核双币逐行fieldContractResult/connectionResult、四次Windows原生请求、代理CONNECT判定、客户端/时间/错误附件、字段摘录和核心SHA-256；完成01-06跨文档schema、路径、配置history、生命周期、计算上下文、DirtyMarkerV1自恢复、任务/AT/需求/依赖一致性复扫；冻结v1.4编码前基线。 |
| 创建/修改文件 | 仅修改docs/01-06规范/状态/台账及D1-T02 evidence状态与Review元数据；未创建或修改backend、业务data、Provider或产品代码。 |
| 实际测试命令或步骤 | 复算已声明SHA-256；扫描秘密/占位符/动态状态；核对57个Task、39个AT、66个Requirement及110条显式依赖；检查DirtyMarkerV1 canonical/tmp/bak崩溃窗口和LifecycleTimeline schema v1 PUBLISHED recordVersion=4的唯一性。 |
| 测试结果 | D1-T02无P0/P1，Code Review=APPROVED；四个核心声明哈希匹配；v1.4最终引用扫描通过。底层六个诊断附件未各自配置sidecar hash及curl旧代码围栏属于P2，不阻D1-T02 DoD，后续如强化证据可补统一artifact manifest。 |
| 验收边界 | 本记录只完成任务Review和编码前契约冻结；Day 1 raw、Java真实PBOC获取、Day 2闭环和AT-SRC-002仍为NOT_RUN，不得写PASS。 |
| 阻塞项变化 | 解除D1-T03的D1-T02任务依赖并改为READY；当前Windows HTTPS/TLS的EXTERNAL_ACCESS_BLOCKED继续由D1-T04在Java/目标网络复测。 |
| 最近可运行版本 | 无；仍未创建业务代码。 |
| Git提交 | 无；当前目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；属于任务协议规定的技术负责人Review与冻结。 |
| 下一建议任务 | Terra领取并只执行D1-T03；完成后改为REVIEW_PENDING并交回技术负责人Review。 |

### D1-T03 最小Spring Boot、data/raw与BigDecimal文件基础

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-08 22:25:01（Asia/Shanghai） |
| 结束时间 | 2026-08-09 00:06:25（Asia/Shanghai） |
| 执行窗口/执行人 | Codex 实现工程师窗口 |
| 开始状态 | D1-T03：`READY` → `IN_PROGRESS` |
| 结束状态 | D1-T03：`REVIEW_PENDING`；`statusReason=SELF_TEST_PASSED_SUBMITTED_FOR_CODE_REVIEW`。执行工程师无权改为`DONE`。 |
| 对应需求 | H02-H04、SUP-02、F07、C01、C02、C06-C10、C15、C16、C27-C34；实现契约为总计划第8.3至9节与AT-FILE-000。 |
| 已完成内容 | 建立 Java 17 单 Spring Boot 模块化单体、唯一dataRoot、冻结目录/路径校验、RawReceiptV1、LifecycleTimelineV1/CandidateV1、QuarantineProjectionV1、active config与字节一致history、ManifestV1、DirtyMarkerV1 canonical/tmp/bak恢复、daily/aggregate确定性codec和BigDecimal字符串契约；未实现Provider、真实PBOC采集或D2业务计算。 |
| 创建/修改文件 | `backend/` Maven/Spring Boot/模型/codec/storage/测试；`docs/data-dictionary/FILE-SCHEMA-V1.md`、`docs/data-dictionary/CALCULATION-RULES.md`；`backend/src/test/resources/contracts/v1/`明确标记的test/contract fixtures；本台账与任务状态锚点。 |
| 实际测试命令或步骤 | 在`D:\Dev\SDK\Java\jdk17`下执行`mvnw.cmd -q -DskipTests compile`、`mvnw.cmd -q test`、5个D1-T03/AT-FILE-000聚焦验收类、`mvnw.cmd -q dependency:tree`；覆盖启动、唯一临时dataRoot、路径/中文路径、双币synthetic fixture、raw/timeline、config/history、DirtyMarker崩溃恢复、manifest、CSV/BigDecimal和黄金文件。 |
| 测试结果 | Java 17.0.19 编译通过；全量54项测试，0 failures、0 errors、0 skipped；5个聚焦验收类通过并验证 Spring Boot 3.3.6 启动；依赖树未发现JDBC/JPA/R2DBC/MyBatis、MySQL/PostgreSQL/SQLite/H2、Redis/Mongo、迁移工具或Docker依赖。此为任务自测，不改变任何独立业务验收状态。 |
| 验收证据路径 | `backend/src/test/java/com/supplymind/foundation/acceptance/`；`backend/src/test/java/com/supplymind/foundation/storage/`；`backend/src/test/resources/contracts/v1/`；`docs/data-dictionary/`；本次Maven Surefire报告位于`backend/target/surefire-reports/`。 |
| 失败与回退 | 开发中发现并修正黄金CSV规范化、Manifest派生字段、写入不变量和DirtyMarker恢复窗口；未产生真实PBOC响应、业务raw、业务data目录或虚假验收PASS。 |
| 新增风险 | D1-T04仍需在Java/目标网络复测当前Windows PBOC HTTPS/TLS问题；本任务等待技术负责人 Code Review，未获批准前不得放行后续任务。 |
| 阻塞项变化 | D1-T03从主动实现转为Review门禁；D1-T04保持`NOT_STARTED`且`blockedByTask=D1-T03`；AT-SRC-002保持`NOT_RUN`，Day 1/Day 2真实PBOC验收均未声明通过。 |
| 最近可运行版本 | `backend`：Java 17 + Spring Boot 3.3.6，完整自测通过；运行测试未留下`data/`或`backend/data/`产品目录。 |
| Git提交 | 无；当前工作目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；严格仅实施D1-T03，未引入数据库、数据库驱动、Docker、第二dataRoot、Provider或D2计算。 |
| 下一建议任务 | 技术负责人执行D1-T03 Code Review；仅Review通过后可将D1-T03改为`DONE`并由技术负责人放行D1-T04。 |
### D1-T03 Review Fix：AT-FILE-000 独立验收证据补齐

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-09（本窗口收到 Sol `CHANGES_REQUESTED` 后；开始时刻未单独采样，Asia/Shanghai） |
| 结束时间 | 2026-08-09 16:41:02（Asia/Shanghai） |
| 执行窗口/执行人 | Codex 实现工程师窗口 |
| 开始状态 | D1-T03：`REVIEW_PENDING`；Sol Review=`CHANGES_REQUESTED`，唯一阻断为 AT-FILE-000 验收覆盖不足。 |
| 结束状态 | D1-T03：`REVIEW_PENDING`；`statusReason=REVIEW_FIX_AT_FILE_000_EVIDENCE_AND_MINIMAL_RECOVERY_FIX_PASSED_AWAITING_SOL_RE_REVIEW`。不得自行改为`DONE`。 |
| 对应需求 | AT-FILE-000步骤1–9；H02-H04、PUB-01、PER-01；D1-T03 Definition of Done。 |
| 已完成内容 | 建立AT-FILE-000步骤1–9逐项映射并补齐独立格式黄金、双币双 raw/timeline 实落盘、raw/quarantine/conflict CREATE_NEW、手工 Manifest 篡改、dataRoot/ATOMIC_MOVE fail-fast、DirtyMarker歧义候选、raw tmp/缺manifest恢复，以及配置history/active四个物理文件逐窗口恢复验收证据。 |
| 创建/修改文件 | `docs/evidence/D1-T03/AT-FILE-000-REVIEW-FIX-MAPPING.md`；4个新增 acceptance 测试；`FoundationStartupAcceptanceTest` 测试断言；`contracts/v1/review-fix/`固定黄金资源；两个双币 lifecycle fixture；`AtomicFileRecovery`最小配置恢复修复；本台账与D1-T03状态原因。 |
| 修改的生产代码 | `backend/src/main/java/com/supplymind/foundation/storage/AtomicFileRecovery.java`：新增受DirtyMarker约束的 CONFIG_ACTIVATION 恢复分支。仅当history已达到`MANIFEST_COMMITTED`、history/manifest/hash均可验证且history/active预期hash相同时，才从逐字节相同的history快照确定性完成缺失active数据；否则继续fail closed。新测试证明原实现无法在history已提交、active未开始的窗口完成或回退，违反总计划8.5.5。 |
| 实际测试命令或步骤 | Java 17.0.19：9个聚焦 acceptance 类定向运行；全部 `com.supplymind.foundation` 测试；一次最终 `mvnw.cmd -q test` backend 全量回归；`mvnw.cmd -q dependency:tree` 并扫描禁用依赖。 |
| 测试结果 | 聚焦 D1-T03/AT-FILE-000 验收集 PASS；D1-T03相关测试集 PASS；backend全量66项测试，0 failures、0 errors、0 skipped；Spring Boot 3.3.6 启动通过；依赖树无数据库、迁移工具或Docker命中；测试后未留下`data/`或`backend/data/`产品目录。 |
| 验收证据路径 | `docs/evidence/D1-T03/AT-FILE-000-REVIEW-FIX-MAPPING.md`；`backend/src/test/java/com/supplymind/foundation/acceptance/IndependentFormatContractAcceptanceTest.java`；`AtFile000DualArtifactImmutabilityAcceptanceTest.java`；`AtFile000RecoveryManifestRootAcceptanceTest.java`；`AtFile000ConfigAndRawWindowAcceptanceTest.java`；`backend/target/surefire-reports/`；`backend/target/d1-t03-review-fix-dependency-tree.txt`。 |
| 独立黄金说明 | 格式合同不使用生产 `JsonV1Codec`/`CsvV1Codec`/`ManifestFactory`/`CanonicalJsonV1` 生成 expected；使用仓库冻结 UTF-8 bytes、字面 header/sourceFingerprint/SHA-256、手工对象和 JDK `MessageDigest`，并含 JSON/Manifest 篡改反例。 |
| 失败与回退 | 新增配置崩溃窗口测试真实暴露 `AtomicFileRecovery` 无法处理“history已提交、active未开始”的CONFIG_ACTIVATION窗口；已作上述最小、marker约束修复并回归通过。另一次 zip 文件系统原子移动假设失败为测试环境假设错误，已改为跨 provider 测试。 |
| 新增风险 | 无确认生产阻断；仍需 Sol 二次 Code Review。D1-T04 Java/目标网络 PBOC HTTPS/TLS风险仍未处理。 |
| 阻塞项变化 | Sol 指定的验收覆盖阻断已提交PASS证据；D1-T03仍受 Review 门禁，D1-T04保持`NOT_STARTED`且`blockedByTask=D1-T03`；AT-SRC-002保持`NOT_RUN`。 |
| 最近可运行版本 | `backend` Java 17 + Spring Boot 3.3.6；66项全量测试通过。 |
| Git提交 | 无；当前工作目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；以测试优先方式补齐证据，仅修复测试真实暴露的CONFIG_ACTIVATION恢复缺口；未进入D1-T04/Provider/真实PBOC/D2，也未重构文件基础设施。 |
| 下一建议任务 | 等待 Sol 对 D1-T03 Review Fix 二次 Code Review；仅 Sol 通过后才可将D1-T03改为`DONE`。 |
### D1-T03 最终二次 Code Review通过与任务收口

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（技术负责人最终Review/状态收口，不是新的业务代码开发） |
| 开始时间 | 2026-08-09 17:16:15（Asia/Shanghai） |
| 结束时间 | 2026-08-09 17:16:15（Asia/Shanghai） |
| 执行窗口/执行人 | Sol 技术负责人最终二次Review结论；Codex实现工程师窗口记录状态收口 |
| 开始状态 | D1-T03=`REVIEW_PENDING`；Sol Review=`CHANGES_REQUESTED`后的AT-FILE-000补证已自测通过；D1-T04=`NOT_STARTED`且blockedByTask=D1-T03。 |
| 结束状态 | D1-T03=`DONE`、`statusReason=REVIEW_PASS_AT_FILE_000_PASS_SOL_FINAL_REVIEW_APPROVED`；Sol最终二次Review=`PASS`；`AT-FILE-000=PASS`；D1-T04=`READY`。 |
| 对应需求 | D1-T03：H02-H04、SUP-02、F07、C01、C02、C06-C10、C15、C16、C27-C34；AT-FILE-000。 |
| 已完成内容 | 接收Sol正式结论：Finding 9=`CLOSED`、AT-FILE-000=`PASS`、D1-T03=`REVIEW_PASS`；完成D1-T03状态收口并解除D1-T04任务依赖。 |
| 创建/修改文件 | 仅更新`docs/04-DEVELOPMENT-TASKS.md`、本台账；未修改冻结规范、Traceability、业务代码、Provider、业务data或真实PBOC证据。 |
| 测试/验收结果 | 技术负责人最终二次Review批准。AT-FILE-000仅确认D1-T03文件存储与基础设施验收；不代表AT-SRC-002、真实PBOC数据闭环、Day 1或Day 2总门禁通过。 |
| 阻塞项变化 | D1-T03 Review门禁解除；D1-T04可领取。Windows原生PBOC HTTPS/TLS的`EXTERNAL_ACCESS_BLOCKED`保留为D1-T04外部风险。 |
| 最近可运行版本 | `backend` Java 17 + Spring Boot 3.3.6；D1-T03最终Review通过。 |
| Git提交 | 无；当前工作目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；仅状态收口和下一任务开工确认，未实施D1-T04。 |
| 下一建议任务 | 等待项目方确认后领取唯一READY P0任务D1-T04。 |
### D1-T04 领取：PBOC OfficialWeb真实获取与raw落盘

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-09 17:46:33（Asia/Shanghai） |
| 执行窗口/执行人 | Codex实现工程师窗口 |
| 开始状态 | D1-T04=`READY`；D1-T02、D1-T03均为`DONE`。 |
| 结束时间 | 2026-08-09 18:25:20（Asia/Shanghai） |
| 结束状态 | D1-T04=`REVIEW_PENDING`；`statusReason=REAL_PBOC_JAVA17_COLLECTION_AND_TASK_TESTS_SUBMITTED_FOR_CODE_REVIEW_20260809`。执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-01、SUP-02、F02、F03、F07；C09、C14、C25、C30、C33。 |
| 已完成内容 | 实现唯一的PBOC OfficialWeb适配：合法匿名Java HTTPS访问公告列表，从真实列表href发现详情页，保存同一响应的双币不可变raw/manifest，并分别创建初始LifecycleTimelineV1。真实Java 17链路成功取得2026-08-07 PBOC公告；USD=6.7904、EUR=7.8067均按原始decimal string保存。 |
| 创建/修改文件 | 新增`backend/src/main/java/com/supplymind/provider/pboc/`的PBOC HTTP、页面解析、适配与脱敏日志类；新增D1-T04合成夹具及`PbocOfficialWebDataProviderContractTest`、`PbocOfficialWebRealNetworkAttemptTest`；最小修复PBOC列表无日期导航链接和详情页document title结构；新增`docs/evidence/D1-T04/REAL-PBOC-JAVA17-COLLECTION-20260809T181803+0800.md`；在唯一`backend/data/`落盘真实raw、manifest、timeline。 |
| 实际测试命令或步骤 | Java 17：`mvnw.cmd -q -Dtest=PbocOfficialWebDataProviderContractTest test`（6 PASS）；一次真实`PbocOfficialWebRealNetworkAttemptTest`（PASS）；`mvnw.cmd -q -Dtest=PbocOfficialWebDataProviderContractTest,RawAndConfigStoreTest,AtomicFileStoreWriteInvariantTest test`（13 PASS）；独立核对两个payload解码实体、SHA-256、source字段、raw无HTTP headers和RECEIVED+PENDING timeline。 |
| 测试结果 | 合成正常/缺USD/缺EUR/结构变化/fake timeout/重复响应/日志脱敏均通过；真实PBOC链路HTTP 200、`text/html`，从列表发现实际详情URL并产生两个raw/timeline。未触发VERIFIED、PUBLISHED、daily、aggregate或其他Provider。 |
| 验收证据路径 | `docs/evidence/D1-T04/REAL-PBOC-JAVA17-COLLECTION-20260809T181803+0800.md`；`backend/data/raw/`、`backend/data/staging/`及对应manifest；`backend/target/surefire-reports/`。 |
| 失败与回退 | 首次真实尝试发现官方列表含同名无日期导航链接，第二次发现详情页使用document title；均经最小、PBOC专用fail-closed解析修复和合成回归后成功。未猜测文章ID、未使用非PBOC来源、未关闭TLS、未使用Cookie/token或伪造数据。 |
| 新增风险 | PBOC HTML可能继续发生结构或字段漂移，后续运行应保持严格解析与fail-closed；本次任务级成功不构成AT-SRC-002、Day 1或Day 2总门禁通过。 |
| 阻塞项变化 | D1-T04的Java公开HTTPS外部风险已由真实成功复测解除；任务转为Code Review门禁。AT-SRC-002、Day 1/Day 2总门禁保持`NOT_RUN`；D1-T05保持`NOT_STARTED`。 |
| 最近可运行版本 | `backend`：Java 17 + Spring Boot 3.3.6；真实PBOC双币raw/manifest和两个独立初始timeline已落盘。 |
| Git提交 | 无；当前目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；严格只实施D1-T04，未进入D1-T05、D2、其他Provider、Provider Registry或数据库。 |
| 下一建议任务 | 等待技术负责人D1-T04 Code Review；未批准前不得领取D1-T05。 |
### D1-T04 Code Review通过与任务收口

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（Review状态收口，不是新的开发实现） |
| 开始时间 | 2026-08-09 20:53:24（Asia/Shanghai） |
| 结束时间 | 2026-08-09 20:53:24（Asia/Shanghai） |
| 执行窗口/执行人 | Sol技术负责人 + OpenCode独立Review结论；Codex实现工程师窗口记录状态收口 |
| 开始状态 | D1-T04=`REVIEW_PENDING`。 |
| 结束状态 | D1-T04=`DONE`；`statusReason=REVIEW_PASS_SOL_AND_OPENCODE_APPROVED_20260809`。 |
| 对应需求 | SUP-01、SUP-02、F02、F03、F07。 |
| 已完成内容 | 接收Sol技术负责人和OpenCode独立Review均为通过的正式结论；将D1-T04从`REVIEW_PENDING`收口为`DONE`。 |
| 创建/修改文件 | 仅更新`docs/04-DEVELOPMENT-TASKS.md`与本台账的可变状态和Review记录；未修改冻结规范、业务代码、真实raw或未来任务内容。 |
| 测试/验收结果 | D1-T04任务级Review=`PASS`。此状态不等于AT-SRC-002、Day 1退出或Day 2总门禁通过；三者仍为`NOT_RUN`。 |
| 验收证据路径 | `docs/evidence/D1-T04/REAL-PBOC-JAVA17-COLLECTION-20260809T181803+0800.md`及D1-T04既有定向测试/真实采集证据。 |
| 阻塞项变化 | 解除D1-T04 Review门禁；D1-T05的直接依赖已满足，但任务状态保持`NOT_STARTED`，等待项目方领取确认。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D1-T04真实双币raw/timeline已通过Review。 |
| Git提交 | 无；当前目录尚未初始化Git仓库。 |
| 是否偏离计划 | 否；严格只完成D1-T04状态收口与D1-T05只读开工确认。 |
| 下一建议任务 | 项目方确认后领取D1-T05；本窗口未实施D1-T05。 |
### D1-T05 领取与实施：PBOC双币raw闭环冒烟门禁

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1 |
| 开始时间 | 2026-08-09 21:35（Asia/Shanghai，领取并更新快照） |
| 结束时间 | 2026-08-09 21:44（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实现工程师窗口 |
| 开始状态 | D1-T05=`NOT_STARTED`；D1-T02、D1-T03、D1-T04均为`DONE`。 |
| 结束状态 | D1-T05=`REVIEW_PENDING`；`statusReason=REAL_PBOC_DUAL_CURRENCY_RAW_SMOKE_GATE_EVIDENCE_SUBMITTED_20260809`。执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-01、SUP-02、F03、F07；D1-T05任务定义、DoD与Day 1退出条款。 |
| 已完成内容 | 在清空的独立dataRoot（`backend/data/d1-t05-smoke`，与D1-T04的`backend/data`完全独立）重新执行真实PBOC双币采集：列表→真实详情链接→详情实体→USD/EUR两个独立不可变raw/manifest与各自RECEIVED+PENDING timeline；核对SHA-256/来源/日期/单位/原始值（rawValue与保留页面可见文本锚点一致）；真实重复触发按冻结规则（总计划8.5.6同hash幂等/异hash冲突绝不覆盖）生成RawConflictEvidenceV1且既有证据逐字节未动；Spring Context关闭后重新初始化，从磁盘重启读取全部文件逐字节一致；断网（真实ConnectException）重试不造数、不写任何文件。 |
| 创建/修改文件 | 新增`backend/src/test/java/com/supplymind/provider/pboc/PbocRawClosedLoopSmokeGateTest.java`（真实门禁测试 + 确定性断网不造数测试）；新增`docs/evidence/D1-T05/d1-t05-smoke-gate-summary.json`（测试自动生成）；新增`docs/evidence/D1-T05/D1T05-PBOC-RAW-CLOSED-LOOP-SMOKE-GATE-20260809T2142+0800.md`（Day 1退出报告/证据）；更新`docs/04-DEVELOPMENT-TASKS.md`与本台账状态。未修改生产代码。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PbocRawClosedLoopSmokeGateTest' '-Dpboc.real-network=true' '-Dd1-t05.data-root=...\backend\data\d1-t05-smoke' '-Dd1-t05.evidence-dir=...\docs\evidence\D1-T05' test`（2 PASS）；门禁定向回归6个测试类共21项（0 failures、0 errors）。 |
| 测试结果 | 真实门禁：首次采集SUCCESS（businessDate=2026-08-07，USD=6.7904，EUR=7.8067，payloadSha256=f37cda1f…4f82）；重复触发=FROZEN_CONFLICT_EVIDENCE（1份冻结冲突证据，originals逐字节不变）；重启读取=PASS；失败路径=EXTERNAL_ACCESS_BLOCKED（ConnectException）且不造数。确定性断网测试始终通过。 |
| 验收证据路径 | `docs/evidence/D1-T05/`；`backend/data/d1-t05-smoke/`（raw/manifest/staging/conflict evidence 全量SHA-256在证据文档中）；`backend/target/surefire-reports/com.supplymind.provider.pboc.PbocRawClosedLoopSmokeGateTest.txt`。 |
| 失败与回退 | 开发期门禁测试自身暴露并修复：官方页meta描述与正文各含一次锚点文本（改为可见文本独立提取）；Files.walk删除包含根目录自身（跳过根）；writer锁文件被本进程FileLock占用不可读（按冻结文档属运行期非业务工件，快照排除）。均为测试侧修正，未改动生产代码与冻结语义。 |
| 新增风险 | PBOC HTML结构或字段漂移（持续fail-closed）；真实重复触发因raw必填receivedAt走冻结冲突证据路径（符合8.5.6/DEC-044，D2采集窗口幂等消重属后续任务）。 |
| 阻塞项变化 | D1-T05提交Code Review门禁（`REVIEW_PENDING`）。AT-SRC-002、Day 1/Day 2总门禁保持`NOT_RUN`，不得写PASS。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D1-T05真实双币raw闭环冒烟门禁证据已提交。 |
| Git提交 | 无；未获指示，未提交。 |
| 是否偏离计划 | 否；严格只实施D1-T05，未进入D2、未标PASS任何总门禁、未实现标准化/校验/发布/daily/aggregate/warning/dashboard/Agent。 |
| 下一建议任务 | 等待技术负责人D1-T05 Code Review；未批准前不得改为`DONE`，不得启动D2或后续未批准任务。 |
### D1-T05 Review BLOCKER 定点修复：重启读取改为真实第二个 Spring Context

| 字段 | 记录 |
|---|---|
| 开发日 | Day 1（Review BLOCKER 修复，非重新实施） |
| 开始时间 | 2026-08-09 22:45（Asia/Shanghai） |
| 结束时间 | 2026-08-09 22:52（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实现工程师窗口 |
| 开始状态 | D1-T05=`REVIEW_PENDING`；Sol Review 唯一 BLOCKER：重启读取未真正启动第二个 Spring Context。 |
| 结束状态 | D1-T05=`REVIEW_PENDING`；`statusReason=REAL_PBOC_DUAL_CURRENCY_RAW_SMOKE_GATE_EVIDENCE_RESUBMITTED_AFTER_REVIEW_FIX_20260809`。执行人不得自行改为`DONE`。 |
| 已完成内容 | 修复`PbocRawClosedLoopSmokeGateTest`重启读取阶段：Context A（真实Spring Context）采集并落盘后`close()`并断言`isActive()==false`；使用同一物理dataRoot再次`SpringApplicationBuilder(SupplyMindApplication)`启动全新Spring Context B（断言`assertNotSame(contextA, contextB)`、`contextB.isActive()`、DataRoot Bean路径一致）；从Context B重新获取RawReceiptStore/AtomicFileStore/ConfigActivationStore/PbocOfficialWebDataProvider/SingleWriterGuard Bean并经其DataRoot Bean从磁盘读取；核验USD/EUR raw、manifest、timeline与Context A关闭前逐字节一致，解码对象相等且RECEIVED+PENDING/candidate=null；完成后关闭Context B。移除以DataRoot.forTest(root)直接读盘替代Context B的做法。 |
| 创建/修改文件 | 仅修改`backend/src/test/java/com/supplymind/provider/pboc/PbocRawClosedLoopSmokeGateTest.java`；删除旧证据`docs/evidence/D1-T05/D1T05-PBOC-RAW-CLOSED-LOOP-SMOKE-GATE-20260809T2142+0800.md`（含不准确声明）；重新生成`docs/evidence/D1-T05/d1-t05-smoke-gate-summary.json`（测试自动生成）与`docs/evidence/D1-T05/D1T05-PBOC-RAW-CLOSED-LOOP-SMOKE-GATE-20260809T2249+0800.md`；最小同步`docs/04`与本台账。未修改生产代码。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PbocRawClosedLoopSmokeGateTest' '-Dpboc.real-network=true' '-Dd1-t05.data-root=...\backend\data\d1-t05-smoke' '-Dd1-t05.evidence-dir=...\docs\evidence\D1-T05' test`（2 PASS）；门禁定向回归6个测试类共21项（0 failures、0 errors）。 |
| 测试结果 | 真实门禁PASS：首次采集SUCCESS（businessDate=2026-08-07，USD=6.7904，EUR=7.8067，payloadSha256=f37cda1f…4f82）；重复触发=FROZEN_CONFLICT_EVIDENCE（1份冻结冲突证据，originals逐字节不变）；重启读取=PASS（secondSpringContext=true、distinctFromContextA=true、contextAClosedBeforeRestart=true、filesUnchanged=true，beans经Context B重新取得）；失败路径=EXTERNAL_ACCESS_BLOCKED（ConnectException）且不造数。 |
| 验收证据路径 | `docs/evidence/D1-T05/D1T05-PBOC-RAW-CLOSED-LOOP-SMOKE-GATE-20260809T2249+0800.md`；`docs/evidence/D1-T05/d1-t05-smoke-gate-summary.json`（含restartRead第二Context证据字段）；`backend/data/d1-t05-smoke/`（磁盘产物与SHA-256）；`backend/target/surefire-reports/com.supplymind.provider.pboc.PbocRawClosedLoopSmokeGateTest.txt`（测试计数）。 |
| 失败与回退 | 无；修复后门禁一次通过。证据措辞同步修正：不再声称surefire .txt“仍包含全部原始输出行”，可持续证据以summary JSON、测试结果、磁盘产物与SHA-256为准。 |
| 新增风险 | 无新增；PBOC HTML结构或字段漂移风险持续（fail-closed）。 |
| 阻塞项变化 | D1-T05仍处Review门禁（`REVIEW_PENDING`），等待技术负责人重新Review。AT-SRC-002、Day 1/Day 2总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D1-T05门禁以真实第二个Spring Context完成重启读取验证。 |
| Git提交 | 无；未获指示，未提交。 |
| 是否偏离计划 | 否；严格按Sol Review BLOCKER范围定点修复，仅改测试与证据，未改生产代码、冻结计划、AT-SRC-002，未进入D2。 |
| 下一建议任务 | 等待技术负责人D1-T05重新Review；通过前不得改为`DONE`，不得启动D2或后续未批准任务。 |
### D2-T01 领取与实施：PBOC标准化与基础校验

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2 |
| 开始时间 | 2026-08-09（Asia/Shanghai，领取并更新快照） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口 |
| 开始状态 | D2-T01=`NOT_STARTED`；D1-T01～D1-T05=`DONE`，Day 1 Gate=`PASS`，Day 1=`COMPLETE`。 |
| 结束状态 | D2-T01=`REVIEW_PENDING`；`statusReason=PBOC_STANDARDIZATION_AND_BASIC_VALIDATION_EVIDENCE_SUBMITTED_20260809`。执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-02、F03、F06、H02；D2-T01任务定义、DoD与冻结状态机/条件必填矩阵（总计划8.4.3、DEC-042、C28/C31）。 |
| 已完成内容 | 实现PBOC标准化与基础校验最小链：新增TimelineStore（LifecycleTimelineV1初始创建与原子追加、幂等重放不追加重复快照）与validation包（PbocCandidateStandardizer `pboc-standardization-v1`、PbocBasicValidator `pboc-basic-validation-v1`、LifecycleValidationService 编排、ActiveConfigReader、原因码/结果载体）。执行链：RECEIVED+PENDING → PARSED+PENDING（不可变CandidateV1）→ VALIDATED+VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT；解析失败 → RECEIVED+REJECTED（candidate=null）。校验规则版本化固定：来源、字段完整性、单位、币种、未来日期、30天时效窗口、范围(0,100]、同键同源重复→VERIFIED_WITH_NOTICE/DUPLICATE_OBSERVATION、异值→CONFLICT/VALUE_CONFLICT；无效数据绝不覆盖既有合法值。真实D1-T05双币raw（复制逐字节一致，真实页面SHA-256=f37cda1f…4f82，businessDate=2026-08-07）均PARSED→VALIDATED+VERIFIED（USD=6.7904、EUR=7.8067），raw校验后逐字节未动，重启重读一致。 |
| 创建/修改文件 | 新增`backend/src/main/java/com/supplymind/foundation/storage/TimelineStore.java`；新增`backend/src/main/java/com/supplymind/validation/`（7个类）；新增测试`PbocValidationPipelineTest`（14项）与`PbocValidationRealRawEvidenceTest`（gated真实raw证据）；新增golden fixtures `contracts/v1/valid/lifecycle-validated-verified-pboc-v1.json`、`lifecycle-validated-rejected-unit-mismatch-pboc-v1.json`、`lifecycle-received-rejected-standardization-pboc-v1.json`与`contracts/v1/invalid/lifecycle-received-validated-skip.json`、`lifecycle-parsed-published-skip.json`；新增`docs/evidence/D2-T01/`（校验报告与`d2-t01-real-raw-validation-summary.json`）；同步`docs/04`与本台账。未修改任何Day 1生产代码、冻结计划与数据字典。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PbocValidationPipelineTest,PbocValidationRealRawEvidenceTest' test`；`mvnw.cmd -q '-Dtest=PbocValidationRealRawEvidenceTest' '-Dd2-t01.real-raw=true' '-Dd2-t01.source-data-root=...\backend\data\d1-t05-smoke' '-Dd2-t01.evidence-dir=...\docs\evidence\D2-T01' test`；最小直接回归6个测试类。 |
| 测试结果 | 合成矩阵14项PASS（含3组golden bytes逐字节一致、重复/冲突/幂等/跳级拒绝/恢复）；真实raw证据门禁PASS（双币VALIDATED+VERIFIED）；最小直接回归31 tests，0 failures、0 errors（1 skipped=gated）。 |
| 验收证据路径 | `docs/evidence/D2-T01/D2T01-PBOC-STANDARDIZATION-AND-BASIC-VALIDATION-20260809.md`；`docs/evidence/D2-T01/d2-t01-real-raw-validation-summary.json`；`backend/src/test/resources/contracts/v1/{valid,invalid}/`黄金/非法fixture；`backend/target/surefire-reports/`。 |
| 失败与回退 | 开发期测试侧修正：golden fixture改为与冻结codec一致的compact单行固定字节；管线时钟统一Asia/Shanghai；SOURCE_MISMATCH用例改为直接落盘raw以独立验证校验规则（RawReceiptStore的冻结来源守卫使其无法经store注入）。均为测试侧调整，未改动生产语义。 |
| 新增风险 | 数值范围上限(0,100]与30天时效窗口为冻结文档未锁定的版本化实现默认（随pboc-basic-validation-v1固定），待EXT-03/EXT-05/EXT-06关闭后以新validationVersion调整。 |
| 阻塞项变化 | D2-T01提交Code Review门禁（`REVIEW_PENDING`）。AT-SRC-002、Day 2总门禁保持`NOT_RUN`，不得写PASS。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T01双币真实raw标准化→校验→VALIDATED+VERIFIED闭环证据已提交。 |
| Git提交 | 无新提交；分支`feature/d2-t01`，基线`day1-complete`；未获指示，未提交。 |
| 是否偏离计划 | 否；严格只实施D2-T01，未进入发布门禁/每日加工/调度、未生成quarantine（属D2-T02）、未创建normalized/published目录、未修改AT-SRC-002。 |
| 下一建议任务 | 等待技术负责人D2-T01 Code Review；未批准前不得改为`DONE`，不得启动D2-T02或后续未批准任务。 |
### D2-T01 Review Fix：CHANGES_REQUESTED Finding 1-3 修复与 Finding 4 上报

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2（Review Fix，非重新实施） |
| 开始时间 | 2026-08-09（Asia/Shanghai） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口 |
| 开始状态 | D2-T01=`REVIEW_PENDING`；技术负责人 Review=`CHANGES_REQUESTED`（Finding 1 BLOCKER、Finding 2/3 MAJOR、Finding 4 业务决策）。 |
| 结束状态 | D2-T01=`REVIEW_PENDING`；`statusReason=PBOC_STANDARDIZATION_AND_BASIC_VALIDATION_REVIEW_FIX_RESUBMITTED_20260809`。Finding 4=`CHANGE_REQUEST_REQUIRED`。执行人不得自行改为`DONE`。 |
| 已完成内容 | Finding 1：重复/冲突历史扫描只纳入当前快照为 VERIFIED 类（VERIFIED/VERIFIED_WITH_NOTICE）的合法基准，排除 PARSED+PENDING、VALIDATED+REJECTED、VALIDATED+CONFLICT。Finding 2：`ActiveConfigReader` 改为 `VersionedConfigReader.readVersion(dataRoot, raw.configVersion())`，精确读取不可变 config history（manifest+版本一致校验），不再用当前 active config。Finding 3：standardizer 仅对不可解析/缺字段判 STANDARDIZATION_FAILED；可解析的 0/负数构造 CandidateV1 经 PARSED+PENDING 由 validator 判 VALIDATED+REJECTED/OUT_OF_RANGE。Finding 4：检查全部冻结 docs 后确认无任何 stale/range 阈值正式裁决，按要求上报 CHANGE_REQUEST_REQUIRED，未自行决定参数、未修改冻结 docs。 |
| 创建/修改文件 | 修改`validation/PbocCandidateStandardizer.java`、`validation/LifecycleValidationService.java`；删除`validation/ActiveConfigReader.java`、新增`validation/VersionedConfigReader.java`；新增7项测试（rejected/conflict/pending 历史反例、configVersion 切换、0/负数/不可解析）；更新`docs/evidence/D2-T01/D2T01-...-20260809.md`（Review Fix 记录）、`docs/04`与本台账。未修改 Day 1 生产代码、冻结计划、数据字典。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PbocValidationPipelineTest,PbocValidationRealRawEvidenceTest' test`（21+1(skip) PASS）；真实raw门禁重跑（gated）PASS；最小直接回归6类38 tests。 |
| 测试结果 | 21/21 PASS：新增 rejected 历史不参与 conflict、conflict 历史不污染同值新记录、PARSED+PENDING 历史不参与、configVersion=1 在 active V2 下仍按 V1 同结果且新 V1 raw 仍 VERIFIED、V2 raw 按 V2 判 VERIFIED、rawValue=0 与 -1.5 经 PARSED→OUT_OF_RANGE、rawValue=abc→RECEIVED+REJECTED(candidate=null)；原 14 项全部保持通过。真实 raw 门禁重跑双币 VERIFIED。回归38 tests 0 failures 0 errors（1 skipped）。 |
| 验收证据路径 | `docs/evidence/D2-T01/D2T01-PBOC-STANDARDIZATION-AND-BASIC-VALIDATION-20260809.md`（含 Review Fix 记录）；`docs/evidence/D2-T01/d2-t01-real-raw-validation-summary.json`；surefire 报告。 |
| 失败与回退 | 无。 |
| 新增风险 | Finding 4 未决：stale 阈值与数值边界（当前实现维持 30 天/(0,100] 版本化默认行为，标注非正式）等待技术负责人正式裁决并提供 validationVersion；裁决前 D2-T01 不得宣称业务口径通过。 |
| 阻塞项变化 | D2-T01 仍处 Review 门禁；Finding 4 裁决为新增外部裁决项。AT-SRC-002、Day 2 总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T01 Review Fix 后测试全绿。 |
| Git提交 | 无新提交；分支`feature/d2-t01`，基线`day1-complete`；未获指示，未提交。 |
| 是否偏离计划 | 否；严格只修复已确认 Finding，未进入 D2-T02/发布/隔离/加工，未修改 AT-SRC-002 与冻结 docs。 |
| 下一建议任务 | 等待技术负责人：D2-T01 Finding 1-3 复审 + Finding 4 阈值正式裁决；未批准前不得改为`DONE`。 |
### D2-T01 Finding 4 正式决策落地与 Review 收口

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2（决策落地与状态收口，非重新实施） |
| 开始时间 | 2026-08-09（Asia/Shanghai） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口 |
| 开始状态 | D2-T01=`REVIEW_PENDING`；Finding 1-3=`CLOSED`，Finding 4 待技术负责人正式裁决。 |
| 结束状态 | D2-T01=`REVIEW_PENDING`；`statusReason=PBOC_STANDARDIZATION_AND_BASIC_VALIDATION_FINDINGS_CLOSED_AWAITING_FINAL_REVIEW_20260809`。Finding 1-4 全部关闭，等待最终 Review；执行人不得自行改为`DONE`。 |
| 已完成内容 | 将技术负责人正式批准的 PBOC 汇率基础校验 v1 业务口径登记为 `docs/06-DECISION-LOG.md` 的 DEC-050（`staleThresholdDays=30`、`(0,100]`、`pboc-basic-validation-v1`，仅限 USD/CNY、EUR/CNY D2-T01 基础校验，历史 validationVersion 结果保持可追溯，不得扩展其他口径）。核验生产代码与正式参数逐字一致（30 天判断、signum<=0 或 >100 → OUT_OF_RANGE、100 允许、validationVersion 常量），生产代码零修改。新增 4 项 DEC-050 边界测试。修正 evidence 中"版本化实现默认/等待确认"措辞为正式批准口径（保留原 Review Fix 历史审计链）。 |
| 创建/修改文件 | 修改`docs/06-DECISION-LOG.md`（新增 DEC-050）；修改`docs/evidence/D2-T01/D2T01-...-20260809.md`（正式参数说明、Finding 4 状态、边界测试结果）；修改`PbocValidationPipelineTest.java`（+4 边界测试）；最小同步`docs/04`与本台账。未修改生产代码与冻结计划。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PbocValidationPipelineTest' test`（25 PASS）；真实 raw 门禁重跑（gated）PASS；最小直接回归 6 类 42 tests。 |
| 测试结果 | 边界：日期差=30（2026-07-11）→ 非 stale VERIFIED；日期差=31（2026-07-10）→ STALE_BUSINESS_DATE；value=0 与 -1.5 → 先 PARSED+PENDING 后 VALIDATED+REJECTED/OUT_OF_RANGE；value=100 → VERIFIED；value=101/500 → OUT_OF_RANGE。管线 25 tests 0 failures 0 errors；真实 raw 双币 VALIDATED+VERIFIED；回归 42 tests 0 failures 0 errors 0 skipped。 |
| 验收证据路径 | `docs/06-DECISION-LOG.md#DEC-050`；`docs/evidence/D2-T01/`；surefire 报告。 |
| 失败与回退 | 无。 |
| 新增风险 | 无新增；DEC-050 适用范围已限定双币基础校验，扩展需新决策+新 validationVersion。 |
| 阻塞项变化 | D2-T01 全部 Finding 关闭，仅剩最终 Review 门禁。AT-SRC-002、Day 2 总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T01 正式参数边界与真实 raw 证据通过。 |
| Git提交 | 无新提交；分支`feature/d2-t01`，基线`day1-complete`；未获指示，未提交。 |
| 是否偏离计划 | 否；未进入 D2-T02/PUBLISHED/quarantine/daily/aggregate/warning/Agent/前端，未修改 AT-SRC-002 与 Finding 1-3 实现。 |
| 下一建议任务 | 等待技术负责人 D2-T01 最终 Review；通过前不得改为`DONE`，不得启动 D2-T02。 |
### D2-T01 Sol 最终 Review 通过与任务收口

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2（Review 收口，非新的业务开发） |
| 开始时间 | 2026-08-09（Asia/Shanghai） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | Sol 技术负责人最终 Review 结论；OpenCode实施工程师窗口记录状态收口 |
| 开始状态 | D2-T01=`REVIEW_PENDING`；Finding 1-4=`CLOSED`、DEC与实现一致性=PASS、边界规则=PASS、D2-T01 DoD=PASS、BLOCKER=无；唯一 MAJOR（证据/注释口径）已由 Final Delta 收口。 |
| 结束状态 | D2-T01=`DONE`；`statusReason=REVIEW_PASS_SOL_FINAL_APPROVED_20260809`。Sol 最终 Review=`PASS`。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。 |
| 已完成内容 | 接收 Sol 正式结论：D2-T01 通过最终 Review（Finding 1-4 全部 CLOSED、DoD PASS、DEC-050 已生效）；完成 D2-T01 状态收口为`DONE`。 |
| 创建/修改文件 | 仅更新`docs/04-DEVELOPMENT-TASKS.md`与本台账的 D2-T01 状态记录；未修改生产代码、测试、evidence、冻结业务规则与 DEC-050。 |
| 测试/验收结果 | D2-T01 任务级 Review=`PASS`（25 项定向测试、真实 raw 双币 VALIDATED+VERIFIED 证据、DEC-050 边界）。此状态不等于 AT-SRC-002、Day 2 总门禁通过。 |
| 阻塞项变化 | D2-T01 Review 门禁解除；D2-T02 的直接依赖已满足，任务保持`NOT_STARTED`，等待项目方确认领取。AT-SRC-002、Day 2 总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T01 标准化/校验链（含 DEC-050 业务口径）已通过 Sol 最终 Review。 |
| Git提交 | 无新提交；分支`feature/d2-t01`，基线`day1-complete`；未获指示，未提交。 |
| 是否偏离计划 | 否；仅状态收口，未实施 D2-T02。 |
| 下一建议任务 | 等待项目方确认后领取 D2-T02 PBOC VERIFIED发布门禁。 |
### D2-T02 领取与实施：PBOC VERIFIED发布门禁

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2 |
| 开始时间 | 2026-08-09（Asia/Shanghai，领取并更新快照） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口 |
| 开始状态 | D2-T02=`NOT_STARTED`；D2-T01=`DONE`（Sol最终Review PASS）、DEC-050 生效。 |
| 结束状态 | D2-T02=`REVIEW_PENDING`；`statusReason=PBOC_VERIFIED_PUBLISH_GATE_EVIDENCE_SUBMITTED_20260809`。执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-02、F06、H01、H02；D2-T02任务定义、DoD、冻结状态机/条件必填矩阵（总计划8.4.3、DEC-042、C28/C31、AT-PUB-001/002/003）。 |
| 已完成内容 | 实现最小发布边界与业务读模型：新增QuarantineStore（CREATE_NEW不可变投影持久化、同hash幂等、异hash fail-closed）与publish包（LifecyclePublishService 编排、PublishedQueryService 业务入口、PublishOutcome/PublishedRecord 载体）。执行链：VALIDATED+VERIFIED类 → 追加PUBLISHED快照（recordVersion=4、publishRef=staging/<runId>.json#recordVersion=4、publishedAt，审计字段保持）；三个失败终态 → QuarantineProjectionV1.fromTerminal 确定性投影落盘（timeline逐字节不动）；PENDING → NOT_READY；PUBLISHED重放幂等。业务入口仅暴露PUBLISHED+VERIFIED类，记录可追溯（runId/rawRef/recordVersion/validationVersion/rawPayloadSha256/rawFileSha256/stale事实比较）。真实D1-T05双币raw（逐字节复制，真实页面SHA-256=f37cda1f…4f82）经D2-T01校验后均发布为PUBLISHED+VERIFIED（USD=6.7904、EUR=7.8067），raw逐字节未动、无quarantine、业务入口可见。 |
| 创建/修改文件 | 新增`foundation/storage/QuarantineStore.java`；新增`publish/`（LifecyclePublishService、PublishedQueryService、PublishOutcome、PublishedRecord）；新增测试`PublishGateTest`（9项）、`PublishedQueryServiceTest`（5项）、`PublishRealRawEvidenceTest`（gated真实raw）；新增golden `contracts/v1/valid/lifecycle-published-pboc-v1.json`；新增`docs/evidence/D2-T02/`（实施记录与`d2-t02-real-raw-publish-summary.json`）；同步`docs/04`与本台账。未修改D1/D2-T01生产代码、冻结计划、DEC-050与数据字典。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=PublishGateTest,PublishedQueryServiceTest,PublishRealRawEvidenceTest' test`（14+1(skip) PASS）；真实raw门禁（gated `-Dd2-t02.real-raw=true`）PASS；最小直接回归7类55 tests。 |
| 测试结果 | 发布门禁9项PASS（含golden bytes、notice发布、PENDING不动、三终态quarantine逐字段对账、重放幂等、合法不误隔离）；读模型5项PASS（不可见性边界、全字段追溯、latest/stale、notice可见）；真实raw门禁双币PUBLISHED+VERIFIED；回归55 tests 0 failures 0 errors 0 skipped。 |
| 验收证据路径 | `docs/evidence/D2-T02/D2T02-PBOC-VERIFIED-PUBLISH-GATE-20260809.md`；`docs/evidence/D2-T02/d2-t02-real-raw-publish-summary.json`；`backend/src/test/resources/contracts/v1/valid/lifecycle-published-pboc-v1.json`；`backend/target/surefire-reports/`。 |
| 失败与回退 | 开发期测试侧修正：ManifestV1导入包位置与BOM清理、`isPublishedForDailyInput`调用对象、两个查询测试断言自身错误（未发布first运行、查询日期误用）。均为测试侧调整，未改动生产语义。 |
| 新增风险 | 业务读模型stale为事实比较语义（businessDate<参考日，Asia/Shanghai），未引入业务阈值；正式stale展示口径（EXT-06相关）由后续任务/决策确定。 |
| 阻塞项变化 | D2-T02提交Code Review门禁（`REVIEW_PENDING`）。AT-SRC-002、Day 2总门禁保持`NOT_RUN`，不得写PASS。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T02双币真实raw发布门禁与业务读模型证据已提交。 |
| Git提交 | 无新提交；分支`feature/d2-t02`，基线`day1-complete`；未获指示，未提交。 |
| 是否偏离计划 | 否；严格只实施D2-T02，未进入daily/aggregate/warning/Agent/Vue、未创建published目录、未修改AT-SRC-002、未启动D2-T03。 |
| 下一建议任务 | 等待项目方确认后领取 D2-T03 PBOC每日加工与CSV持久化。 |
### D2-T02 Sol 最终固定快照 Review 通过与任务收口

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2（Review 收口，非新的业务开发） |
| 开始时间 | 2026-08-09（Asia/Shanghai） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | Sol 技术负责人最终固定快照 Review 结论；OpenCode实施工程师窗口记录状态收口 |
| 开始状态 | D2-T02=`REVIEW_PENDING`；publishRef MAJOR=FIXED、stale CHANGE_REQUEST 已落地（DEC-051）、DEC与实现一致性=PASS。 |
| 结束状态 | D2-T02=`DONE`；`statusReason=REVIEW_PASS_SOL_FINAL_APPROVED_20260809`。Sol 最终固定快照 Review=`PASS`（审查 commit=`12766c9`）；publishRef MAJOR=`CLOSED`、stale CHANGE_REQUEST=`CLOSED`、DEC-051 与实现=`PASS`、D2-T02 DoD=`PASS`、Evidence=`VALID`。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。 |
| 已完成内容 | 接收 Sol 正式结论：D2-T02 通过最终固定快照 Review（审查 commit=`12766c9`）；完成 D2-T02 状态收口为`DONE`。 |
| 创建/修改文件 | 仅更新`docs/04-DEVELOPMENT-TASKS.md`与本台账的 D2-T02 状态记录；未修改生产代码、测试、evidence、DEC-050/DEC-051 与冻结业务规则。 |
| 测试/验收结果 | D2-T02 任务级 Review=`PASS`（61 项定向/回归测试、真实 raw 双币 PUBLISHED+VERIFIED 证据、publishRef 与 DEC-051 边界）。此状态不等于 AT-SRC-002、Day 2 总门禁通过。 |
| 阻塞项变化 | D2-T02 Review 门禁解除；D2-T03 的直接依赖已满足，`TaskExecutionStatus=READY`，等待项目方确认领取。AT-SRC-002、Day 2 总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T02 发布门禁/隔离/读模型（含 publishRef、DEC-051 stale）已通过 Sol 最终 Review。 |
| Git提交 | 无新提交；审查基线 commit=`12766c9`（feature/d2-t02）；未获指示，未提交。 |
| 是否偏离计划 | 否；仅状态收口，未实施 D2-T03。 |
| 下一建议任务 | 等待项目方确认后领取 D2-T03 PBOC每日加工与CSV持久化。 |
### D2-T03 领取与实施：PBOC每日加工与CSV持久化

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2 |
| 开始时间 | 2026-08-09（Asia/Shanghai，领取并更新快照） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口 |
| 开始状态 | D2-T03=`READY`；D2-T01、D2-T02=`DONE`（Sol最终Review PASS）。 |
| 结束状态 | D2-T03=`REVIEW_PENDING`；`statusReason=PBOC_DAILY_PROCESSING_EVIDENCE_SUBMITTED_20260809`。EXT-03/EXT-06未关闭，按冻结DoD不得标DONE或宣称正式业务口径通过；执行人不得自行改为`DONE`。 |
| 对应需求 | SUP-02、F03、F08、H01、H02；D2-T03任务定义、DoD、总计划8.4.5 Daily CSV固定表头、CALCULATION-RULES（arithmetic-mean-v1）、DEC-016/DEC-043/DEC-048、AT-PUB-001。 |
| 已完成内容 | 实现每日加工最小链：新增processing包（DailyProcessingService 按月编排、DailyMeanCalculator 冻结算术平均计算、DailyInput/DailyResult 载体）。仅接受PUBLISHED+VERIFIED类输入（冻结发布门禁谓词）；逐输入经TimelineStore/raw/manifest校验读取与VersionedConfigReader（raw.configVersion→不可变history）解析计算上下文；按冻结分组键分组（不同来源/单位/币种/校验结论/计算上下文分行）；sum精确不舍入、avg仅按calculationScale/roundingMode一次舍入、displayScale不回写、expectedCount=1、缺失不补0、完整inputRefs（runId/rawRef/recordVersion=4覆盖全部validCount）；原子写processed/daily/<itemId>/YYYY-MM.csv+manifest（ManifestFactory.csv，rowCount/min/max/sourceRunIds）。真实D1-T05双币raw（逐字节复制，真实页面SHA-256=f37cda1f…4f82，businessDate=2026-08-07）经校验/发布后生成双币daily：USD sum=6.7904 avg=6.79040000、EUR sum=7.8067 avg=7.80670000，raw逐字节未动、重启解码一致。 |
| 创建/修改文件 | 新增`processing/`（DailyProcessingService、DailyMeanCalculator、DailyInput、DailyResult）；新增测试`DailyProcessingServiceTest`（11项）、`DailyRealRawEvidenceTest`（gated真实raw）；新增golden `contracts/v1/valid/daily-pboc-v1.csv`（CRLF固定字节）；新增`docs/evidence/D2-T03/`（实施记录与`d2-t03-real-raw-daily-summary.json`）；同步`docs/04`与本台账。未修改D1/D2生产代码、冻结计划、CALCULATION-RULES与数据字典。 |
| 实际测试命令或步骤 | Java 17.0.19：`mvnw.cmd -q '-Dtest=DailyProcessingServiceTest,DailyRealRawEvidenceTest' test`（11+1(skip) PASS）；真实raw门禁（gated `-Dd2-t03.real-raw=true`）PASS；最小直接回归10类75 tests。 |
| 测试结果 | 每日加工11项PASS（golden bytes、多观测平均、缺失日/空月、重复分行、非法不可见、循环小数、12/9位、配置版本同上下文合并、计算上下文分行、重算幂等、重启解码）；真实raw门禁双币daily PASS；回归75 tests 0 failures 0 errors 1 skipped(gated)。 |
| 验收证据路径 | `docs/evidence/D2-T03/D2T03-PBOC-DAILY-PROCESSING-20260809.md`；`docs/evidence/D2-T03/d2-t03-real-raw-daily-summary.json`；`backend/src/test/resources/contracts/v1/valid/daily-pboc-v1.csv`；`backend/target/surefire-reports/`。 |
| 失败与回验 | 开发期测试侧修正：多观测/循环小数/12位用例改为fixture直接构造PUBLISHED运行（D2-T01冻结冲突/重复规则使同源同日不同值必然CONFLICT、同值按校验结论分行，真实流水线无法产生同组validCount>1）；golden CSV两处修正（configVersions无逗号不加引号、updatedAt零秒省略）。均为测试侧调整，未改动生产语义。 |
| 新增风险 | EXT-03/EXT-06未关闭：每日均值/节假日口径使用冻结版本化P0默认（arithmetic-mean-v1、weekday-asia-shanghai-v1），本任务不得宣称正式业务口径通过；关闭后可能以新规则版本调整。 |
| 阻塞项变化 | D2-T03提交Code Review门禁（`REVIEW_PENDING`）。AT-SRC-002、Day 2总门禁保持`NOT_RUN`，不得写PASS。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T03双币真实raw每日加工证据已提交。 |
| Git提交 | 无新提交；分支`feature/d2-t03`，HEAD=`7258fb2`；未获指示，未提交。 |
| 是否偏离计划 | 否；严格只实施D2-T03，未进入聚合（D2-T04）/warning/Agent、未绕过发布门禁、未引入float/double、未修改AT-SRC-002。 |
| 下一建议任务 | 等待技术负责人D2-T03 Code Review；未批准前不得改为`DONE`，不得启动D2-T04或后续未批准任务。 |
### D2-T03 EXT Gate 最终收口（DEC-053 / DEC-054）

| 字段 | 记录 |
|---|---|
| 开发日 | Day 2（EXT Gate 文档收口，非技术实现） |
| 开始时间 | 2026-08-09（Asia/Shanghai） |
| 结束时间 | 2026-08-09（Asia/Shanghai） |
| 执行窗口/执行人 | OpenCode实施工程师窗口，按技术负责人 EXT-03/EXT-06 正式裁决收口 |
| 开始状态 | D2-T03=`REVIEW_PENDING`；Implementation Review（Sol + 第二方）=`PASS`、MAJOR 1/2=`CLOSED`、DEC-052=`PASS`（固定 commit=`607e859`）；EXT-03/EXT-06 待正式裁决。 |
| 结束状态 | D2-T03=`DONE`，`statusReason=REVIEW_PASS_EXT_GATE_PASS_DEC053_DEC054_20260809`；D2-T04=`READY`。EXT-03=`ACCEPTED_VERSIONED_DEFAULT`（DEC-053）、EXT-06=`ACCEPTED_VERSIONED_DEFAULT`（DEC-054）、EXT Gate=`PASS`。 |
| 已完成内容 | 登记 DEC-053（EXT-03 arithmetic-mean-v1 接受版本化默认，正式规则：sum 完整精度/validCount 只统计合法输入/avg 仅最终除法舍入/displayScale 不回写/missing 不补0，未来变更须新 calculationVersion+configVersion）与 DEC-054（EXT-06 weekday-asia-shanghai-v1 接受版本化默认，Asia/Shanghai 周一至周五预期日期/expectedCount=1/missing/complete/缺失不补0/空月不虚构，能力边界：不代表完整法定节假日/调休/停报/特殊交易日日历）；同步 docs/01（EXT-03/06 行）、docs/02（追踪关系）、docs/04（D2-T03=DONE、D2-T04=READY）、docs/05、docs/data-dictionary/CALCULATION-RULES.md。未修改生产代码、测试、evidence、FILE-SCHEMA-V1、DEC-052 内容。 |
| 创建/修改文件 | `docs/01-PROJECT-MASTER-PLAN.md`、`docs/02-REQUIREMENT-TRACEABILITY.md`、`docs/04-DEVELOPMENT-TASKS.md`、`docs/05-PROGRESS-LEDGER.md`、`docs/06-DECISION-LOG.md`（DEC-053/054）、`docs/data-dictionary/CALCULATION-RULES.md`。 |
| 测试/验收结果 | 技术 DoD 已于 Implementation Review 确认 PASS（固定 commit=607e859）；本轮为纯 Gate 文档收口，不重跑技术测试。EXT Gate=`PASS`（DEC-053/054 接受版本化默认）。AT-SRC-002、Day 2 总门禁仍为`NOT_RUN`。 |
| 阻塞项变化 | D2-T03 全部门禁解除并`DONE`；D2-T04=`READY`（前置依赖满足，尚未领取）。AT-SRC-002、Day 2 总门禁保持`NOT_RUN`。 |
| 最近可运行版本 | backend：Java 17 + Spring Boot 3.3.6；D2-T03（含 DEC-052/053/054 口径）通过 Implementation Review。 |
| Git提交 | 本轮创建 `docs: close D2-T03 EXT gate` commit；implementation commit=`607e859`。 |
| 是否偏离计划 | 否；纯状态/决策收口，未实现 D2-T04、未修改 AT-SRC-002。 |
| 下一建议任务 | 等待项目方确认后领取 D2-T04。 |
### 后续记录模板

后续窗口复制以下模板并追加在最近一条记录之后，不得删除旧记录。

### `[任务编号] [任务标题]`

| 字段 | 记录 |
|---|---|
| 开发日 | 例如 Day 2 |
| 开始时间 | YYYY-MM-DD HH:mm Asia/Shanghai |
| 结束时间 | YYYY-MM-DD HH:mm Asia/Shanghai |
| 执行窗口/执行人 |  |
| 开始状态 | `READY` |
| 结束状态 | `REVIEW_PENDING` / `DONE` / `FAILED` / `BLOCKED_*` |
| 对应需求 |  |
| 已完成内容 |  |
| 创建/修改文件 |  |
| 实际测试命令或步骤 |  |
| 测试结果 |  |
| 验收证据路径 |  |
| 失败与回退 |  |
| 新增风险 |  |
| 阻塞项变化 |  |
| 最近可运行版本 |  |
| Git提交 | 提交哈希和说明；未提交需写明原因 |
| 是否偏离计划 | 否；若是，说明偏离和批准依据 |
| 下一建议任务 |  |

## 6. 验收结果记录模板

任务执行记录中的`DONE`不得复制为本表的`PASS`。每个AT必须按实际独立执行结果填写。

| 验收用例编号 | 对应需求 | 执行日期 | 环境 | AcceptanceStatus | 证据路径 | 缺陷编号 | 复测结果 |
|---|---|---|---|---|---|---|---|
| 待填写 |  |  |  | `NOT_RUN` |  |  |  |

## 7. 风险记录

| 风险编号 | 发现日期 | 风险描述 | 概率 | 影响 | 负责人 | 缓解措施 | 触发条件 | 状态 |
|---|---|---|---|---|---|---|---|---|
| RISK-01 | 2026-08-08 | PBOC公开页面结构或字段变化导致双币采集失败 | 中 | 高 | 后端负责人 | 原始响应留存、契约测试、解析器隔离、失败显式告警 | AT-SRC-002任一币种无法生成raw或字段映射失败 | OPEN |
| RISK-02 | 2026-08-08 | SMM/Asian Metal无合法自动路径 | 高 | 中（局部能力） | 数据接入负责人 | 合法自动→FreePublic→Manual三层受控降级，记录routeDecision | 会员、无公开接口或合法反爬阻止获取 | MITIGATED_NON_BLOCKING |
| RISK-03 | 2026-08-08 | 免费公开源许可不明、更新频率或字段漂移 | 中 | 高 | 数据接入负责人 | 保存URL/条款证据，字段契约与变更告警；不合格时转Manual | 条款不可证明、解析失败或字段语义变化 | OPEN |
| RISK-04 | 2026-08-08 | 替代源规格、单位、地区或含税口径与目标序列不可比 | 中 | 高 | 数据治理负责人 | 每个来源×品种独立映射，单位归一化，不跨规格拼接 | 同一series出现不可比口径或映射缺失 | OPEN |
| RISK-05 | 2026-08-08 | Manual误录、漏录或修订覆盖原始记录 | 中 | 高 | 数据治理负责人 | 必填校验、PENDING隔离、VERIFIED门禁、版本/审计留痕 | 缺来源/单位异常/重复键/修订未留版本 | OPEN |
| RISK-06 | 2026-08-08 | 免费或Manual数据被错误标记成SMM/Asian Metal | 低 | 极高 | 技术负责人 | providerType、actualSourceName、accessMethod分字段；多出口一致性测试 | 文件/API/UI/预警/Agent任一处来源标签不实 | OPEN |
| RISK-07 | 2026-08-08 | 当前工作区的PowerShell/curl经本地代理访问PBOC时发生TLS握手失败；Java 17已于D1-T04成功完成正常公开HTTPS获取，因此不再构成D1-T04外部阻塞。 | 中 | 高 | 后端负责人 | 保留脱敏失败诊断；后续仍以Java/目标部署网络复测，禁止将命令行环境失败误判为PBOC数据失效。 | Java无法以正常公开HTTPS请求获得PBOC页面或完整raw。 | MITIGATED |
| RISK-08 | 2026-08-08 | 将D1-T02调查完成或外部失败证据误当作PBOC真实验收通过。 | 中 | 极高 | 技术负责人 | 任务/验收状态分表；AT-SRC-002独立记录；Day1/Day2硬门只认可真实双币全链。 | D1-T02为DONE后，无双币真实raw/daily仍被宣布PASS。 | OPEN |
| RISK-09 | 2026-08-08 | D1-T03对聚合路径、多item响应、Raw/Lifecycle/Candidate/Quarantine边界、配置、币种、追溯或两文件原子提交做不同解释，导致文件层返工。 | 低 | 极高 | 技术负责人 | DEC-044至DEC-047、C30-C33、AT-FILE-000和总计划8.3至9节形成唯一契约；冻结前双重复扫，编码后Review。 | Terra实现第二dataRoot、grain-first目录、raw可变状态、双item共享runId、单rawRef、来源混算或非原子降级。 | MITIGATED |

## 8. 偏离计划审批模板

任何偏离都必须先比较信息优先级：官方原始需求书及其独立实施补充说明 > 官方验收要求 > 架构冻结决策 > 总计划 > 开发任务 > 代码。

| 字段 | 内容 |
|---|---|
| 偏离编号 | DEV-YYYYMMDD-NN |
| 原计划/决策 |  |
| 拟变更内容 |  |
| 变更原因 |  |
| 是否影响官方需求或H01-H09 |  |
| 是否需要甲方确认 |  |
| 批准人和证据 |  |
| 受影响任务/文件 |  |
| 回退方案 |  |
| 最终状态 | 待批准/批准/拒绝/已回退 |

