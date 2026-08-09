# SupplyMind AI 架构与项目决策日志

> 文档编号：SMA-DEC-001  
> 版本：v1.4  
> 状态：生效  
> 基线日期：2026-08-08

## 1. 使用规则

本日志记录会影响项目范围、架构、数据可信度、验收或交付的冻结决策。后续窗口不得仅凭聊天内容推翻这些决策。

来源标签：

- **[A] 官方需求**
- **[F] 项目方实施补充说明**：独立保存在 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`，对数据获取方式和实施优先级形成正式补充。
- **[B] 官方验收**
- **[C] 架构冻结**
- **[D] 外部待确认**
- **[E] P1/P2 增强**

若需修改一条冻结决策，必须先记录变更提案、依据、影响分析、数据迁移或兼容方案、回归测试范围和批准人，再更新总计划、需求追踪、任务、测试和代码。官方要求或验收变更必须有书面依据。

## 2. 生效决策

| 决策编号 | 决策内容 | 决策原因 | 影响范围 | 可否修改 | 修改条件 |
|---|---|---|---|---|---|
| DEC-001 [C] | 信息优先级固定为：官方原始需求书及项目方正式补充 > 官方验收 > 架构冻结 > 总计划 > 开发任务 > 代码；数据获取方式重叠时以正式补充解释原需求。 | 防止实现便利、聊天压缩或旧规划反向修改正式要求，同时保留原需求正文。 | 全项目需求、设计、开发、测试和验收。 | P0 不可随意修改。 | 仅在官方书面变更或发现来源记录错误时修改，并同步所有基线文档。 |
| DEC-002 [C] | 后端采用 Java 17。 | 冻结统一编译、运行和内置 JRE 基线，减少 10 天内环境差异。 | 构建、依赖、测试、Electron 打包、部署。 | P0 冻结。 | 只有目标 Windows 环境不兼容，且完成全部依赖和打包回归后可变更。 |
| DEC-003 [C] | 使用单个 Spring Boot 工程的模块化单体，按业务包划分，不拆微服务。 | 10 天内需要完整闭环；文件单写架构也不适合多服务并发写。 | 工程结构、部署、进程、模块依赖。 | P0 冻结。 | P0 验收后出现明确独立扩缩容需求，并完成存储架构升级评审时才可讨论。 |
| DEC-004 [B][C] | P0不使用任何数据库栈、驱动、服务、进程或业务数据库文件，包括MySQL、Redis、SQLite、H2、JPA、JDBC、R2DBC和MyBatis。 | H04 要求无隐藏数据库运行进程；零数据库也降低最终用户安装门槛。 | 持久化、查询、依赖、部署、验收进程检查。 | P0 不可修改。 | 只有官方验收书面取消 H04，且重新评审数据迁移与桌面交付后才可变更。 |
| DEC-005 [C] | JSON/CSV是唯一业务数据持久化方式；config/raw/lifecycle/quarantine/warning/report/runtime/manifest使用JSON，processed daily及四级aggregate使用CSV。物理目录唯一冻结为`data/{config/{monitor-series.json,history/<configVersion>.json},raw,staging,quarantine,processed/daily,processed/aggregate/<itemId>/{month,quarter,halfyear,year},warning,report,runtime/{jobs/active,jobs/history,dirty,conflicts/raw}}`；monitor-series是唯一活动配置，history只是不可变已生效快照；根`runtime`仅JRE，根`logs`仅日志。`normalized`、`published`不能成为物理目录；`runtime/conflicts/raw`只存raw异hash诊断证据，不是第二正常数据链。 | 满足H03直接可检查性，并与H04一致，避免格式、目录和活动配置分叉。 | 已冻结data目录、格式、根runtime/logs、文件路由与验收证据。 | P0冻结。 | 官方允许其他格式且仍满足可检查、无数据库、跨卷和恢复要求时可扩展；不可静默替换现有格式或新增竞争目录/活动配置。 |
| DEC-006 [C] | 不使用 MyBatis。 | 项目无数据库，ORM/Mapper 层没有业务价值。 | 后端依赖、数据访问模块和简历表述。 | P0 冻结。 | 只有 DEC-004 被正式修改后重新评审。 |
| DEC-007 [B][C] | raw/lifecycle、daily CSV、月/季/半年/年aggregate CSV和warning JSON均必须持久化；高级聚合不能只在查询时临时计算。 | 官方要求多级均值文件留存，H01/H03需要可直接检查和独立复算。 | 文件schema、聚合任务、查询、验收证据。 | P0不可修改。 | 只有官方书面明确取消高级聚合文件验收后才可修改。 |
| DEC-008 [B][C] | 所有价格、金额、汇率、均值、变化率和成本只可由`new BigDecimal(String)`进入计算；精确输出使用`toPlainString()`字符串，不得`stripTrailingZeros()`，不得使用科学计数法或让float/double进入业务真值链。 | H02要求全链路无精度流失。 | Provider、JSON/CSV、加工、聚合、预警、Agent工具、前端接口。 | P0不可修改。 | 展示层可生成仅供绘图的数值副本，但不得回写或参与计算。 |
| DEC-009 [C] | 原始词法精度原样保存；每个标的配置calculationVersion、非负calculationScale、非负displayScale、Java RoundingMode字符串和calendarVersion。sum精确不舍入，avg仅按calculationScale/roundingMode除法，displayScale只用于API/UI固定展示且不得回写或参与聚合。 | 避免级联聚合误差，并让H02可逐行复算。 | 配置schema、CSV、计算库、数据字典和测试。 | P0冻结。 | 业务方书面调整时必须新增计算/日历版本、configVersion并回归全部受影响聚合，不得改写历史版本含义。 |
| DEC-010 [A][C] | 数据链路固定为：获取→不可变RawReceipt→独立Lifecycle timeline候选标准化→校验→发布→每日加工→五级持久化→预警→面板/查询/Agent。raw不保存`ProcessingStage`/`ValidationStatus`；二者只存在于Lifecycle版本快照。 | 官方明确要求校验和文件留存；固定门禁并消除可变状态污染raw。 | 全部后端模块、任务状态、测试和证据。 | P0不可修改。 | 只允许在不绕过校验、不减少持久化结果且不合并双状态字段的前提下增加内部步骤。 |
| DEC-011 [C] | **状态集合与扩展条款已由DEC-042取代；仅发布门禁继续有效。** 只有`ProcessingStage=PUBLISHED`且`ValidationStatus∈{VERIFIED, VERIFIED_WITH_NOTICE}`可进入下游。 | DEC-042已把两个enum和合法组合冻结为精确集合，不能继续使用“至少包含/可增加状态”的旧口径。 | 校验、隔离、加工、聚合、预警、面板、Agent。 | 按DEC-042执行。 | 任何状态扩展必须先形成新决策、schema版本和全量门禁迁移，不得依据本历史条目直接增加。 |
| DEC-012 [C] | 校验失败的新值不得覆盖已有合法历史值；面板可显示上一合法值，但必须显示业务日期与过期状态。 | 保持业务连续性，同时避免以旧值冒充最新值。 | 文件 upsert、查询、面板、数据质量和预警。 | P0 冻结。 | 仅业务方书面要求不同的失败处理策略，且不违反官方“未经校验不展示”时可调整。 |
| DEC-013 [C] | **历史决策，已由DEC-036取代。** 原计划支持OfficialApi、AuthorizedFile、LocalImport、SyntheticDemo四类入口。 | 保留变更审计；项目方补充现要求六类逻辑来源。 | Provider接口、配置、页面模式、验收。 | 不再作为当前实现依据。 | 当前方案见DEC-036；不得恢复四类限制。 |
| DEC-014 [C] | **部分被DEC-037取代。** “正式模式仅接受官方API或授权文件”失效；“Synthetic只能演示且必须显著标识”继续有效。 | 项目方已正式认可FreePublic与可追溯Manual作为材料降级路径。 | 数据记录、页面、预警、Agent报告、验收结论。 | 按DEC-037执行。 | Synthetic永远不能直接升级为真实来源。 |
| DEC-015 [F][C] | 禁止未授权爬虫绕过登录、验证码、会员限制、访问控制或反爬机制。 | 项目方实施补充明确强化该边界，避免合规、稳定性和知识产权风险。 | SMM/Asian Metal接入、所有Web Provider实现和项目声明。 | 不可修改。 | 只能使用合法公开访问、合法授权接口、免费公开信源或Manual降级。 |
| DEC-016 [C] | daily按`processed/daily/<itemId>/YYYY-MM.csv`自然月分卷；aggregate按`processed/aggregate/<itemId>/<grain>/YYYY.csv`分区；查询按日期枚举分卷并校验、拼接、去重、排序。 | 直接满足文件轮转、跨文件和跨年度验收。 | 文件布局、历史查询、H05/H06测试。 | P0冻结。 | 变更必须同步C29、AT-FILE-001和迁移策略。 |
| DEC-017 [B][C] | raw的YYYY/MM及异常证据quarantine的YYYY-MM按`receivedAt`在Asia/Shanghai的年月路由；processed daily/aggregate只按已验证`businessDate`路由。持久化系统时间状态并检测前跳、回拨和睡眠恢复。 | raw必须先保存，来源业务日期可能缺失/非法；processed则必须保持业务周期准确。 | ingestion、调度、轮转、质量校验、Windows测试。 | P0冻结。 | 只能更换检测实现，不得让未验证业务日期控制processed路径。 |
| DEC-018 [C] | **原子提交细节由DEC-046补充并以其为准。** raw使用同目录临时写、force/校验后不覆盖提交；可变文件使用备份后原子替换；使用单写锁、manifest和dirty marker恢复。 | 无数据库事务时必须避免半文件、raw覆盖、重复记录和中断不一致。 | storage、ingestion、aggregation、启动恢复。 | P0冻结。 | 具体data+manifest顺序、ATOMIC_MOVE门禁、冲突证据与恢复规则见DEC-046和总计划8.5。 |
| DEC-019 [C] | 动态停用使用 enabled=false；替换标的创建新 itemId；历史数据不得删除或改名冒充新标的。 | 满足 H07-H09，并保持历史语义和审计链。 | 配置、调度、回填、面板、规则和文件路径。 | P0 不可修改。 | 只有正式的数据保留政策和迁移方案获批后可增加受控归档，不能破坏验收历史。 |
| DEC-020 [C] | 采用 Vue3 构建仪表盘、历史趋势、质量、导入、动态配置、预警和 Agent 工作台。 | 满足动态面板需求，并与 Electron Web 技术栈统一。 | 前端工程、API 和桌面封装。 | P0 冻结。 | 只有严重兼容性问题且替代方案完成全部页面回归时可调整。 |
| DEC-021 [C] | Windows 桌面端采用 Electron，不使用 JavaFX。 | Electron 可复用 Vue3、管理 Java 子进程并提供用户双击启动体验。 | 桌面进程、打包、端口、日志和发布物。 | P0 冻结。 | 只有 Electron 无法满足目标 Windows 环境，且新方案仍满足内置运行时、Vue 页面与便携交付后可变更。 |
| DEC-022 [B][C] | 最终交付是便携 Windows 应用目录和 ZIP；JAR 仅作为 Electron 内部组件，不能是唯一用户交付物。 | 官方要求 Windows 桌面端；冻结目标要求用户无需开发环境即可启动。 | 发布流程、README、部署手册、H03、干净机测试。 | P0 不可修改。 | 只有官方书面接受其他最终交付形态时可修改。 |
| DEC-023 [C] | 发布包内置 JRE；用户不需要安装 Java、Node.js、Maven、Docker 或数据库。 | 提高可移植性并满足 Windows 干净机验收。 | 包体、许可证清单、Electron 启动和部署文档。 | P0 冻结。 | 只有目标环境统一预装兼容运行时且验收方书面同意时可改变。 |
| DEC-024 [B][C] | 唯一配置项`supplymind.data-root`解析为规范化绝对路径；测试显式注入临时目录，最终Electron显式传入EXE同级data，本地开发才可默认`${user.dir}/data`。不可写时fail-fast，不静默回退到userData/隐藏目录/第二data。 | H03要求检查程序目录数据文件并保证测试隔离。 | Electron数据路径、storage、备份、部署、验收。 | 受控可修改。 | 安装形态变化必须仍提供唯一显式dataRoot并同步迁移/验收。 |
| DEC-025 [C] | 最终用户运行不依赖 Docker；不创建 Dockerfile/docker-compose 作为最终部署方案。 | Docker 会违反便携桌面和干净机零前置目标。 | 发布、文档、测试和用户支持。 | P0 不可修改。 | P0 后可增加仅供开发/CI 的容器方案，但不得写入最终用户前置条件。 |
| DEC-026 [C] | 模型调用通过项目自有 LLMService 解耦；业务层不暴露某家供应商 SDK 类型。 | 支持未来云模型切换和本地模型接入，避免 Agent 业务逻辑绑定厂商。 | llm、agent、配置、测试。 | P0 冻结。 | 可扩展接口能力，但必须保留现有业务请求/响应兼容或提供迁移版本。 |
| DEC-027 [C] | P0 实现 CloudLLMService，并保留 LocalLLMService 接口；Ollama/Qwen 连通性为 P1，正式本地模型为 P2。 | 10 天内优先验证 Agent 业务链路，避免 GPU 和模型部署阻塞 P0。 | AI 范围、资源、验收和路线图。 | P0 冻结，P1/P2 可扩展。 | P0 完成且有独立时间、硬件和评价集后才进入本地模型工作。 |
| DEC-028 [C] | Agent 必须调用 Java 受控工具；均值、成本、风险等级、数据有效性均由 Java 确定性计算。 | LLM 数值和规则判断不可作为可审计业务真相。 | Agent 编排、工具、安全、测试、简历亮点。 | P0 不可修改。 | 可增加工具，不得把确定性计算权交给 LLM。 |
| DEC-029 [C] | P0 Agent 工具至少包括 series.resolve、history.query、period.metrics、quality.inspect、cost.impact、warning.explain、provenance.trace。 | 覆盖标的解析、历史、指标、质量、成本、预警和血缘的完整证据链。 | Agent、API、EvidencePack、验收。 | 可增加，不可减少。 | 减少工具必须证明等价能力仍存在，并更新验收、报告 schema 和回归测试。 |
| DEC-030 [C] | LLM 只接收 Java 生成的 EvidencePack；输出后由后端核验证据引用并保存结构化报告。 | 防止模型读取未验证文件、编造来源或产生无证据结论。 | Prompt、工具调用、报告、审计。 | P0 冻结。 | 只可增强 EvidencePack，不得移除后端证据核验。 |
| DEC-031 [C] | 云模型失败或断网时使用同一 EvidencePack 生成 Java 模板报告。 | Agent 的确定性业务价值不能依赖外部云服务在线。 | LLM 异常处理、UI、断网验收。 | P0 不可修改。 | 可替换模板实现，但降级能力和证据必须保留。 |
| DEC-032 [E][C] | P0 不做 RAG、LoRA、vLLM 或正式本地模型。 | 这些工作需要额外数据、评价集、硬件和时间，不能抢占 H01-H09。 | 10 天范围、依赖、打包。 | P0 不可修改；P2 可启动。 | P0 验收通过，有明确数据、评价指标、硬件和独立计划后启动。 |
| DEC-033 [C] | Day 8 后冻结新增业务功能，只允许修复 P0 验收缺陷、补充证据和交付。 | 为 Electron 打包、干净机测试和 H01-H09 留出确定时间。 | 进度、变更管理和优先级。 | P0 冻结。 | 只有阻断 H01-H09 的缺失能力可按 P0 缺陷处理；普通增强不得例外。 |
| DEC-034 [F][C] | 项目方实施补充说明是原需求“数据获取方式”和实施优先级的正式解释，独立保存且不混入原需求正文。 | 保持官方文本可追溯，同时让新说明覆盖旧规划冲突。 | 需求优先级、追踪、任务、验收、风险。 | P0不可修改。 | 仅由项目方后续书面说明取代。 |
| DEC-035 [F][C] | Day1至Day2第一目标是完成PBOC EUR/CNY、USD/CNY真实获取、raw/lifecycle JSON、标准化、校验、`PUBLISHED+VERIFIED类`、daily/aggregate CSV和历史读取闭环。 | 项目方明确要求优先完成汇率爬取和存取。 | 10天排序、退出门禁、验收、资源分配。 | P0不可修改。 | 仅AT-SRC-002完整通过才放行材料Provider；D1-T02调查或失败证据不构成通过。 |
| DEC-036 [F][C] | Provider架构至少区分OfficialWeb、AuthorizedApi、FreePublic、Manual、LocalImport、SyntheticDemo六类逻辑来源；实现类可复用，来源身份不可合并。 | 同时支持合法自动、免费公开、手工和演示路径。 | Provider、schema、配置、UI、验收。 | P0冻结。 | 可增加合法类型，不得删除来源元数据或六类逻辑能力；取代DEC-013。 |
| DEC-037 [F][C] | 材料接入按“合法指定源自动→同类免费公开信源→Manual”受控降级；SMM/Asian Metal无授权只影响指定源自动能力，不阻塞整个P0。 | 项目方已正式认可两级降级实现。 | 风险、P0判定、任务、验收、来源路由。 | P0不可修改。 | 每次降级必须记录routeDecision、fallbackReason和实际来源；部分取代DEC-014。 |
| DEC-038 [F][C] | 免费公开信源必须保存并展示真实网站名称、URL/引用和获取方式，不得冒充SMM、Asian Metal或其他指定商业信源。 | 保证数据血缘、合规与Agent证据可信。 | raw、daily、API、UI、预警、EvidencePack、报告。 | P0不可修改。 | 来源修订必须保留版本审计并重跑来源真实性测试。 |
| DEC-039 [F][C] | Manual输入先写不可变RawReceipt，再建立独立`RECEIVED+PENDING` LifecycleRecord；raw记录实际来源、标的、业务日期原文、输入时间、单位、输入方式和不可变updatedAt，Lifecycle记录校验状态与版本。必须经过PARSED→VALIDATED，且仅PUBLISHED+VERIFIED类进入下游。 | 手工降级不能绕过数据治理且可变状态不能污染raw。 | ManualDataProvider、validation、storage、UI、聚合、Agent。 | P0不可修改。 | 可增加复核或附件，不得允许手工数据直达面板。 |
| DEC-040 [D][C] | D1-T02已确认PBOC双币官方公开HTML字段事实：从公告列表读取真实详情链接，解析“1美元对人民币X元”“1欧元对人民币X元”；业务日取标题/正文/落款一致日期，发布时间取“文章来源”。本轮Code Review曾因逐币种连接重放元数据不完整而重开任务；TraceabilityStatus为EXTERNAL_CONFIRMED，实时TaskExecutionStatus唯一见docs/05。 | 保留已核验字段事实，同时不把调查状态、瞬时任务状态或失败证据误判为DoD/验收。 | PBOC Provider、Series配置、D1-T02、D1-T04/D2验收。 | P0受控可修改。 | 可复现重放证据须经Review后任务方可DONE；只有AT-SRC-002真实全链PASS才通过Day1/2门禁。 |
| DEC-041 [C] | `TaskExecutionStatus`、`AcceptanceStatus`、`TraceabilityStatus`为独立命名空间；`EXTERNAL_CONFIRMED`只属于TraceabilityStatus，不能写成任务DONE、数据VERIFIED或AT PASS。任务产物提交后先进入TaskExecutionStatus=`REVIEW_PENDING`，只有技术负责人Review通过才为DONE。 | 防止调查、实现、Review和验收状态混写。 | 任务台账、验收计划、追踪矩阵、进度账本与发布结论。 | P0冻结。 | 新状态必须明确命名空间并通过跨文档审计。 |
| DEC-042 [C] | 生命周期enum及九种合法组合精确冻结为总计划8.4.3所列集合。唯一初态为RECEIVED+PENDING，唯一迁移边为RECEIVED→PARSED或RECEIVED+REJECTED、PARSED→四种VALIDATED、两种VERIFIED类VALIDATED各自→同状态PUBLISHED；禁止跳级/回退/自迁移。CandidateV1从PARSED起必填且同run不可变，版本指针和各状态条件必填矩阵均以8.4.3为准。 | 使各阶段可审计并防止覆盖历史、丢失标准化候选或绕过门禁。 | LifecycleRecord、存储、校验、加工、聚合、API、UI、Agent和验收。 | P0冻结。 | raw不可变；timeline不得删除旧版本；迁移或字段规则变化必须升级schema并迁移。 |
| DEC-043 [C] | 物理数据目录只采用DEC-005冻结树，aggregate路径必须为`data/processed/aggregate/<itemId>/{month,quarter,halfyear,year}`，不是缺少itemId层的简写，也不是grain-first。`normalized`和`published`仅为逻辑术语；根runtime为JRE，data/runtime为业务运行状态。 | 消除跨文档目录竞争和PathResolver分叉。 | 文件存储、查询、Windows发布包、测试样本与验收证据。 | P0冻结。 | 变更必须同步schema、迁移策略、AT-FILE-001和全部基线文档。 |
| DEC-044 [C] | 一次外部响应使用一个`acquisitionId`；每个item使用独立`runId`、RawReceipt、rawRef和Lifecycle timeline。raw固定为`raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json`。多item共享响应时，各RawReceipt保存相同完整payload bytes/hash，并分别保存item级rawValue/matchAnchor。 | PBOC同一页面含双币，而raw路径与staging均按item/run组织；显式基数可避免文件竞争。 | ingestion、raw schema、PBOC Provider、幂等、测试。 | P0冻结。 | 只有提供等价无竞争、可逐item验收且不破坏现有路径的迁移方案时可改。 |
| DEC-045 [C] | RawReceiptV1、LifecycleTimelineV1/CandidateV1、QuarantineProjectionV1、活动monitor-series及不可变history、manifest、daily/aggregate固定codec与CALCULATION-RULES，以总计划8.4至9节为唯一schema/计算契约。D1-T03必须同时交付单一schema数据字典、追加式计算规则字典与合法/非法黄金文件；raw不含可变生命周期字段，quarantine只投影三个失败终态。 | 消除自然语言字段、候选/配置历史丢失和codec字段自创造的实现分叉。 | schema、codec、storage、validation、calculation、AT-FILE-000。 | P0冻结。 | schema变更必须升版本并提供迁移、黄金文件和回归测试。 |
| DEC-046 [C] | 唯一dataRoot、receivedAt/businessDate路由、data先于相邻manifest提交、固定tmp/bak/DirtyMarkerV1单调markerRevision+targets[]事务状态机、marker canonical/tmp/bak候选组专用自恢复、fileSha256/payloadSha256分离、ATOMIC_MOVE启动门禁、raw/history不覆盖、异hash完整incoming冲突证据及启动恢复，以总计划8.3至9节和AT-FILE-000为唯一实现契约。配置变更在同一CONFIG_ACTIVATION事务中先提交history数据/manifest，再提交活动配置数据/manifest。marker歧义必须fail closed，manifest不递归生成manifest，禁止静默非原子降级。 | 让D1-T03无需猜测两文件/多目标提交、marker自身替换崩溃窗口、哈希冲突和Windows文件系统行为。 | D1-T03、storage、config、desktop、恢复、H02-H04。 | P0冻结。 | 替代方案必须不降低可检查性/可靠性并通过AT-FILE-000与受影响全量回归。 |
| DEC-047 [C] | monitor-series顶层configVersion/mode与item级来源意图、Provider配对、routeDecision/fallback、动态替换、外部代码/解析键、币种/单位/精度字段，及两个PBOC正式itemId、baseCurrency/currency（语义等于quoteCurrency）/unit映射以总计划8.4.5为准；运行JSON不得新增quoteCurrency字段。RawReceipt保存任务启动时configVersion。daily必须用完整inputRefs；aggregate按item+来源身份+周期+币种+单位分组，保留完整daily inputRefs并按冻结算法产生sourceFingerprint；配置历史和计算追溯由DEC-048补充。 | 动态配置、同日多观测和来源降级不能依赖displayName猜测，也不能混合血缘。 | D1-T03、D1-T04、D2/D4计算、D3路由、D5动态配置、查询、UI、验收。 | P0冻结。 | 修改须升级config/CSV schema与计算版本并提供历史迁移和黄金回归。 |
| DEC-048 [C] | 每个已生效configVersion必须有与激活时monitor-series逐字节相同、CREATE_NEW且不可覆盖/删除的`data/config/history/<configVersion>.json`及manifest；RawReceipt.configVersion必须可解析。daily/aggregate固定表头逐行保存validationStatus、validationVersion、configVersions、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion；不同校验结论/版本或计算上下文分行，aggregate inputRefs必须唯一定位daily行，qualityStatus严格由complete派生。sum精确，avg按calculationScale舍入，高级聚合直接从daily avg重算，displayScale只在API/UI边界使用。 | 让历史路由、来源、校验、精度、日历和均值可复算，避免配置覆盖后无法解释旧raw/CSV或D2/D4返工。 | D1-T03、D2/D4计算、D3路由、D5配置、查询、UI、AT-PREC/FILE。 | P0冻结。 | 任何字段或算法变化必须升级schema/规则/config版本，保留旧快照并执行迁移与全量黄金回归；EXT-03/EXT-06仍须正式关闭或书面接受默认。 |
| DEC-049 [C] | v1.4编码前基线冻结：docs/01、02、03、06及docs/04中的需求、契约、依赖、测试和DoD不得由开发窗口自行修改；docs/04任务状态副本、docs/05实时台账和docs/evidence执行证据可按协议更新。规范变更必须先新增决策与影响分析，再同步所有受影响基线。 | 让Terra可从唯一文档实现到项目结束，同时避免瞬时状态使冻结规范反复变化。 | 全部后续开发、Review、验收和跨窗口交接。 | P0不可随意修改。 | 仅官方/项目方书面变更、已证实基线缺陷或外部项正式决议可触发，并须记录批准、迁移和回归范围。 |
| DEC-050 [C] | PBOC汇率基础校验v1正式冻结（技术负责人正式批准，APPROVED）：`validationVersion=pboc-basic-validation-v1`；`staleThresholdDays=30`个自然日，businessDate距校验日期（validation date，Asia/Shanghai自然日）超过30日判定为陈旧（STALE_BUSINESS_DATE），等于30日仍视为有效；合法数值区间为`(0,100]`，下界开放、上界闭合，`0`与负值不允许（OUT_OF_RANGE），`100`允许。该规则当前仅适用于USD/CNY、EUR/CNY的D2-T01基础校验。历史已生成validation结果必须继续由其持久化的validationVersion保持可追溯，未来规则变化不得静默改写旧结果。不得把该规则扩展为材料价格规则、所有未来Provider通用规则、warning阈值或其他业务口径；扩展须新决策+新validationVersion。 | D2-T01基础校验需要正式、确定、可追溯的时效与数值边界；Review Finding 4（CHANGE_REQUEST_REQUIRED）后由技术负责人正式裁决，取代此前“版本化实现默认/待确认”措辞。 | D2-T01校验、LifecycleSnapshot.validationVersion语义、D2-T01证据与测试。 | P0受控可修改。 | 业务方书面调整或正式关闭EXT-03/EXT-05/EXT-06时，以新validationVersion与决策记录发布，不得改写已持久化的旧validationVersion结果。 |
| DEC-051 [C] | PBOC业务读模型`PublishedRecord.stale`正式冻结为查询时派生字段（技术负责人正式裁决，REPLACED旧语义）：以Asia/Shanghai自然日计算businessDate与referenceDate的日期差，超过30个自然日（日期差>30）为`stale=true`，等于或少于30日（日期差<=30）为`stale=false`；当天、差1天、差29天、差30天均非stale，差31天起stale。复用DEC-050的30个自然日阈值与边界，仅比较基准不同：D2-T01 validation以validationDate为基准、D2-T02 query以referenceDate（查询参考日）为基准。该规则不改变已持久化validation结果、不改变Lifecycle状态、不改变发布资格，不重写timeline。明确禁止把`businessDate < referenceDate`直接等同stale。 | D2-T02业务读模型需要与DEC-050同阈值的确定性stale派生语义，避免“非当日即过期”的未授权口径；正式Review判定旧事实比较语义未获授权并REPLACED。 | D2-T02业务读模型、PublishedRecord、D2-T02证据与测试。 | P0受控可修改。 | 业务方书面调整或正式关闭EXT-06时，以新决策记录发布；不得与DEC-050形成同名不同义。 |
| DEC-052 [C] | D2-T03 daily.updatedAt确定性语义正式冻结（技术负责人正式裁决）：daily行的`updatedAt`表示该行全部有效PUBLISHED输入中**最新的正式发布时间**，即`max(valid PUBLISHED input publishedAt)`；比较按实际时间点Instant进行（不是LocalDateTime文本或offset字符串字典序）；输出统一转换为Asia/Shanghai的ISO-8601 offset datetime；不表示本次processing执行时间。同一冻结daily group独立计算本组max；VERIFIED与VERIFIED_WITH_NOTICE按现有冻结分组键保持分行；输入顺序不影响updatedAt。完全相同的逻辑输入集合在不同processing执行时间重算必须产生逐字节一致的daily CSV与fileSha256；updatedAt仅在有效输入集合导致max(publishedAt)变化时变化。缺少合法publishedAt（找不到PUBLISHED snapshot、快照缺失publishedAt、publishedAt无法解析、inputRef与lifecycle无法对应）必须fail-closed，不得回退当前Clock/businessDate/validatedAt/文件mtime或生成默认时间。 | D2-T03重算确定性要求（总计划8.4.5"相同逻辑输入必须逐字节相同CSV与fileSha256"、AT-AGG-001"文件重算结果与首次计算完全一致"）要求daily.updatedAt不得取processing执行Clock；参照总计划8.4.3 quarantine先例（派生记录时间字段取业务输入时间，不取重放时Clock），由技术负责人正式裁决来源为输入publishedAt最大值。 | D2-T03每日加工、DailyRecordV1.updatedAt语义、daily CSV/manifest、D2-T03证据与测试。 | P0受控可修改。 | 业务方书面调整时以新决策记录与规则版本发布，不得改写已持久化daily的语义；不得借此修改EXT-03/EXT-06、daily算术平均、missing、aggregate、warning或未来任务。 |
| DEC-053 [C] | EXT-03（每日均值业务定义）正式接受版本化默认：`calculationVersion=arithmetic-mean-v1`，适用于D2-T03 PBOC每日加工。正式规则：同一daily group内全部合法PUBLISHED输入的`sum`使用BigDecimal完整精度求和（不舍入）；`validCount`只统计合法正式输入；`avg=sum.divide(validCount, calculationScale, roundingMode)`，只在最终除法执行舍入；`displayScale`仅用于展示，不得回写正式业务计算值；missing不进入validCount、不进入sum、不得补0。历史结果不得改写。未来若改为收盘价、加权均价或其他daily计算规则，必须新增calculationVersion及相应configVersion，不得静默修改arithmetic-mean-v1或改写已有历史语义。 | EXT-03经技术负责人正式裁决接受现有版本化实现为当前可上线、可验收口径；D2-T03 EXT Gate据此满足。 | D2-T03每日加工、CALCULATION-RULES、configVersion演进、D2-T03证据与验收。 | P0受控可修改。 | 业务方书面调整daily计算口径时，以新calculationVersion+configVersion与新决策记录发布，保留历史结果及其版本标识。 |
| DEC-054 [C] | EXT-06（节假日/未发布日）正式接受版本化默认：`calendarVersion=weekday-asia-shanghai-v1`，适用于D2-T03 PBOC每日加工。正式规则：Asia/Shanghai周一至周五视为当前版本的预期业务日期；daily row的`expectedCount=1`、`missingCount=max(expectedCount-validCount,0)`、`complete=validCount>=expectedCount`；无合法输入不得生成value=0的daily row；缺失不得补0；空月不得仅因预期日期存在而强制生成虚构数据。能力边界：本版本仅为当前可上线、可验收的expected-count与completeness版本化规则，不代表完整中国法定节假日日历、完整调休规则、完整停报日规则或完整特殊交易日日历。未来提升日历精度必须新增calendarVersion及相应configVersion，不得静默修改weekday-asia-shanghai-v1；历史正式结果必须保留其实际使用的calendarVersion。 | EXT-06经技术负责人正式裁决接受现有版本化实现为当前可上线、可验收口径；D2-T03 EXT Gate据此满足；明确能力边界防止误认为完整节假日日历。 | D2-T03每日加工、CALCULATION-RULES、configVersion演进、完整率与D2-T03证据。 | P0受控可修改。 | 业务方书面要求更精确节假日/调休/停报日历或以新决策记录发布新calendarVersion+configVersion时变更；历史结果不得改写。 |

## 3. 待外部确认但尚未形成决策的事项

以下内容不得通过本日志假设为已确认：

1. PBOC 欧元/人民币、美元/人民币的字段、单位和中间价口径已由 D1-T02 契约记录确认；仍待 D1-T04 在 Java/目标网络对真实响应、raw 落盘和重复采集结果复核。[D]
2. 各实际来源的ADC12、AZ91D规格、地区、单位、含税口径和价格字段。[D]
3. “每日加工均值”的正式业务定义。[D]
4. SMM、Asian Metal是否存在合法公开页面、接口或已授权自动路径；该项只影响指定源自动能力。[D]
5. 历史回填范围。[D]
6. 节假日与未发布日处理口径。[D]
7. 预警阈值和严重度。[D]
8. 动态调价公式、成本权重和换算规则。[D]
9. “跨卷”是否仅指多个轮转文件，或包含多个 Windows 物理卷。[D]
10. 项目方认可的免费公开材料信源、许可条款、更新频率和字段映射。[D]
11. 手工录入是否要求实名操作人、复核人及附件证据。[D]

这些事项的推荐默认解释、风险、最晚确认节点和临时开发方案记录在项目总计划中。“允许FreePublic/Manual降级”已由[F]确认，不再属于待确认；EXT-04、EXT-10、EXT-11只局部影响来源能力或审计深度，不阻塞PBOC和整个P0。确认后应新增决策记录，不得直接覆盖本节文字而不留痕。

## 4. 决策变更记录模板

新增或修订决策时使用以下字段：

- 决策编号：`DEC-XXX`
- 来源：A/F/B/C/D/E
- 决策内容：
- 决策原因：
- 影响范围：
- 可否修改：
- 修改条件：
- 替代方案：
- 回归测试：
- 批准人及日期：
- 被取代决策：
