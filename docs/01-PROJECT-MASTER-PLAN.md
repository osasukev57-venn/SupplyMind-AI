# SupplyMind AI 项目总计划书

> 文档编号：SMA-PLAN-001  
> 版本：v1.4  
> 状态：项目执行基线  
> 基线日期：2026-08-08  
> 适用范围：SupplyMind AI P0 开发、测试、验收、交付及后续 P1/P2 演进

## 0. 使用说明

本文件是后续 Codex 窗口、开发人员、测试人员和项目验收人员共同使用的自包含执行基线。开始任何实现任务前，必须先读取本文件、需求追踪矩阵、验收测试计划、开发任务清单、进度台账和决策日志。不得依赖聊天记忆补全需求，也不得以实现方便为由降低官方要求。

本文中的来源标签含义如下：

- **[A] 官方需求**：来自《新汇率监控需求书》正文。
- **[F] 项目方实施补充说明**：来自 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`，是对原需求书“数据获取方式”和实施优先级的正式补充。
- **[B] 官方验收**：来自需求书验收章节及已经编号固化的 H01-H09 验收要求。
- **[C] 架构冻结**：项目已经确认的实现边界，不冒充官方原文。
- **[D] 外部待确认**：必须由业务方、数据授权方或验收方确认，当前不得假设已解决。
- **[E] 增强功能**：P1/P2 演进内容，不得阻塞或抢占 P0。

发生冲突时，信息优先级固定为：**官方原始需求书及项目方正式补充 > 官方验收要求 > 已冻结架构决策 > 本总计划 > 具体开发任务 > 代码实现**。涉及数据获取方式和实施优先级时，[F] 是对 [A] 的正式解释；它不修改原文，也不降低 H01-H09。低优先级内容不得修改、弱化或替代高优先级内容。

### 0.1 D1-T02 Review 与 D1-T03 编码前基线对齐（[C]）

本节只冻结内部实施语义，不改写 `00-OFFICIAL-REQUIREMENTS.md` 或 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md` 的官方文本。

1. **任务完成不等于验收通过。** `TaskExecutionStatus=DONE` 只说明约定的调查、实现、任务级测试与证据归档已完成；正式门禁只由独立 `AcceptanceStatus` 的 AT 用例判定。D1-T02 的字段事实已确认，本轮 Code Review 曾因逐币种连通性重放证据不完整而将任务重开；实时 `TaskExecutionStatus` 只以 `docs/05-PROGRESS-LEDGER.md` 为准，`docs/04-DEVELOPMENT-TASKS.md`仅同步展示可领取性。无论实时状态如何，外部访问失败证据都不能使 PBOC 真实数据、Day 1/Day 2退出门禁或 AT-SRC-002 记为 `PASS`。
2. **数据生命周期使用两个字段。** `ProcessingStage` 描述记录处理到哪一步；`ValidationStatus` 描述校验结论。二者必须同时持久化，禁止合并为单一 `status` 字段。
3. **物理目录只有一套。** `normalized`、`published` 仅是逻辑阶段描述，不是 `data/` 下的物理目录；唯一目录树见第8.3节。
4. **格式分工只有一套。** config、raw、lifecycle、quarantine、warning、report、runtime、manifest 使用 JSON；processed daily 与月/季/半年/年 aggregate 使用 CSV。“JSON/CSV”表示系统整体使用这两类可检查文件，不表示每个业务结果重复写两种格式。
5. **D1-T03 不得自行补设计。** RawReceiptV1、LifecycleTimelineV1、manifest、dataRoot、日期路由、原子写与 BigDecimal 规则以第8.3至9节为唯一实现契约。

## 1. 项目背景

SupplyMind AI 的官方题目为“供应链成本监测与动态调价预警智能体”。项目面向 Windows 桌面环境，聚合中国人民银行、上海有色金属网（SMM）和亚洲有色金属网（Asian Metal）的权威数据，对汇率与原材料价格完成采集、核验、本地文件留存、多周期聚合、面板展示、预警和智能体辅助分析。[A]

项目方随后正式补充：Day 1 至 Day 2 优先完成中国人民银行 EUR/CNY、USD/CNY 的真实获取和本地存取闭环；指定大宗交易网站无法合法自动获取时，可依次采用同类免费公开信源、手动填写接口，不得绕过会员、登录、验证码或反爬机制。[F]

本项目的工程定位是“Java 后端 + AI 应用工程”，不是算法训练项目。P0 要证明的是：数据来源与模式可识别、计算可复算、文件可检查、动态配置可联动、Agent 有证据边界、Windows 用户可直接运行。[C]

## 2. 项目目标

### 2.1 业务目标

1. 优先每日自动获取并留存 PBOC EUR/CNY、USD/CNY；原材料按“合法指定源自动获取 → 免费公开信源 → 手动填写”形成可运行接入链路。[A][F]
2. 在数据进入正式业务链路前完成校验，未经校验的数据不得进入展示层。[A]
3. 持久化原始数据、每日加工结果、月度/季度/半年度/年度聚合结果和预警记录。[A][B]
4. 支持文件轮转、跨文件和跨年度读取、拼接、去重、排序。[A][B]
5. 支持用户动态停止、新增或替换监测标的，并联动采集、历史回填、计算、面板和预警。[A][B]
6. 通过受控 Agent 工具链对已验证数据进行解释、成本影响分析和预警说明。[C]

### 2.2 工程目标

1. 形成可维护的 Java 17 + Spring Boot 模块化单体。[C]
2. JSON/CSV 是唯一业务持久化方式，运行时无任何数据库进程。[B][C]
3. 所有数字计算使用 BigDecimal，保证可追溯、可复算、无 float/double 精度污染。[B][C]
4. 交付 Vue3 + Electron Windows 便携桌面应用，内置 JRE，用户双击 EXE 即可运行。[C]
5. 云端模型通过 LLMService 解耦；云模型不可用时 Java 模板报告仍可工作。[C]

## 3. 官方需求与硬性验收基线

### 3.1 官方功能基线

- [A] Windows 桌面端为主要运行平台。
- [A] 每日定时自动获取官方权威数据。
- [A] 汇率来源为中国人民银行，默认面板包含欧元和美元。
- [A] SMM 与 Asian Metal 原材料默认标的包含 ADC12、AZ91D。
- [A] 计算链路为每日加工均值、月度均值、季度均值、半年度均值、年度均值。
- [A] 通过多信源比对或校验规则确保准确性，所有未经校验的数据不得进入展示层。
- [A] 原始每日数据、多级均值数据、预警记录写入 JSON/CSV 文件。
- [A] 支持自动分卷和跨文件历史读取。
- [A] 提供完整工程源代码和本地化部署手册。

### 3.2 项目方实施补充说明

- [F] Day 1 至 Day 2 的第一开发目标是 PBOC EUR/CNY、USD/CNY 真实获取与 JSON/CSV 存取闭环。
- [F] 指定大宗网站存在合法公开数据或合法接口时优先自动获取。
- [F] 因会员限制、无公开接口或合法反爬机制无法自动获取时，允许使用同类免费公开信源；仍不可得时允许手动填写。
- [F] 禁止绕过登录、验证码、会员权限、访问控制或反爬机制。
- [F] 免费信源和手工数据必须显示真实来源，并经过与自动数据相同的 raw、标准化、校验、发布、加工、聚合和持久化链路。
- [F] SMM/Asian Metal 商业授权缺失只影响对应指定源自动采集能力，不再阻塞整个 P0。

### 3.3 H01-H09 验收要求

| 编号 | 不得弱化的验收要求 | 计划证据 |
|---|---|---|
| H01 [B] | 随机抽取历史自然月，展示指定货币和原材料的每日加工均值，并正确计算月度、季度、半年度、年度均值 | 黄金数据输入、页面截图、各级持久化文件、独立复算结果 |
| H02 [B] | 全链路计算准确且无精度流失 | BigDecimal 单元/属性测试、边界小数数据、前后端字符串比对 |
| H03 [B] | 程序目录中可直接检查规定格式的 JSON/CSV 文件 | 发布包 `data/` 目录清单、文件内容截图、release-manifest |
| H04 [B] | 系统运行时不存在 MySQL、Redis、SQLite、H2 或其他隐藏数据库运行进程 | Windows 进程清单、端口检查、依赖审计、干净机录像 |
| H05 [B] | 修改系统时间触发跨期后，自动创建新的轮转文件 | 时间前跳/跨月测试录像、新文件与元数据证据 |
| H06 [B] | 多份历史轮转文件存在时，可跨年度读取、拼接、去重和排序 | 跨年查询响应、页面截图、重复数据测试报告 |
| H07 [B] | 用户可动态停止或新增监测标的，不修改程序代码 | 配置变更录像、配置文件版本、采集调度变化 |
| H08 [B] | 新增标的后获取当日数据，并启动历史回填和历史均值计算 | 新增英镑等用例、回填任务记录、聚合文件 |
| H09 [B] | 配置后面板自动重构；旧标的隐藏、新标的显示、系统无异常且历史数据不删除 | 前后页面截图、历史文件检查、日志与健康检查 |

## 4. 交付范围与非交付范围

### 4.1 P0 必须交付

- 六类逻辑 DataProvider 边界及可验收的数据模式；10天内允许复用基础实现，但来源类型不得丢失。
- PBOC EUR/CNY、USD/CNY 真实自动获取、raw JSON、校验、每日加工、JSON/CSV、历史读取与多周期聚合闭环。
- 大宗原材料合法自动获取、免费公开信源、手动填写三层降级，以及实际来源追溯。
- 原始落盘、候选标准化、校验门禁、每日加工、五级持久化聚合。
- BigDecimal 精度规则、黄金数据测试和独立复算证据。
- 文件原子写入、轮转、校验元数据、隔离、恢复、跨年度查询。
- 动态标的配置、历史回填、面板联动和最小规则预警。
- Vue3 仪表盘、历史趋势、数据质量、导入、配置、预警、Agent 工作台。
- 受控 Agent 工具、EvidencePack、CloudLLMService 和 Java 模板降级。
- Electron、内置 JRE、Spring Boot 子进程管理和 Windows 便携 ZIP。
- 源码、README、部署/用户/数据/计算/Agent 文档和验收证据。

### 4.2 P0 明确不交付

- 未授权爬虫、绕过登录/验证码/会员限制或反爬机制。[C]
- MySQL、Redis、SQLite、H2 或任何隐藏数据库。[B][C]
- 作为最终用户前置条件的 Docker、Java、Node.js、Maven或数据库服务。[C]
- 微服务、消息队列、分布式事务和多实例文件并发写。[C]
- RAG、LoRA、正式本地模型、vLLM 或模型训练。[E]
- JavaFX 桌面端。[C]
- 让 LLM 判断数据有效性、直接计算均值/成本/风险或无证据推断原因。[C]
- 未经业务确认的正式调价执行；P0 只生成参考成本影响与预警说明。[D]

## 5. 冻结架构与技术栈

### 5.1 总体架构

采用一个 Spring Boot 工程的模块化单体，按业务包组织；Vue3 作为前端；Electron 负责桌面封装、内置 JRE 启动、Java 子进程生命周期和窗口管理。[C]

```text
Electron Windows 外壳
    ├─ 启动内置 JRE + Spring Boot 子进程
    ├─ 等待本地健康检查与动态端口
    └─ 展示 Vue3 页面
                    │
              Spring Boot API
                    │
  配置 ─ Provider ─ 采集 ─ 校验 ─ 加工/聚合 ─ 查询/预警
                                      │
                               Agent 工具与 LLM
                    │
      本地 JSON/CSV + manifest + 运行状态文件
```

### 5.2 技术栈

| 层 | 冻结选择 | 来源 |
|---|---|---|
| Java | Java 17 | [C] |
| 后端 | Spring Boot，单工程、模块化单体 | [C] |
| JSON/CSV | Jackson；CSV 使用 RFC 4180 兼容库并在 D1-T03 的构建基线中锁定版本。config/raw/lifecycle/quarantine/warning/report/runtime/manifest=JSON，daily/aggregate=CSV | [C] |
| 计算 | BigDecimal | [B][C] |
| 前端 | Vue3 | [C] |
| 桌面 | Electron | [C] |
| Java 运行时 | 发布包内置精简 JRE | [C] |
| 持久化 | 仅 JSON/CSV 与普通运行状态文件 | [B][C] |
| AI | LLMService；P0 CloudLLMService；LocalLLMService 扩展点 | [C] |
| 最终交付 | Windows 便携目录 + ZIP + 双击 EXE | [C] |

P0 禁止任何数据库栈或数据库文件，包括 MySQL、Redis、SQLite、H2、JPA、JDBC、R2DBC、MyBatis 及其驱动/服务；禁止将 Docker 变成开发或最终运行条件。[C]

## 6. 模块设计

| 模块 | 核心职责 | 主要输入/输出 |
|---|---|---|
| `config` | 监测标的、Provider、精度、规则、模式配置；版本与依赖检查 | JSON 配置、配置变更事件 |
| `provider` | OfficialWeb、AuthorizedApi、FreePublic、Manual、LocalImport、SyntheticDemo 六类逻辑入口 | 原始来源响应/文件/手工输入 → RawRecord |
| `ingestion` | 调度、手工触发、幂等、回填、任务状态 | RawReceipt、LifecycleRecord、job 文件 |
| `validation` | 模式、字段、单位、时间、范围、重复、多信源/规则校验 | `(ProcessingStage=VALIDATED, ValidationStatus=VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT)` |
| `processing` | 合法样本的每日加工均值 | 已发布样本 → daily CSV |
| `aggregation` | 月、季、半年、年度精确计算与持久化 | 有效每日加工值 → aggregate CSV |
| `storage` | 唯一物理目录、原子写、manifest、轮转、隔离、恢复 | raw/staging/quarantine/processed 等 JSON/CSV 与生命周期元数据 |
| `history` | 跨卷/跨年检索、拼接、去重、排序、来源追踪 | 时间范围 → 已验证序列 |
| `warning` | 确定性规则、风险等级、证据和预警记录 | 指标/阈值 → warning JSON |
| `agent` | 意图识别、受控工具编排、EvidencePack、报告核验 | 用户问题 → 结构化报告 |
| `llm` | 模型供应商解耦、云端调用、本地扩展、失败降级 | EvidencePack → 解释文本 |
| `web` | API、统一错误、健康检查、只读证据输出 | Vue/Electron 请求 |
| `desktop` | 动态端口、内置 JRE、单实例、进程退出与数据路径 | EXE 启停与发布包 |

模块间只能通过稳定应用接口和数据对象协作。Provider 不得直接写面板文件；LLM 不得直接访问 raw、文件系统或执行任意 SQL/Shell。[C]

## 7. 数据接入模式、三层降级与来源边界

### 7.1 DataProvider逻辑类型

1. `OfficialWebDataProvider`：PBOC 等合法公开官方网站数据。[F][C]
2. `AuthorizedApiDataProvider`：已获得合法授权的商业或官方 API。[F][C]
3. `FreePublicDataProvider`：无需绕过访问限制的同类免费公开信源。[F][C]
4. `ManualDataProvider`：用户通过受控表单录入实际来源数据。[F][C]
5. `LocalImportDataProvider`：导入合法 CSV/Excel 文件、历史回填文件或黄金数据。[F][C]
6. `SyntheticDemoDataProvider`：可复现的演示和测试数据。[C]

10 天实现可以让多类 Provider 共用解析、HTTP 或文件基础设施，但记录中的 `providerType`、`accessMethod`、`actualSourceName` 不得合并或丢失。[F][C]

### 7.2 PBOC第一优先级与原材料三层接入

- **PBOC**：Day 1 至 Day 2 必须完成 EUR/CNY、USD/CNY 的真实自动获取与本地文件闭环；它不等待原材料商业授权。[F]
- **原材料优先级1**：指定网站存在合法公开数据或合法接口时自动获取。[F]
- **原材料优先级2**：指定网站不可合法自动获取时，选择经过许可与字段映射审查的同类免费公开信源。[F]
- **原材料优先级3**：仍无合适免费信源时，启用 ManualDataProvider。[F]

P0材料监测序列固定为四条：`SMM意图×ADC12`、`SMM意图×AZ91D`、`Asian Metal意图×ADC12`、`Asian Metal意图×AZ91D`。`itemId`稳定标识“来源意图×材料”；发生降级时itemId不改写，`actualSourceName`必须显示实际免费源或手工来源，不能沿用指定商业源名称。[A][F][C]

三层降级是有记录、有配置版本的接入决策，不是运行时遇到网络失败便静默切换到任意网站。任何切换都必须记录 `routeDecision`、`fallbackReason`、生效时间和实际来源。[F][C]

### 7.3 正式数据与演示数据

- **可进入正式数据链的来源**：合法 OfficialWeb、AuthorizedApi、FreePublic，以及具有真实来源证据的 Manual/LocalImport；是否发布最终由校验状态决定。[F][C]
- **演示数据模式**：Synthetic 只能用于测试和演示；页面、导出、预警和 Agent 报告必须显著标注“演示数据”，不得冒充任何真实网站。[C]
- Manual、LocalImport 或 FreePublic 不是天然可信；它们必须与自动来源一样经过标准化和校验门禁。[F][C]

SMM、Asian Metal 无商业授权只使对应“指定商业源自动采集能力”不可用；它不阻塞 PBOC、文件、计算、面板、预警、Agent 或经过项目方认可的原材料降级链路。[F]

### 7.4 ManualDataProvider治理

手工请求由用户提交：`actualSourceName`、`itemId`、`businessDate`、`value`、`unit`、`currency`和非空`sourceReference`；operatorRef取已认证操作者上下文，不接受客户端冒充。系统在受理边界生成`inputAt`与`receivedAt`，固定`accessMethod=manual`，并令RawReceipt.updatedAt=receivedAt；客户端不得指定或回写这些审计字段。请求到RawReceiptV1的唯一映射为：`businessDate`原始词法同时写入`sourceBusinessDateRaw`，仅在可无歧义解析为ISO日期时写入`sourceBusinessDate`；`value`→`rawValue`、`unit`→`rawUnit`、`currency`→`rawCurrency`。可信业务日期和值只在标准化后进入CandidateV1；不得把请求字段直接当作已验证候选。提交后先写不可变RawReceipt，再创建通过`rawRef`关联的独立初始LifecycleRecord（`RECEIVED + PENDING`），不得把可变生命周期字段写进raw，也不得直接进入面板。[F][C]

```text
ManualDataProvider
→ 不可变 raw + 独立 LifecycleRecord（RECEIVED + PENDING）
→ 解析/标准化候选（PARSED + PENDING）
→ 校验（VALIDATED + VERIFIED / VERIFIED_WITH_NOTICE / REJECTED / CONFLICT）
→ 发布（PUBLISHED + VERIFIED / VERIFIED_WITH_NOTICE）
→ 每日加工
→ 聚合
→ JSON/CSV
→ 面板 / 预警 / Agent
```

### 7.5 来源真实性

免费公开信源必须展示真实网站名称和可核验 URL/引用；手工数据必须展示实际来源与输入方式。前端、文件、预警和 EvidencePack 不得把替代来源改名为 SMM、Asian Metal 或其他指定商业网站。[F][C]

## 8. 文件数据链路

### 8.1 冻结处理顺序

```text
数据获取
→ 保存原始记录
→ 标准化候选数据
→ 数据校验
→ 校验后发布
→ 每日加工
→ 月度/季度/半年度/年度聚合
→ 聚合文件持久化
→ 规则预警
→ 面板、历史查询和 Agent 使用
```

任何模块不得绕过校验发布门禁。[A][C]

### 8.2 双维数据生命周期（冻结）

每条参与处理的 `LifecycleRecord` 必须同时持久化 `processingStage` 与 `validationStatus`；二者分别回答“处理走到哪里”和“校验结论是什么”，禁止用一个通用 `status` 字段代替。原始 raw 不保存生命周期状态且不可变；`staging/<runId>.json` 保存 Lifecycle timeline 的全部有序版本，生命周期变更通过带 `rawRef`、`runId`、`recordVersion` 的快照追加留痕，不能只保留当前版本。

| ProcessingStage | 含义 |
|---|---|
| `RECEIVED` | 原始响应或手工输入已经写入不可变 raw。 |
| `PARSED` | 原始内容已解析、标准化为候选记录。 |
| `VALIDATED` | 校验已经完成，并已产生可发布或不可发布的结论。 |
| `PUBLISHED` | 发布动作已经完成，记录可进入业务读模型。 |

| 允许组合 | 含义与可见性 |
|---|---|
| `RECEIVED + PENDING` | raw 已落盘，等待解析；不可见。 |
| `PARSED + PENDING` | 已标准化为候选，等待规则或人工校验；不可见。 |
| `RECEIVED + REJECTED` | 解析或基础格式失败；保留 raw 与原因并隔离；不可见。 |
| `VALIDATED + VERIFIED / VERIFIED_WITH_NOTICE` | 校验通过并具备发布资格；尚未发布，不进入业务读模型。 |
| `VALIDATED + REJECTED / CONFLICT` | 校验终态；进入隔离区，不可见。 |
| `PUBLISHED + VERIFIED / VERIFIED_WITH_NOTICE` | **唯一**允许每日加工、聚合、API、Dashboard、预警和 Agent 使用的组合。 |

`PUBLISHED + PENDING / REJECTED / CONFLICT` 一律非法；任何非 `PUBLISHED` 记录不得进入业务读模型。仅沿合法迁移边推进时才追加连续`recordVersion`；任何终态后的修订或重试必须创建新的`runId`，不得在终态timeline追加版本、回退阶段或覆盖raw。

校验失败的新数据不得覆盖已有合法历史值。面板可继续显示上一条合法值，但必须同时显示该值的业务日期、来源、过期状态和当前质量问题。[C]

### 8.3 冻结唯一物理数据目录结构

以下目录属于实现冻结方案而非官方原文：[C]

```text
SupplyMindAI/
  SupplyMindAI.exe
  runtime/                         # 内置 JRE 与后端运行组件；不是业务数据
  app/                             # Electron、Spring Boot JAR 与只读发布资源
  data/
    config/monitor-series.json     # 唯一活动监测序列/精度配置文件
    config/history/<configVersion>.json # 每个已生效configVersion的不可变审计快照
    raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json      # 不可变原始收据
    staging/<runId>.json                                        # 单item完整Lifecycle timeline
    quarantine/<itemId>/YYYY-MM/<runId>.json                    # REJECTED / CONFLICT证据投影
    processed/daily/<itemId>/YYYY-MM.csv                        # PUBLISHED后生成的每日结果
    processed/aggregate/<itemId>/month/YYYY.csv
    processed/aggregate/<itemId>/quarter/YYYY.csv
    processed/aggregate/<itemId>/halfyear/YYYY.csv
    processed/aggregate/<itemId>/year/YYYY.csv
    warning/YYYY-MM/<warningId>.json
    report/YYYY-MM/<reportId>.json
    runtime/jobs/active/*.json                                   # 任务恢复状态，不是根runtime/
    runtime/jobs/history/YYYY-MM/*.json
    runtime/dirty/*.json
    runtime/conflicts/raw/<itemId>/YYYY-MM/<runId>/<conflictId>.json # raw异hash冲突完整证据
  logs/
  licenses/
```

不得新增 `normalized/`、`published/`、`monthly/`、`quarterly/`、`half-year/`、`yearly/`、`alerts/` 或 `state/` 等竞争性物理目录。`normalized` 仅表示 `PARSED` 的逻辑结果，`published` 仅表示 `PUBLISHED` 的逻辑阶段；月、季、半年、年度结果只能位于 `data/processed/aggregate/<itemId>/` 下。`runtime/conflicts/raw`只保存不可覆盖raw发生异hash碰撞时的完整诊断证据，不是第二份正常raw、不是发布层，也不得被业务查询读取。

唯一配置项为 `supplymind.data-root`：解析后必须得到一个规范化绝对路径。自动测试必须显式注入独立临时目录；最终 Electron 必须显式传入 EXE 同级 `data/`；仅本地开发可默认 `${user.dir}/data`。目录不可写时 fail-fast，禁止静默回退到用户目录、隐藏目录或第二个 data 目录。验收版默认使用便携应用目录，使验收人员可直接检查。[B][C]

### 8.4 文件 schema v1 与生命周期元数据（D1-T03 唯一契约）

#### 8.4.1 Wire value 与路径安全

- `mode` 只允许 `formal`、`demo`、`test`。
- `providerType` 只允许 `official_web`、`authorized_api`、`free_public`、`manual`、`local_import`、`synthetic_demo`。
- `accessMethod` 只允许 `public_official_html`、`authorized_api`、`free_public_web`、`manual`、`local_import`、`synthetic_demo`。
- providerType与accessMethod合法配对精确固定为：official_web↔public_official_html、authorized_api↔authorized_api、free_public↔free_public_web、manual↔manual、local_import↔local_import、synthetic_demo↔synthetic_demo；交叉配对全部非法。
- `routeDecision`只允许`primary`、`fallback_free_public`、`fallback_manual`、`direct_local_import`、`synthetic_demo`。
- `mode`、`providerType`、`itemId`均来自受校验配置；`runId`、`acquisitionId`、`recordId`、`conflictId`由应用生成。凡作为目录名或文件名的`mode`、`providerType`、`itemId`、`runId`、`conflictId`都必须匹配`[A-Za-z0-9._-]+`；所有ID均非空且不得含路径分隔符。`rawRef`等引用统一使用相对dataRoot的`/`分隔路径，拒绝绝对路径、`..`和目录穿越。

#### 8.4.2 RawReceiptV1（JSON）

| 字段 | 类型/格式 | 必填与语义 |
|---|---|---|
| schemaVersion | string，固定 `"1.0"` | 必填；JSON数字`1.0`非法 |
| rawRef | string | 必填；必须逐字等于由mode/providerType/itemId/receivedAt/runId计算出的`raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json`，不得信任调用方自定义路径 |
| acquisitionId | string | 必填；一次外部响应/手工提交/文件导入的获取标识，可被多个 item 共享 |
| runId | string | 必填；单 item 逻辑处理标识 |
| mode | enum | 必填；见8.4.1 |
| providerType | enum | 必填；见8.4.1 |
| accessMethod | enum | 必填；见8.4.1 |
| configVersion | positive integer | 必填；本次获取/输入开始时的monitor-series配置版本，生命周期内不变 |
| actualSourceName | string | 必填；真实来源显示名，不是封闭枚举 |
| sourceUrl、sourceReference | nullable string | OfficialWeb/AuthorizedApi/FreePublic的sourceUrl必须是非空绝对http(s) URL；Manual/LocalImport的sourceReference必须非空，sourceUrl可为null；SyntheticDemo的sourceReference必须是fixture ID且sourceUrl为null |
| itemId | string | 必填；一个 RawReceipt 只对应一个 item |
| sourceBusinessDateRaw | nullable string | 来源日期原文 |
| sourceBusinessDate | nullable string `YYYY-MM-DD` | 能无歧义解释时填写；标准化后才产生可信 `businessDate` |
| sourcePublishedAtRaw | nullable string | 来源发布时间原文 |
| sourcePublishedAt | nullable ISO-8601 offset datetime | 能解释时填写 |
| receivedAt | ISO-8601 offset datetime | 必填；系统实际接收时间 |
| inputAt | nullable ISO-8601 offset datetime | Manual/LocalImport必填；其他Provider为null |
| rawValue、rawUnit、rawCurrency | nullable string | item 级原始词法值、单位、值的计价币种；禁止用 BigDecimal 回写改写。汇率序列的rawCurrency固定表示quoteCurrency，不表示baseCurrency |
| operatorRef | nullable string | Manual 时必填，其他来源可为 null |
| httpStatus | nullable integer | HTTP 来源必填；未获得 HTTP 响应时不得伪造 raw |
| contentType | string | 六类Provider均必填；具体媒体类型见本表后provider语义 |
| payloadEncoding | string，固定 `base64` | 必填 |
| payloadBase64 | string | 必填；完整原始实体字节，不是字段摘录 |
| payloadSha256 | 64位小写十六进制 string | 必填；仅对解码后的原始实体字节计算 |
| matchAnchor | nullable string | 自动解析时保存 item 级匹配锚点 |
| updatedAt | ISO-8601 offset datetime | 必填；创建时等于 `receivedAt`，之后永不修改 |

`payloadBase64`的provider语义唯一固定：OfficialWeb/AuthorizedApi/FreePublic保存外部HTTP响应实体原始字节（不含响应头）；Manual保存服务端收到的原始请求实体字节；LocalImport保存原导入文件完整字节；SyntheticDemo保存有版本的fixture完整字节。不得把解析后的字段摘要重新序列化后冒充payload。`contentType`对六类Provider都必须非空；`httpStatus`只对三个外部HTTP Provider必填，Manual/LocalImport/SyntheticDemo必须为null。一次Manual请求或LocalImport文件产生多个item时，沿用“一次acquisitionId、每item独立run/raw/timeline、各raw重复完整payload bytes/hash”的同一基数规则。

一次 PBOC 响应包含 USD 与 EUR 时只产生一个共享 `acquisitionId`，但必须产生两个独立 `runId`、两个 item 级 RawReceipt 和两个 Lifecycle timeline；两个 RawReceipt 保存相同完整 payload bytes/hash，同时分别保存自己的 `rawValue` 与 `matchAnchor`。USD/CNY与EUR/CNY的`rawCurrency`均固定为quoteCurrency=`CNY`；baseCurrency分别为`USD`与`EUR`，只由对应series配置的`baseCurrency`显式承载，不得从displayName猜测；`rawUnit`分别为`CNY/1 USD`与`CNY/1 EUR`。不得让两个 item 竞争同一个 `staging/<runId>.json`。

#### 8.4.3 LifecycleTimelineV1（staging JSON）

顶层字段固定为：`schemaVersion`字符串`"1.0"`、`recordId` string、`runId` string、`rawRef` string、`currentRecordVersion`正整数、`records` array。`records`是按`recordVersion`从1连续递增的不可删除非空快照数组；必须始终满足`currentRecordVersion == records.size == records最后一项.recordVersion`。每个快照包含正整数`recordVersion`、enum `processingStage`、enum `validationStatus`、nullable object `candidate`、nullable string `reasonCode`、nullable string `validationVersion`、nullable ISO-8601 offset datetime `validatedAt`、nullable同格式`publishedAt`、nullable string `publishRef`、必填同格式`updatedAt`。`runId`和`rawRef`必须与所引用RawReceipt逐字一致；updatedAt按recordVersion非递减。追加新版本后原子替换timeline文件，但必须保留所有旧版本；幂等重放不得追加相同状态的重复快照。

`candidate`使用CandidateV1，字段全部必填：`itemId` string、`businessDate` string `YYYY-MM-DD`、`value`精确十进制string、`currency` string、`unit` string、`providerType` enum、`actualSourceName` string、`accessMethod` enum、`normalizationVersion` string。`currency`始终表示值的计价币种；汇率候选固定为quoteCurrency，因此PBOC双币均为`CNY`，baseCurrency不复制进CandidateV1而由item配置显式给出。`RECEIVED+PENDING/REJECTED`时candidate必须为null；从`PARSED+PENDING`开始candidate必须非null，且同一runId内后续VALIDATED/PUBLISHED快照必须逐字段保持相同。候选纠错必须创建新runId，不得改写既有CandidateV1。这样不创建normalized/published目录，也能在重启后从PUBLISHED timeline确定性生成daily。

允许组合的完整白名单只有：`RECEIVED+PENDING`、`PARSED+PENDING`、`RECEIVED+REJECTED`、`VALIDATED+VERIFIED`、`VALIDATED+VERIFIED_WITH_NOTICE`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT`、`PUBLISHED+VERIFIED`、`PUBLISHED+VERIFIED_WITH_NOTICE`；其余4×5组合全部拒绝。recordVersion=1的唯一初态是`RECEIVED+PENDING`。后续唯一允许迁移边为：`RECEIVED+PENDING→PARSED+PENDING`、`RECEIVED+PENDING→RECEIVED+REJECTED`、`PARSED+PENDING→VALIDATED+VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT`、`VALIDATED+VERIFIED→PUBLISHED+VERIFIED`、`VALIDATED+VERIFIED_WITH_NOTICE→PUBLISHED+VERIFIED_WITH_NOTICE`；禁止跳级、回退、自迁移或跨VERIFIED状态改写。`RECEIVED+REJECTED`、`VALIDATED+REJECTED/CONFLICT`和两种PUBLISHED组合均为终态。

条件必填矩阵同样冻结：RECEIVED/PARSED快照的`validationVersion`与`validatedAt`必须为null，VALIDATED/PUBLISHED快照二者必须非null且进入PUBLISHED时保持不变；只有RECEIVED+REJECTED、VALIDATED+REJECTED/CONFLICT及两种VERIFIED_WITH_NOTICE的`reasonCode`必须非空，其余组合必须为null；PUBLISHED前`publishedAt`与`publishRef`必须为null，PUBLISHED时二者必须非null，且`publishRef`固定为`staging/<runId>.json#recordVersion=<当前版本>`。同run进入PUBLISHED时CandidateV1、validationVersion、validatedAt及已有reasonCode均不得改写。

#### 8.4.4 QuarantineProjectionV1（JSON）

QuarantineProjectionV1仅在`RECEIVED+REJECTED`、`VALIDATED+REJECTED`或`VALIDATED+CONFLICT`终态生成；PENDING、具备发布资格的VALIDATED及PUBLISHED记录绝不生成quarantine。字段与稳定输出顺序固定为：`schemaVersion` string固定`"1.0"`、`quarantineRef` string、`itemId` string、`runId` string、`rawRef` string、`stagingRef` string、`terminalRecordVersion`正整数、`processingStage` enum、`validationStatus` enum、`reasonCode`非空string、`validationVersion` nullable string、`rawPayloadSha256` 64位小写十六进制string、`rawFileSha256`同格式string、`receivedAt` ISO-8601 offset datetime、`quarantinedAt`同格式。`validationVersion`仅RECEIVED+REJECTED时为null，两个VALIDATED终态时必填。

`quarantineRef`必须逐字等于按RawReceipt.receivedAt的Asia/Shanghai年月计算出的`quarantine/<itemId>/YYYY-MM/<runId>.json`；`stagingRef`固定为`staging/<runId>.json`；`quarantinedAt`固定等于所投影终态快照的updatedAt，不得取重放时当前Clock。终态版本、状态、reasonCode和哈希必须能与timeline、raw及raw manifest逐项对账。投影不复制CandidateV1且不是权威历史；权威记录始终是staging timeline。投影采用CREATE_NEW不覆盖提交，相同hash重放幂等、不同hash报冲突；缺失投影可从完整terminal timeline与raw/manifest确定性重建，并生成相邻manifest。

#### 8.4.5 业务文件与 manifest

- **JSON v1编码**：UTF-8无BOM、LF换行、文件末尾恰好一个换行；schema规定字段按稳定顺序输出，nullable字段显式写`null`，不得因值为空静默省略。字符串只对引号、反斜杠和JSON规定的控制字符做标准转义，`/`不转义，非ASCII字符直接写UTF-8且不做Unicode归一化；数字类型不得加引号，精确业务十进制字段按schema使用字符串。
- **CSV v1编码**：UTF-8无BOM、逗号分隔、CRLF行结束、首行且仅一行固定表头、RFC 4180转义；null写空字段，精确十进制使用`toPlainString()`文本且不加千分位。
- **监测配置**：唯一活动文件为`data/config/monitor-series.json`。顶层必填：`schemaVersion` string固定`"1.0"`、`configVersion`正整数、`mode` enum、`updatedAt` ISO-8601 offset datetime、`items` array；mode使用8.4.1 wire值并作为全部raw路径的唯一运行模式来源。每个item的以下字段都必须出现：`itemId` string、`displayName` string、`enabled` boolean、`sourceIntent`非空string、`providerType` enum、`accessMethod` enum、`actualSourceName` string、`routeDecision` enum、nullable string `fallbackReason`、`routeEffectiveAt` ISO-8601 offset datetime、nullable string `supersedesItemId`、`externalCode` string、nullable string `sourceFieldKey`、nullable string `rateKind`、`calculationVersion`非空string、`calculationScale`非负整数、`displayScale`非负整数、`roundingMode` Java RoundingMode enum string、`calendarVersion`非空string、`currency` string、nullable string `baseCurrency`、`unit` string。providerType/accessMethod和routeDecision使用8.4.1 wire值；`fallback_free_public`只配free_public，`fallback_manual`只配manual，`direct_local_import`只配local_import，`synthetic_demo`只配synthetic_demo，`primary`只配official_web或authorized_api。两个fallback决策的fallbackReason必须非空，其他决策必须为null。supersedesItemId仅替换新item时非null且不得等于自身；旧item只改enabled=false，不改语义字段。`currency`是值的计价币种；`baseCurrency`仅汇率类非null，材料类为null。

  发布默认配置固定为schemaVersion=`"1.0"`、configVersion=1、mode=`formal`，且两个PBOC item均enabled=true。PBOC itemId正式冻结为`FX.USD.CNY.PBOC_MID`和`FX.EUR.CNY.PBOC_MID`，displayName分别为`美元/人民币中间价`和`欧元/人民币中间价`；共同使用sourceIntent=`PBOC`、providerType=`official_web`、accessMethod=`public_official_html`、actualSourceName=`中国人民银行官网（授权中国外汇交易中心公布）`、routeDecision=`primary`、fallbackReason=null、supersedesItemId=null、rateKind=`人民币汇率中间价`，初始routeEffectiveAt等于配置updatedAt；USD项externalCode=`USD`、sourceFieldKey=`1美元对人民币`、currency=`CNY`、baseCurrency=`USD`、unit=`CNY/1 USD`，EUR项对应为`EUR`、`1欧元对人民币`、`CNY`、`EUR`、`CNY/1 EUR`。`quoteCurrency`只是业务概念，运行JSON不新增该字段，统一由currency承载。PBOC双币生产初始计算配置固定为calculationVersion=`arithmetic-mean-v1`、calculationScale=8、displayScale=4、roundingMode=HALF_UP、calendarVersion=`weekday-asia-shanghai-v1`；GD-01验收夹具必须显式覆盖为calculationScale=12、displayScale=9、roundingMode=HALF_UP、calendarVersion=`golden-calendar-v1`，不得把夹具覆盖冒充生产默认值。`weekday-asia-shanghai-v1`只是EXT-06确认前的可替换实现默认，不能据此宣称正式节假日口径已获确认。

  items中的itemId必须唯一，持久化时按itemId Unicode code point升序；每次成功提交语义变更时configVersion必须在上一已提交版本基础上恰好+1，updatedAt由服务端Clock生成。无语义变化的幂等请求不得增加版本。运行中任务使用启动时版本完成或安全取消，并把该configVersion写入每个RawReceipt；新配置只影响之后启动的任务。

  每个已生效配置版本必须以与当时活动`monitor-series.json`逐字节相同的内容，CREATE_NEW写入`data/config/history/<configVersion>.json`并生成相邻manifest；`<configVersion>`使用正整数的无前导零十进制文本。历史快照不可覆盖、不可删除、不可被调度器当作第二活动配置。RawReceipt.configVersion必须能唯一解析到该快照。初始版本与之后每个成功语义变更都必须有快照；因此历史routeDecision、fallbackReason、routeEffectiveAt、actualSourceName、精度与日历规则均可长期恢复。仅有活动文件可以原子替换，历史快照不是“第二配置入口”。重试遇到同configVersion且业务字节/hash相同视为幂等，可在验证业务文件后补建/修复manifest；同configVersion但业务字节/hash不同必须fail closed并保留dirty/日志证据，绝不能改写快照或激活冲突内容。
- **Daily CSV 固定表头**：schemaVersion、businessDate、itemId、providerType、actualSourceName、accessMethod、processingStage、validationStatus、validationVersion、configVersions、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、sum、validCount、avg、expectedCount、missingCount、complete、currency、unit、inputRefs、updatedAt；schemaVersion单元格固定文本`"1.0"`。processingStage固定`PUBLISHED`，validationStatus只允许`VERIFIED`或`VERIFIED_WITH_NOTICE`。`currency`是值的计价币种；汇率daily因此写业务概念上的quoteCurrency，即运行字段`currency=CNY`。以itemId+providerType+actualSourceName+accessMethod+businessDate+currency+unit+validationStatus+validationVersion+calculationVersion+calculationScale+displayScale+roundingMode+calendarVersion分组，不同来源、单位、币种、校验结论或计算上下文不得混成一行。`configVersions`是RFC 4180转义的紧凑JSON正整数数组（示例`[1,2]`），去重后数值升序，必须等于全部inputRefs所引用RawReceipt.configVersion的集合。`inputRefs`是RFC 4180转义的紧凑JSON数组，wire示例固定为`[{"runId":"...","rawRef":"...","recordVersion":4}]`，对象字段顺序固定为runId、rawRef、recordVersion，数组按runId、rawRef、recordVersion升序，覆盖全部validCount输入；recordVersion必须精确指向该run的PUBLISHED快照，且在LifecycleTimeline schema v1的唯一迁移链中固定为4，禁止指向RECEIVED/PARSED/VALIDATED、使用其他版本号或只保留一个rawRef。`updatedAt`按DEC-052为确定性语义：等于该daily行全部有效PUBLISHED输入的`max(publishedAt)`（按Instant比较后统一转换为Asia/Shanghai的ISO-8601 offset datetime），不是processing执行时间；相同逻辑输入跨执行时间重算必须产生逐字节相同的CSV与fileSha256；缺少合法publishedAt时fail-closed，不得回退当前Clock/businessDate/validatedAt/文件mtime。
- **Aggregate CSV 固定表头**：schemaVersion、grain、periodStart、periodEnd、itemId、providerType、actualSourceName、accessMethod、validationStatus、validationVersion、configVersions、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、sum、validCount、avg、min、max、expectedCount、missingCount、complete、qualityStatus、currency、unit、sourceFingerprint、inputRefs、calculatedAt；schemaVersion单元格固定文本`"1.0"`。validationStatus只允许`VERIFIED`或`VERIFIED_WITH_NOTICE`；qualityStatus只允许`COMPLETE`或`INCOMPLETE`并必须分别与complete=true/false逐字对应。以itemId+providerType+actualSourceName+accessMethod+validationStatus+validationVersion+grain+periodStart+periodEnd+currency+unit+calculationVersion+calculationScale+displayScale+roundingMode+calendarVersion分组，不同来源/规格/单位/币种、校验结论/版本或计算上下文不得混算；同一period允许因来源、校验结论/版本或计算上下文切换存在多行。`configVersions`是全部被引用daily行configVersions的去重数值升序并集，wire规则与daily相同。`sourceFingerprint`固定为UTF-8无BOM、无空白、无末尾换行、对象字段顺序为providerType、actualSourceName、accessMethod并使用上述JSON v1字符串转义的紧凑JSON（wire示例`{"providerType":"official_web","actualSourceName":"...","accessMethod":"public_official_html"}`）之SHA-256小写十六进制。`inputRefs`是RFC 4180转义的紧凑JSON数组，wire示例固定为`[{"dailyFileRef":"...","businessDate":"YYYY-MM-DD","validationVersion":"...","fileSha256":"..."}]`，对象字段顺序固定为dailyFileRef、businessDate、validationVersion、fileSha256，数组按businessDate、dailyFileRef、validationVersion、fileSha256升序；结合aggregate行的item/source/validationStatus/calculation上下文后必须唯一定位每个参与daily行，并覆盖全部输入。
- **CSV数据行规范顺序**：除固定表头外，daily数据行按businessDate、itemId、providerType、actualSourceName、accessMethod、validationStatus、validationVersion、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、currency、unit依次升序；aggregate数据行按grain、periodStart、periodEnd、itemId、providerType、actualSourceName、accessMethod、validationStatus、validationVersion、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、currency、unit依次升序。日期按ISO文本、scale按整数数值、其余string按Unicode code point比较；完整分组键保证无平局。相同逻辑输入无论遍历/线程顺序如何都必须产生逐字节相同CSV与fileSha256。
- **Warning JSON**：warningId、ruleId/version、subjectId、period、current/baseline/change、threshold、severity、evidence refs、status、createdAt/updatedAt。
- 每个最终业务JSON/CSV文件的manifest与目标文件同目录，命名为`<完整业务文件名>.manifest.json`，不新增manifest顶层目录。manifest文件自身、临时/备份文件、runtime dirty marker均不得再生成manifest；禁止递归manifest。必填字段：`schemaVersion` string固定`"1.0"`、`fileName` string、`fileSha256` 64位小写十六进制string、`byteLength`非负整数、nullable非负整数`rowCount`、nullable `YYYY-MM-DD`字符串`minBusinessDate/maxBusinessDate`、`sourceRunIds` string array、`generatedAt` ISO-8601 offset datetime、`commitState` string固定`COMMITTED`。sourceRunIds去重后按Unicode code point升序；无来源run的配置/运行状态文件写空数组。`fileSha256`哈希最终业务文件字节，与raw的`payloadSha256`不得混用；`commitState`仅表示文件提交状态，不是ProcessingStage/ValidationStatus。

  ManifestV1派生口径固定：fileName只写同目录目标业务文件的basename，不写dataRoot相对路径；JSON的rowCount固定null，CSV的rowCount为数据行数且不含唯一表头；JSON的minBusinessDate/maxBusinessDate固定null，daily CSV分别取全部数据行businessDate的最小/最大值，aggregate CSV分别取全部数据行periodStart的最小值和periodEnd的最大值，空CSV二者均为null。raw/timeline/quarantine的sourceRunIds为单元素runId；daily为全部inputRefs.runId并集；aggregate为全部被引用daily manifest的sourceRunIds并集；RawConflictEvidence为其runId；config、runtime dirty/jobs等无业务run文件为空数组；warning/report取其证据引用可解析到的runId并集。generatedAt由服务端Clock记录本次提交或修复时间。

schema v1由上述字段、黄金文件和数据字典共同约束。D1-T03必须创建`docs/data-dictionary/FILE-SCHEMA-V1.md`、`docs/data-dictionary/CALCULATION-RULES.md`及`backend/src/test/resources/contracts/v1/{valid,invalid}/`，至少覆盖RawReceiptV1、LifecycleTimelineV1/CandidateV1、活动monitor-series与不可变history快照、QuarantineProjectionV1、manifest、daily/aggregate codec；这些工件必须与本节逐字段一致，不能引入第二套schema。`CALCULATION-RULES.md`按calculationVersion/calendarVersion追加版本条目且不得改写已使用条目；D1-T03只冻结字段、codec、引用和默认/黄金版本定义，不得提前实现D2的daily或aggregate计算。

### 8.5 原子写、幂等与恢复

1. 应用启动时必须在dataRoot内用同目录探针完整验证Java NIO `ATOMIC_MOVE`循环：已有probe target原子移到事务probe.bak，使target不存在，再把probe.tmp原子移到target，最后验证字节并清理。若任一步不支持、目录不可写或只读，必须在任何业务写入前fail-fast；禁止依赖`ATOMIC_MOVE+REPLACE_EXISTING`对已存在目标的provider特定行为，也禁止静默降级为普通move或第二dataRoot。
2. 每次文件提交使用唯一`transactionId`并先以CREATE_NEW写`runtime/dirty/<transactionId>.json`。DirtyMarkerV1顶层字段稳定顺序固定为：`schemaVersion` string固定`"1.0"`、`transactionId` string、`transactionType` enum、`createdAt` ISO-8601 offset datetime、`markerRevision`正整数、`transactionPhase` enum、`targets` array。初始markerRevision固定为1；每次持久化状态推进恰好+1，不得复用、回退或跳号。transactionType只允许`SINGLE_FILE`、`CONFIG_ACTIVATION`、`AGGREGATION_BATCH`；transactionPhase只允许`OPEN`、`COMMITTED`。每个target字段顺序固定为：`order`从1连续递增的正整数、`role` enum、`dataRef`相对dataRoot路径、`manifestRef`相对dataRoot路径、`expectedFileSha256`、nullable `oldFileSha256`、`targetPhase` enum；role只允许`BUSINESS_FILE`、`CONFIG_HISTORY`、`CONFIG_ACTIVE`，targetPhase只允许`PREPARED`、`DATA_COMMITTED`、`MANIFEST_COMMITTED`。targets按order升序；单文件恰一项，配置激活恰两项且CONFIG_HISTORY order=1、CONFIG_ACTIVE order=2，聚合批次按dataRef Unicode code point升序分配order。创建后，除markerRevision、transactionPhase和各targetPhase按合法方向推进外，其他字段逐字不可变。dirty marker自身不生成manifest；其状态更新使用固定同目录候选`runtime/dirty/.<transactionId>.json.marker.tmp`与备份`runtime/dirty/.<transactionId>.json.marker.bak`，先force并校验下一revision的tmp，再清理已确认低版本bak、把canonical marker原子移到bak使目标为空，最后把tmp原子移到canonical。
3. 每个target提交前先完整写业务tmp并flush/force、关闭、重读schema与hash，再写对应manifest tmp并同样force/校验。目标同目录临时文件固定命名为`.<目标完整文件名>.<transactionId>.tmp`，备份固定为`.<目标完整文件名>.<transactionId>.bak`；不得使用无法归属事务的随机残留名。业务文件必须先于manifest执行原子move；业务文件提交成功后把该targetPhase改为`DATA_COMMITTED`，manifest提交并复核后改为`MANIFEST_COMMITTED`。全部target均为MANIFEST_COMMITTED后才把transactionPhase改为COMMITTED；最终逐项复核成功后才删除备份和dirty marker。任一中间崩溃均不可对业务读取可见。
4. RawReceipt、QuarantineProjection和配置history快照业务文件采用目标不存在的CREATE_NEW/不覆盖原子提交，绝不能`REPLACE_EXISTING`。更新可变JSON/CSV（包括活动config、Lifecycle timeline、daily、aggregate、warning）时，持锁后必须先把旧正式目标用ATOMIC_MOVE移动到本事务`.bak`使正式目标不存在，再把已校验tmp用ATOMIC_MOVE移到正式目标；崩溃时按dirty marker完成或把bak原子恢复，业务读取在事务未闭合时fail closed。所有manifest都是对应业务文件的派生完整性元数据，即使业务文件不可变，其manifest也按同样的“旧manifest→bak、tmp→空目标”方式原子创建或修复；修复manifest绝不得改写对应业务文件，且manifest仍不得先于业务文件提交。
5. 配置变更使用transactionType=`CONFIG_ACTIVATION`的同一dirty事务覆盖“新history数据+manifest、活动monitor-series数据+manifest”：先完成order=1的CONFIG_HISTORY，再完成order=2的CONFIG_ACTIVE；活动target达到MANIFEST_COMMITTED是配置激活点，调度任务只能在该点之后读取新版本。激活前崩溃时，若新快照、旧活动文件和dirty预期均有效，恢复必须优先确定性完成激活；无法完成时只能在marker证明该版本从未激活且不存在RawReceipt引用后移除该事务创建的未激活快照。激活后不得删除快照，只能完成活动manifest或恢复为与快照逐字一致的活动文件。
6. raw目标已存在时，现有业务文件hash与incoming完整文件hash相同视为幂等成功，可补建/修复manifest但不得改写raw；hash不同则现有raw与manifest原样保留，并以CREATE_NEW写`runtime/conflicts/raw/<itemId>/YYYY-MM/<runId>/<conflictId>.json`及相邻manifest。RawConflictEvidenceV1字段固定为`schemaVersion="1.0"`、`conflictId`、`itemId`、`runId`、`existingRawRef`、`existingFileSha256`、`incomingFileSha256`、`incomingReceipt`完整RawReceiptV1对象、`detectedAt`；YYYY-MM按incoming.receivedAt的Asia/Shanghai路由。写完冲突证据后抛出明确冲突，绝不让incoming进入正常业务链。
7. 启动恢复先执行DirtyMarkerV1自身的专用引导恢复，再恢复业务target。对同一transactionId的canonical marker、`.marker.tmp`与`.marker.bak`组成候选组：只接受schema有效、文件名与内含transactionId一致、不可变字段逐字相同且phase只单调前进的候选；按markerRevision选择最高版本。同一最高revision若字节不同、候选间revision跳号/回退、字段漂移或候选均无效，必须保留全部证据并fail closed。若最高版本不在canonical，先把其字节force到合法marker tmp；canonical存在时，清理已验证的低版本marker.bak后把旧canonical原子移到marker.bak使目标为空，再把marker tmp原子移到canonical；canonical不存在时直接把marker tmp原子移到该空目标。任一步再次崩溃时，下次启动重复同一算法。canonical恢复后才按marker确定性处理业务文件：业务文件已提交而manifest缺失/旧hash不符时，只有业务文件schema、内部hash和marker预期hash全部有效才可由tmp或确定性重建manifest；manifest存在而业务文件未提交时恢复同事务备份或删除未提交manifest；业务文件损坏或无法证明完整时恢复备份，raw无合法备份时fail closed并报告，不得展示。最终文件/manifest均有效才完成事务并清理canonical/tmp/bak marker候选。
8. 第7条的同transactionId、合法命名DirtyMarkerV1候选组是“无canonical dirty marker时不得自动采用tmp/bak”的唯一例外。除此之外，无dirty marker的孤立tmp/bak、manifest hash不匹配或无法归属事务的文件不得自动当作正式数据。可证明为已完成事务的残留才可删除；其余保留并报告运维冲突。任何manifest重建都必须重新验证目标schema、字节长度和fileSha256，旧manifest不得被当作业务真值。
9. 同一item+period使用单写锁；Electron/Spring Boot只允许一个写实例。获取幂等键至少包含provider、item、businessDate/采集窗口；同一次多item响应额外保留共享acquisitionId。聚合前建立dirty marker，全部下游文件及manifest成功后才清除。
10. 重复执行通过业务键upsert且不产生重复行。中断、文件占用、manifest缺失/陈旧、ATOMIC_MOVE不可用及启动恢复测试必须证明：不存在半文件、raw覆盖、无来源残留或损坏数据静默可见。

## 9. 精度与聚合规则

1. 所有业务数字只能使用 `new BigDecimal(String)` 从原始字符串构造；禁止 float/double 进入计算链路。[C]
2. 原始词法值原样保存；精确数值以字符串写 JSON/CSV，输出使用 `toPlainString()`，不得 `stripTrailingZeros()`，不得产生科学计数法；中间过程不得按面板显示精度提前舍入。[C]
3. 每个标的配置必须包含`calculationVersion`、非负整数`calculationScale`、非负整数`displayScale`、Java `RoundingMode`字符串、`calendarVersion`、币种和单位。CSV逐行显式保存这组计算上下文及其configVersions；任何历史行都能解析到不可变配置快照和`CALCULATION-RULES.md`中的追加式规则条目。[C]
4. Daily只选择同一分组内`PUBLISHED+VERIFIED`或`PUBLISHED+VERIFIED_WITH_NOTICE`的Candidate.value；按冻结inputRefs顺序以BigDecimal精确相加，`sum`不舍入并直接`toPlainString()`，`validCount`为实际参与的底层样本数。`avg = sum.divide(validCount, calculationScale, roundingMode)`，持久化时必须恰好保留calculationScale位小数。[C]
5. `arithmetic-mean-v1`是EXT-03确认前的可替换实现默认：单一已发布官方日值直接作为唯一合法样本，同日同来源/同口径多条观测使用算术平均；daily expectedCount=1、missingCount=`max(expectedCount-validCount,0)`、complete=`validCount>=expectedCount`。业务方若确认收盘价、加权均价或其他选择规则，必须新增calculationVersion和配置版本，不能改写`arithmetic-mean-v1`历史含义。[C][D]
6. 月、季、半年、年均直接从属于同一计算上下文的有效daily avg字符串重算：aggregate sum为这些daily avg的精确和，validCount为daily行数，avg按同一calculationScale/roundingMode除法；min/max取参与daily avg的精确最小/最大值。季度、半年、年度禁止读取月均值，任何层级都禁止读取displayScale结果作为输入。[C]
7. Aggregate expectedCount是calendarVersion在periodStart至periodEnd内定义的预期业务日期数，missingCount=`max(expectedCount-validCount,0)`、complete=`validCount>=expectedCount`；缺失或无效日绝不补0。`weekday-asia-shanghai-v1`仅把Asia/Shanghai的周一至周五列为预期日；`golden-calendar-v1`只把GD-01每月10日和20日列为预期日。EXT-06正式确认后新增日历版本，不得改写既有版本。[C][D]
8. `displayScale`表示API/UI固定展示小数位：Java仅在输出边界执行`setScale(displayScale, roundingMode).toPlainString()`；展示结果不得回写文件、进入后续计算或替代持久化avg。图表可生成仅供绘图的数值副本。[C]
9. Daily/aggregate在来源身份、规格、单位、币种、validationVersion或计算上下文不同的情况下必须分行；发生标的语义变化时创建新itemId。来源或计算规则切换可使同一period存在多行，不得为得到单行而混算。[C]
10. D2-T03进入DONE前必须关闭EXT-03与EXT-06，或由业务/验收方书面接受上述带版本的实现默认作为P0正式口径；在此之前可以完成通用实现与黄金测试，但不得宣称正式业务均值和完整率已获验收。[D]

五级结果固定为 daily CSV 加月/季/半年/年 aggregate CSV，全部必须持久化。仅在查询时临时计算、不持久化高级聚合结果不满足当前验收基线。[B][C]

## 10. 轮转、系统时间与历史查询

### 10.1 文件轮转

- 日加工文件按自然月分卷，例如 `2026-08.csv`。
- 高级聚合按年分区保存，避免单文件无限增长。
- raw的`YYYY/MM`与所有quarantine的`YYYY-MM`（不论隔离原因、业务日期是否有效）一律按`receivedAt`在Asia/Shanghai的年月路由；processed daily/aggregate才严格按已验证`businessDate`路由。
- 系统保存 lastWallClock、lastBusinessDate、lastPartitionKey；检测 Windows 睡眠、时间前跳和回拨。
- 跨月前跳时创建新分卷；回拨时按业务日期幂等更新对应旧分卷，不删除未来分卷。
- 来源业务日期异常时先进入 PENDING/CONFLICT，不得直接发布。

### 10.2 跨卷与跨年度查询

查询服务根据起止日期枚举涉及的分区文件，依次完成：存在性与 checksum 检查、解析、只保留 `ProcessingStage=PUBLISHED` 且 `ValidationStatus∈{VERIFIED, VERIFIED_WITH_NOTICE}` 的记录、业务键去重、日期排序、范围裁剪、质量与来源汇总。缺失分卷必须作为覆盖率问题返回，不能伪装成 0。

“跨卷”当前推荐解释为跨多个轮转文件；若验收方指跨 D:/E: 等物理卷，需按 EXT-09 外部事项确认。P0 不实现自动跨物理盘迁移。[D]

## 11. 动态标的与历史回填

1. 标的使用稳定 itemId，显示名称、Provider、外部代码、币种、单位、精度和启用状态均来自 JSON 配置。[C]
2. 新增标的后，配置模块通知调度、Provider、历史回填、聚合、告警和前端，不需要修改代码或重启。[B][C]
3. 停止监测采用 `enabled=false`；停止新任务并从默认面板隐藏，但保留全部历史文件。[B][C]
4. 替换标的必须创建新 itemId，可记录 supersedesItemId；禁止通过改名篡改旧数据语义。[C]
5. 被停用标的关联的成本项或规则进入 SUSPENDED/INVALID_DEPENDENCY，不级联删除。[C]
6. 新增标的启动当日采集和历史回填；回填范围、节假日口径和正式来源受外部确认约束。[B][D]
7. 运行中任务保留启动时 configVersion，新配置从下一任务生效，保证可追踪。[C]

## 12. 预警与动态调价边界

P0 预警必须由 Java 确定性规则产生并持久化。规则输入只能来自已验证日值和聚合文件；风险等级、阈值比较、成本影响均由 Java 计算。[C]

最小规则覆盖：价格/汇率相对基准周期变化、数据过期、数据质量问题。预警阈值、成本组成、单位换算和动态调价公式尚需业务确认；在确认前使用显式标记的演示规则，不得宣称为正式定价决策。[D]

## 13. Agent 与 LLM 设计

### 13.1 固定 Agent 流程

```text
用户问题
→ 标的和意图识别
→ Java 选择受控工具链
→ 查询已验证数据
→ Java 计算确定性指标
→ 构造 EvidencePack
→ LLM 解释、总结和形成建议
→ 后端核验证据引用
→ 保存结构化报告
```

### 13.2 P0 工具

- `series.resolve`
- `history.query`
- `period.metrics`
- `quality.inspect`
- `cost.impact`
- `warning.explain`
- `provenance.trace`

工具只读取 `ProcessingStage=PUBLISHED` 且 `ValidationStatus∈{VERIFIED, VERIFIED_WITH_NOTICE}` 的数据。EvidencePack 至少包含 item、时间范围、指标精确字符串、质量状态、来源文件和 checksum/版本引用。[C]

### 13.3 模型边界与降级

LLM 不得判断数据是否有效、读取未校验 raw、计算均值/成本、确定风险等级、编造来源或无证据解释外部原因。模型仅解释 Java 已计算的事实。[C]

`LLMService` 隔离厂商 SDK；P0 实现 `CloudLLMService`，保留 `LocalLLMService` 接口。云端不可用、断网或调用失败时，系统根据同一 EvidencePack 生成 Java 模板报告，Agent 工作台仍返回可验证结果。[C]

## 14. Vue3 与 Windows 桌面方案

### 14.1 Vue3 页面

- 仪表盘：默认标的、最新合法值、业务日期、过期状态、数据模式。
- 历史趋势：日/月/季/半年/年切换及跨年查询。
- 数据质量：待处理、拒绝、冲突、来源和校验说明。
- 文件导入：授权/本地文件导入和结果反馈。
- 动态配置：新增、停用、替换标的及依赖状态。
- 预警：规则结果、证据、状态。
- Agent 工作台：问题、工具轨迹摘要、EvidencePack 引用、模型/模板降级状态。

演示模式必须在所有相关页面持续显示“演示数据”。[C]

### 14.2 Electron 交付

Electron 主进程负责单实例控制、选择动态本地端口、启动内置 JRE 与 Spring Boot JAR、等待健康检查、展示 Vue 页面、退出时停止 Java 子进程。服务仅监听 `127.0.0.1`。[C]

最终发布目录包含 EXE、内置 JRE、Spring Boot JAR、Vue 构建产物、可检查的 data 目录、配置和文档。用户不需要安装 Java、Node.js、Maven、Docker 或数据库。JAR 是桌面应用内部组件，不能是唯一用户交付物。[C]

## 15. 10 天开发计划

Day 8 完成后冻结新增业务功能，只允许修复 P0 验收缺陷、补充证据和完成交付。[C]

| 日期 | 可执行任务 | 当日输出与退出条件 |
|---|---|---|
| Day 1 | `D1-T01` 纳入补充说明并冻结PBOC双币契约；`D1-T02` PBOC字段/连通性调查；`D1-T03` 最小Spring Boot与data/raw文件基础；`D1-T04` OfficialWeb真实获取；`D1-T05` EUR/USD raw闭环冒烟 | **退出条件仅为**EUR/CNY、USD/CNY真实获取并生成可追溯raw JSON；D1-T02失败证据仅是调查产物，不能使Day1或PBOC验收通过。 |
| Day 2 | `D2-T01` 标准化与基础校验；`D2-T02` PUBLISHED+VERIFIED类发布门禁；`D2-T03` 每日加工CSV；`D2-T04` 历史读取与四级aggregate CSV；`D2-T05` 调度/幂等/重启端到端验收 | 两个币种完成PBOC→raw JSON→PARSED/PENDING→VALIDATED→PUBLISHED+VERIFIED类→daily/aggregate CSV→重启读取；AT-SRC-002必须PASS后才能进入原材料开发 |
| Day 3 | `D3-T01` 六类Provider边界；`D3-T02` 三层路由与AuthorizedApi；`D3-T03` FreePublic；`D3-T04` Manual；`D3-T05` LocalImport/Synthetic隔离；`D3-T06` ADC12/AZ91D合规接入 | 四个来源意图×材料序列各有合法指定源、免费公开源或Manual中的一条non-synthetic路径；实际来源不可冒充 |
| Day 4 | `D4-T01` 全Provider标准化/校验；`D4-T02` 统一发布门禁；`D4-T03` 每日加工通用化；`D4-T04` 五级聚合；`D4-T05` 黄金复算与来源治理测试 | Day1-2最小链推广到全部Provider；手工/免费源不得绕门禁；H01/H02核心计算通过 |
| Day 5 | `D5-T01` 系统时间与文件轮转；`D5-T02` 跨卷/跨年读取；`D5-T03` 动态标的与依赖；`D5-T04` 历史回填；`D5-T05` 最小规则预警 | H05-H09后端链路可演示；前跳/回拨有证据；历史不删除；预警已持久化 |
| Day 6 | `D6-T01` 七个Agent工具；`D6-T02` EvidencePack；`D6-T03` LLMService/CloudLLMService；`D6-T04` 证据核验与结构化报告；`D6-T05` Java模板降级 | LLM不直接计算；回答展示真实来源；断网/云模型失败仍返回确定性报告 |
| Day 7 | `D7-T01` Vue基础；`D7-T02` 仪表盘；`D7-T03` 历史/质量/来源；`D7-T04` 手工录入与文件导入 | 核心Web流通过；手工提交先RECEIVED+PENDING；免费信源和Manual展示实际来源；不展示未校验或未发布数据 |
| Day 8 | `D8-T01` 动态配置页面；`D8-T02` 预警页面；`D8-T03` Agent工作台；`D8-T04` Web端H01-H09预验收；`D8-T05` 冻结功能 | Web P0功能冻结；三层路由和来源真实性有证据；剩余项均为可追踪缺陷 |
| Day 9 | `D9-T01` Electron外壳；`D9-T02` 内置JRE；`D9-T03` 动态端口与健康检查；`D9-T04` 子进程生命周期/单实例；`D9-T05` 便携目录和ZIP | 无外部Java/Node/数据库即可启动；退出无残留Java；data目录可检查 |
| Day 10 | `D10-T01` Windows干净机；`D10-T02` 系统时间和跨年；`D10-T03` 断网/LLM降级；`D10-T04` 动态标的与材料三层路线；`D10-T05` H01-H09证据、手册、报告、release-manifest | PBOC双币真实闭环PASS；四个来源意图×材料序列各有认可non-synthetic路线PASS；指定商业源自动能力不可用不再单独阻塞整个P0 |

每个任务的细化输入、文件、依赖、测试、DoD、回退和阻塞关系由 `docs/04-DEVELOPMENT-TASKS.md` 维护；任务状态只以 `docs/05-PROGRESS-LEDGER.md` 为准。二者不得改变本计划的来源优先级和范围。

## 16. P0、P1、P2 优先级

### P0：正式交付基线

H01-H09、PBOC EUR/CNY/USD/CNY真实闭环、六类Provider逻辑边界、原材料三层合法接入、来源真实性、文件全链路、BigDecimal、五级持久化、动态标的、历史回填、预警、七个Agent工具、CloudLLMService与模板降级、Vue3、Electron、内置JRE、Windows便携包、完整文档和证据。[A][F][B][C]

### P1：P0 通过后的增强

- Ollama/Qwen 连通性验证，但不得取代 P0 云端链路。[E]
- 诊断包、备份恢复演练和可审计数据导出。[E]
- 多来源对比、可配置通知及签名安装体验；不得混淆正式/演示来源。[E]
- 可选开发/运维容器；删除容器后P0 Windows便携包仍独立可用。[E]

### P2：长期演进

- 正式本地模型、vLLM。[E]
- RAG 领域知识库、引用检索。[E]
- 有评价集和合规语料后的 LoRA。[E]
- 预测、情景模拟与受控多Agent研究；只作决策辅助，不自动调价。[E]

P1/P2 只有在 P0 验收通过且有剩余资源时启动。

## 17. 外部待确认事项

| 编号 | 问题 | 推荐默认解释 | 不确认的风险 | 最晚确认节点 | 临时开发方案 | 影响正式验收 |
|---|---|---|---|---|---|---|
| EXT-01 | PBOC EUR/CNY、USD/CNY 的具体发布字段、单位、报价方向与业务日 | 已由 D1-T02 在 PBOC 官方公告 HTML 确认：按“1 外币对人民币 X 元”中间价建立可配置序列；详情见 docs/evidence/D1-T02/ | 当前 Windows 原生 TLS 路径仍可能阻断实际 Java 获取 | Day 1（字段已确认） | D1-T04 保存完整官方 raw 并验证 Java HTTPS 路径 | 是，字段已确认；PBOC 全链仍须通过 Day 2 硬门 |
| EXT-02 | 各实际来源的 ADC12/AZ91D 规格、地区、单位、含税口径和价格字段 | 按“实际来源×品种”分别建序列，不跨规格混算 | 同名价格不可比 | Day 3 | 使用完整元数据黄金样本；Manual保底 | 是，影响材料值口径 |
| EXT-03 | “每日加工均值”的业务定义 | 来源已发布单一官方日值时直接把该值作为当日合法样本；来源同日有多条同口径观测时才按sum/validCount，并保留计算版本 | 可能要求收盘价或加权均价 | Day 2 | 聚合策略可配置并固定黄金样例 | 是，影响H01/H02 |
| EXT-04 | SMM/Asian Metal 是否存在合法公开页面、接口或已授权自动路径 | 能合法自动则优先；否则免费公开信源→Manual | 只影响指定源自动采集能力 | Day 3 | 记录routeDecision与fallbackReason并执行三层降级 | 否，不阻塞整体P0；影响对应自动能力声明 |
| EXT-05 | 新增标的及初始化所需的历史回填范围 | 验收夹具默认至少连续13个自然月并跨年；真实来源回填起止日期仍配置化 | H08历史覆盖范围不明确 | Day 5 | Manual/LocalImport可补历史，任何回填仍经过发布门禁 | 是，影响H08范围，不阻塞通用链路 |
| EXT-06 | 节假日和未发布日处理 | 缺失不补0，记录覆盖率和过期状态 | expectedCount与均值口径争议 | Day 2 | 黄金日历固定、规则可配置 | 是，影响完整率 |
| EXT-07 | 预警阈值和严重度 | 使用演示阈值并明确标记非正式 | 无法声称业务预警阈值正式有效 | Day 5前 | 完成规则框架与演示规则 | 部分 |
| EXT-08 | 动态调价公式、成本权重、汇率换算 | P0只输出参考成本影响，不自动执行调价 | 成本建议缺少业务依据 | Day 5前 | 可配置演示成本篮子 | 是，影响正式建议 |
| EXT-09 | “跨卷”是否仅指多个轮转文件，或包含多个物理磁盘卷 | 默认指按月/年轮转文件 | 物理跨盘场景可能漏验 | Day 5前 | 完成跨文件/跨年；不自动跨物理盘 | 是，影响H06边界 |
| EXT-10 | 项目方认可的免费公开材料信源、许可条款、更新频率和字段映射 | 选择无需绕限制、可留原始证据且规格可比的来源 | 免费源不稳定或不可比 | Day 3 | 未确认时使用ManualDataProvider保底 | 否，不阻塞PBOC或整体P0 |
| EXT-11 | 手工录入是否要求操作人实名、复核人及附件证据 | P0至少记录operatorRef、实际来源、输入/更新时间和版本审计 | 审计责任深度不足 | Day 3 | 先实现可配置operatorRef和来源说明 | 否，不阻塞基础Manual链路 |

任何 EXT 项未确认都不得被写成“已解决”，但只按表中影响范围局部阻塞。EXT-04、EXT-10、EXT-11 不得阻塞 PBOC 或整个 P0；指定商业源不可合法自动获取时，必须执行项目方认可的免费公开信源或 Manual 降级。

## 18. 风险与应对

| 风险 | 等级 | 应对措施 |
|---|---|---|
| 指定商业网站无法合法自动采集 | 中高（局部） | 合法公开/授权自动→免费公开信源→Manual；不绕过访问控制，不阻塞整体P0 |
| 免费公开信源许可或字段变化 | 中高 | 记录条款、真实URL、raw和解析版本；Provider隔离与黄金回归 |
| 替代来源与ADC12/AZ91D规格不可比 | 高 | 按来源×规格建序列；单位/地区/含税口径校验；禁止跨规格混算 |
| 手工录入错误、漏录或来源不实 | 中高 | 必填实际来源/日期/单位；PENDING门禁、版本审计、可选复核 |
| 口径未确认导致计算返工 | 高 | Day 1 冻结黄金数据、BigDecimal 规则和聚合策略；保留计算版本 |
| 10 天范围过大 | 高 | P0 优先；Day 8 功能冻结；P1/P2 不抢占；每日退出门禁 |
| 文件中断或并发损坏 | 高 | 单写实例、原子替换、manifest、dirty marker、启动恢复和杀进程测试 |
| 系统时间修改影响调度 | 中高 | 业务日期路由、时间状态记录、前跳/回拨专项测试 |
| 云模型/网络不可用 | 中 | Java 模板降级；Agent 核心指标不依赖 LLM |
| Electron/JRE 打包延误 | 中高 | Day 2 验证 Java 运行形式；Day 9 专门打包；干净机验收 |
| PBOC及免费来源页面/API字段变化 | 高 | Provider隔离、schema/校验版本、raw保留、解析黄金回归和失败告警 |
| 免费/手工来源冒充SMM或演示数据被误当正式 | 高 | actualSourceName/providerType不可被展示层覆盖；全链路标签、报告引用和来源真实性验收 |

## 19. 最终交付物清单

- 完整工程源代码。
- Windows 便携桌面应用目录与 ZIP。
- 内置 JRE。
- Vue3 构建产物。
- 作为 Electron 内部组件的 Spring Boot JAR。
- 可直接检查的 JSON/CSV `data/` 目录。
- 示例导入模板。
- 黄金测试数据集及预期结果。
- 默认与示例配置文件。
- Windows 本地部署手册。
- 用户操作说明。
- 数据字典与 schema 版本说明。
- BigDecimal 与多级计算规则文档。
- 官方需求保存件。
- 项目方实施补充说明保存件。
- 需求追踪矩阵。
- 验收测试计划与验收测试报告。
- 项目架构说明。
- Agent 工具和 EvidencePack 说明。
- README。
- `release-manifest`，列出版本、文件、SHA-256、构建信息和数据模式。

JAR 可以存在于发布包内部，但不得作为面向最终用户的唯一交付物。[C]

## 20. 项目完成定义（Definition of Done）

只有同时满足以下条件，P0 才可标记完成：

1. H01-H09 每项均有需求映射、测试结果和可检查证据。
2. PBOC EUR/CNY、USD/CNY 真实自动获取至raw JSON、独立 Lifecycle timeline、PARSED/PENDING、VALIDATED、PUBLISHED+VERIFIED类、daily/aggregate CSV、重启历史读取闭环通过。
3. SMM/Asian Metal来源意图×ADC12/AZ91D四个P0原材料序列分别至少有“合法指定源自动、免费公开信源、Manual”中的一条non-synthetic全链路通过。
4. 未绕过登录、验证码、会员权限或反爬；实际来源在文件、面板、预警和Agent中一致，替代源未冒充SMM/Asian Metal。
5. Manual提交先写不可变raw并创建独立的RECEIVED+PENDING LifecycleRecord；完成PARSED、VALIDATED和PUBLISHED后，只有PUBLISHED+VERIFIED或PUBLISHED+VERIFIED_WITH_NOTICE可进入下游，修改保留全部审计版本。
6. 随机历史自然月的日/月/季/半年/年结果与独立黄金复算完全一致，无精度流失。
7. 所有高级聚合均已持久化，不是仅在查询时临时计算。
8. 任何非PUBLISHED记录，以及PUBLISHED+PENDING/REJECTED/CONFLICT非法组合，均无法进入每日加工、聚合、面板、预警和Agent。
9. Windows修改时间、时间回拨、跨月、跨年、重复文件、缺失文件和损坏文件测试均有明确结果。
10. 动态停用欧元、新增英镑、把两个来源意图下的AZ91D替换为MAT-REPL-01且保持ADC12不变的用例通过；旧历史文件保留。
11. 断网和云LLM失败时，Java模板报告仍能基于EvidencePack工作。
12. Windows干净机双击EXE可启动，退出无残留Java子进程；无需安装Java、Node、Maven、Docker或数据库。
13. 运行时进程和端口审计证明不存在任何隐藏数据库。
14. 便携目录中的data、配置、源码、文档、测试报告和release-manifest可直接检查。
15. Synthetic在页面、导出、预警和Agent报告中始终有明显标识，且未被用于正式来源验收。
16. 没有未关闭的P0阻断缺陷；指定商业源自动能力不可用但认可降级已通过时，不视为整体P0阻断。
17. README、部署、用户、数据、计算、Agent、追踪、验收、补充说明和决策文档完整且与发布版本一致。
18. `docs/05-PROGRESS-LEDGER.md`记录最近可运行版本、Git提交、验收状态、阻塞和下一任务。

## 21. 变更控制与执行起点

任何改变来源优先级、数据库禁令、数据链路、精度规则、正式/演示边界、Agent 权限或 Windows 交付形态的提议，都必须先在 `docs/06-DECISION-LOG.md` 新增或修订决策，写明依据、影响和回归测试，再修改任务或代码。

D1-T01 已完成；D1-T02 字段事实有效，本轮 Code Review 曾因逐币种连通性重放证据不完整而重开。实时任务状态与下一可领取任务只看`docs/05-PROGRESS-LEDGER.md`，并同步展示于`docs/04-DEVELOPMENT-TASKS.md`；本冻结总计划不再复制瞬时状态。第8.3至9节已经冻结 D1-T03 的唯一编码契约；无论D1-T02当前处于READY、IN_PROGRESS或REVIEW_PENDING，D1-T03都只能在D1-T02=`DONE`且Review通过后由技术负责人正式改为`READY`。只有 Day1 真实双币 raw 和 Day2 的 AT-SRC-002=`PASS` 才能通过 PBOC 验收或放行原材料 Provider 实现。

v1.4起，`docs/01`、`docs/02`、`docs/03`、`docs/06`及`docs/04`中的需求、契约、依赖、测试和DoD属于编码前冻结规范；`docs/04`的任务状态副本、`docs/05`进度台账和`docs/evidence`执行证据仍按执行协议更新。规范变更必须先新增正式决策和影响分析，再同步修改全部受影响基线，禁止Terra在编码时自行补设计。
