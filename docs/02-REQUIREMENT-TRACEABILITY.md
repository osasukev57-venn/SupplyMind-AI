# SupplyMind AI 需求追踪矩阵

> 版本：v1.4  
> 基线日期：2026-08-08  
> 已纳入：项目方实施补充说明 SMA-REQ-SUP-001

## 1. 文档目的与使用规则

本文件把官方需求、项目方实施补充说明、官方验收要求、项目冻结架构、外部待确认事项和后续增强项映射到开发任务、验收方法及证据。后续窗口不得依靠聊天记录补全需求，必须以本文件、`00-OFFICIAL-REQUIREMENTS.md` 和 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md` 为基线。

需求来源类别固定为：

- A：官方原始需求。
- F：项目方实施补充说明；仅在数据获取方式与实施优先级范围内正式补充A。
- B：官方验收要求。
- C：当前项目已经冻结的架构决策。
- D：外部待确认项。
- E：P1/P2 增强功能。

本矩阵使用独立的 `TraceabilityStatus`，不得与业务数据的 `ValidationStatus` 或任务的 `TaskExecutionStatus` 混用：

- BASELINED：已进入需求基线，但尚未实现或验收。
- FROZEN：架构已经冻结，但尚未实现或验证。
- OPEN_EXTERNAL：等待甲方、数据供应方或验收方确认。
- OPEN_EXTERNAL_NON_BLOCKING：外部事项只影响局部能力或审计深度，不阻塞PBOC与整个P0。
- EXTERNAL_CONFIRMED：外部事实口径已有可核验证据确认，但实现、目标运行环境或正式 AT 仍未通过。
- DEFERRED_P1 / DEFERRED_P2：不进入 P0，不能阻塞 P0。
- IMPLEMENTED：已经实现，但尚无完整验收证据。
- ACCEPTANCE_PASSED：已有可复核的正式验收证据并通过验收；不是数据 `ValidationStatus=VERIFIED`。
- BLOCKED：存在明确阻塞并已登记。

只有在“验收证据”实际生成并可复核后，状态才允许改为 ACCEPTANCE_PASSED。演示数据通过不得把正式数据来源需求改为 ACCEPTANCE_PASSED。

## 2. 项目方实施补充说明追踪

> 独立原文见 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`；本节只做追踪，不改写原需求书正文。

| 需求编号 | 补充要求 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| SUP-01 | 优先完成汇率爬取和存取。 | F. 项目方实施补充 | P0-最高 | PBOC Provider、storage | D1-T01；D1-T02；D1-T03；D1-T04；D1-T05 | Day1真实获取EUR/CNY、USD/CNY并生成raw JSON。 | 官方raw、来源引用、连通与冒烟记录 | BASELINED |
| SUP-02 | Day1至Day2完成PBOC双币从raw JSON到daily/aggregate CSV、历史读取和多周期聚合的闭环。 | F. 项目方实施补充 | P0-硬门 | ingestion、validation、processing、history、aggregation | D1-T02至D1-T05；D2-T01至D2-T05 | 执行AT-SRC-002，重启后从本地文件读取并复算。 | raw/lifecycle JSON、daily/aggregate CSV、重启查询、测试报告 | BASELINED |
| SUP-03 | 大宗原材料按“合法指定源自动→免费公开信源→Manual”三层降级。 | F. 项目方实施补充 | P0-必须 | Provider路由、配置 | D3-T02；D3-T03；D3-T04；D3-T06 | 对SMM/Asian Metal来源意图×ADC12/AZ91D四个P0序列逐项记录routeDecision并验证非synthetic路线。 | 路由配置、fallbackReason、AT-SRC-005 | BASELINED |
| SUP-04 | 架构至少区分六类逻辑DataProvider。 | F. 项目方实施补充 | P0-冻结 | Provider端口与注册表 | D3-T01 | 契约测试覆盖六类Provider和来源元数据。 | 契约测试、能力矩阵 | BASELINED |
| SUP-05 | Manual数据记录实际来源、标的、业务日期、输入时间、单位、输入方式、校验状态和更新时间。 | F. 项目方实施补充 | P0-必须 | Manual、raw、schema | D3-T04 | 缺任一必填字段不得形成合法候选。 | Manual raw、字段校验、AT-SRC-007 | BASELINED |
| SUP-06 | Manual不得直达面板，必须经标准化、校验、VERIFIED、加工与聚合。 | F. 项目方实施补充 | P0-硬门 | validation、publish、processing | D3-T04；D4-T01；D4-T02；D4-T03；D4-T04 | PENDING手工数据从所有业务入口不可见，VERIFIED后才可用。 | 状态时间线、门禁测试、文件证据 | BASELINED |
| SUP-07 | 免费公开信源必须显示真实站名和引用，不得冒充SMM/Asian Metal。 | F. 项目方实施补充 | P0-硬门 | provenance、UI、Agent | D3-T03；D3-T06；D7-T02；D7-T03；D8-T03 | 在raw、API、UI、预警、EvidencePack核对actualSourceName。 | AT-SRC-006、AT-SRC-008及截图 | BASELINED |
| SUP-08 | 指定商业源不可合法自动获取只影响对应自动能力，不阻塞整个P0；禁止绕过登录、验证码、会员或反爬。 | F. 项目方实施补充 | P0-合规门禁 | Provider治理、风险、验收 | D1-T01；D3-T02；D10-T05 | 审查访问方式；替代路线通过后整体P0可继续，任何绕过行为直接失败。 | 能力矩阵、条款证据、路由决策、验收结论 | BASELINED |

## 3. H01-H09 官方硬性验收追踪

| 需求编号 | 需求描述 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| H01 | 随机抽取历史自然月，系统能够展示指定货币和原材料的每日加工均值，并正确计算月度、季度、半年度、年度均值。 | B. 官方验收要求 | P0-硬性 | 每日加工、周期聚合、历史查询、面板 | D1-T03（精度/文件契约）；D2-T03；D2-T04；D4-T03；D4-T04；D4-T05 | 使用冻结黄金数据随机选择自然月，逐日及逐周期独立复算，并核对面板与持久化文件。 | 黄金数据复算表、daily/aggregate CSV、接口响应、面板截图、AT-AGG-001/002/003 | BASELINED |
| H02 | 全链路计算准确且无精度流失。 | B. 官方验收要求 | P0-硬性 | BigDecimal 规范、计算引擎、序列化 | D1-T03；D2-T03；D2-T04；D4-T03；D4-T04；D4-T05 | 覆盖循环小数、大数、尾零、多层聚合；检查业务真值链路无 float/double，结果与独立期望值完全一致。 | AT-PUB-001/002/003、AT-PREC-001/002/003及黄金差异报告 | BASELINED |
| H03 | 程序目录中能够直接检查到规定格式的 JSON/CSV 数据文件。 | B. 官方验收要求 | P0-硬性 | 文件存储、桌面包目录 | D1-T03；D2-T02；D2-T03；D9-T05；D10-T01 | 在 Windows 便携目录启动、采集和计算后，直接检查 data 目录及规定文件。 | JSON/CSV 样本、release-manifest、AT-FILE-001/002、AT-OPS-002 | BASELINED |
| H04 | 系统运行时不存在 MySQL、Redis、SQLite、H2 或其他隐藏数据库运行进程。 | B. 官方验收要求 | P0-硬性 | 后端骨架、文件存储、Windows 运行检查 | D1-T03；D2-T02；D2-T03；D10-T01 | 在干净 Windows 环境启动应用，检查进程、端口、依赖和程序目录，不得出现数据库服务或数据库文件。 | 依赖/进程/端口/目录清单、AT-OPS-001 | BASELINED |
| H05 | 修改系统时间触发跨期操作后，系统能够自动创建新的轮转文件。 | B. 官方验收要求 | P0-硬性 | 调度、时间状态、文件轮转 | D5-T01；D10-T02 | 修改Windows系统时间跨月、跨半年或跨年，触发调度并检查新卷创建、旧卷未覆盖及任务幂等。 | AT-TIME-001/002/003/004及文件时间线 | BASELINED |
| H06 | 存在多份历史轮转文件时，系统能够执行跨年度读取、拼接、去重和排序。 | B. 官方验收要求 | P0-硬性 | 跨文件历史查询、去重排序 | D5-T02；D10-T02 | 准备跨2025-12至2026-01的多卷数据，包含重复和乱序记录，查询后核对连续性、去重和排序。 | AT-XR-001/002、多卷fixture、查询响应 | BASELINED |
| H07 | 用户能够动态停止或新增监测标的，不需要修改程序代码。 | B. 官方验收要求 | P0-硬性 | 动态配置、Provider注册、调度联动 | D5-T03；D5-T04；D8-T01；D10-T04 | 运行中停用欧元、新增英镑或替换材料标的，重启后配置仍有效，过程中不修改代码。 | AT-CFG-001/002/004、配置与调度证据 | BASELINED |
| H08 | 新增标的后能够获取当日数据，并启动历史回填和历史均值计算。 | B. 官方验收要求 | P0-硬性 | 当前采集、历史回填、重算任务 | D5-T03；D5-T04；D8-T01；D10-T04 | 新增受Provider支持的标的，检查当日采集、回填任务状态及历史每日和多级均值文件。 | AT-CFG-002/003/004、回填与历史文件 | BASELINED |
| H09 | 配置修改后面板自动重构，旧标的隐藏，新标的显示，系统无异常，历史数据不得被删除。 | B. 官方验收要求 | P0-硬性 | 配置事件、面板、历史保留 | D5-T03；D5-T04；D8-T01；D10-T04 | 停用、增加、替换标的后检查面板联动；查询旧标的历史并核对文件仍存在；检查无未处理异常。 | AT-UI-001/002、配置版本与旧历史哈希 | BASELINED |

## 4. 官方功能与交付要求追踪

| 需求编号 | 需求描述 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| F01 | 系统以 Windows 桌面端为主运行。 | A. 官方需求 | P0-必须 | Electron 桌面壳、启动器、发布包 | D9-T01；D9-T02；D9-T03；D9-T04；D10-T01 | 在未安装 Java、Node.js、Maven、Docker 和数据库的干净 Windows 环境双击 EXE 启动、使用并退出。 | 干净机录屏、进程清单、启动/退出日志、便携目录清单 | BASELINED |
| F02 | 每日定时获取权威数据；PBOC必须官方自动获取，原材料按项目方认可的三层合法路线接入。 | A+F. 官方需求与实施补充 | P0-必须 | 调度、采集编排、Provider路由 | D1-T02至D1-T05；D2-T05；D3-T01至D3-T06 | PBOC执行真实自动任务；材料逐标的验证合法指定源、FreePublic或Manual中的一条路线，Synthetic不可替代。 | 调度日志、raw、routeDecision、实际来源、fallbackReason、失败恢复记录 | BASELINED |
| F03 | 汇率来源于中国人民银行；默认EUR/CNY、USD/CNY，并形成每日、月、季、半年、年结果。 | A+F. 官方需求与实施补充 | P0-最高/口径待确认 | OfficialWeb、汇率标准化、文件、历史 | D1-T02至D1-T05；D2-T01至D2-T05 | Day1-2完成PBOC→raw/lifecycle JSON→PARSED/PENDING→VALIDATED→PUBLISHED+VERIFIED类状态→daily/aggregate CSV→重启历史读取。 | PBOC来源依据、双币raw、校验记录、daily/aggregate CSV、AT-SRC-002 | BASELINED |
| F04 | ADC12、AZ91D原材料监测优先使用合法SMM通道；不可合法自动获取时按FreePublic→Manual降级，展示实际来源和各周期均值。 | A+F. 官方需求与实施补充 | P0-必须/指定源能力条件化 | 材料Provider、标准化、面板 | D3-T01至D3-T06；D4-T01至D4-T05；D7-T02至D7-T04 | SMM合法自动能力可用时验证该通道；否则记录不可用依据并验证免费或Manual全链，不得冒充SMM。 | 能力矩阵、路由决策、actualSourceName、raw/聚合文件、面板截图 | BASELINED |
| F05 | ADC12、AZ91D原材料监测优先使用合法Asian Metal通道；不可合法自动获取时按FreePublic→Manual降级，展示实际来源和各周期均值。 | A+F. 官方需求与实施补充 | P0-必须/指定源能力条件化 | 材料Provider、标准化、面板 | D3-T01至D3-T06；D4-T01至D4-T05；D7-T02至D7-T04 | Asian Metal合法自动能力可用时验证该通道；否则记录不可用依据并验证免费或Manual全链，不得冒充Asian Metal。 | 能力矩阵、路由决策、actualSourceName、raw/聚合文件、面板截图 | BASELINED |
| F06 | 建立校验规则；所有OfficialWeb、AuthorizedApi、FreePublic、Manual、LocalImport数据只有校验通过才可进入面板、加工、聚合、预警或Agent。 | A+F. 官方需求与实施补充 | P0-必须 | 标准化、质量校验、发布门禁 | D2-T01；D2-T02；D3-T04；D4-T01；D4-T02；D4-T05 | 对各Provider注入PENDING、REJECTED、CONFLICT和合法数据，从所有业务入口验证门禁。 | 规则版本、状态时间线、隔离文件、门禁集成测试 | BASELINED |
| F07 | 所有自动、免费公开、手工和导入的原始每日数据必须以本地 raw JSON 持久化并保留实际来源。 | A+F. 官方需求与实施补充 | P0-必须 | raw存储、来源审计、恢复 | D1-T03至D1-T05；D2-T05；D3-T03至D3-T06 | 分别完成PBOC、FreePublic/Manual样本后检查不可变raw、独立Lifecycle、来源、输入方式、双哈希和重启恢复。 | raw/lifecycle JSON、manifest、actualSourceName、哈希、恢复测试 | BASELINED |
| F08 | 每日加工结果必须持久化，不能只在查询时临时计算。 | A. 官方需求 | P0-必须 | 每日加工、daily文件 | D2-T03；D4-T03；D4-T05 | 先验证PBOC双币daily CSV落盘，再对材料各合法路线运行每日加工并重启读取。 | daily CSV、sum/validCount/avg、重启读取记录 | BASELINED |
| F09 | 月度、季度、半年度、年度聚合结果必须持久化。 | A. 官方需求 | P0-必须 | 周期聚合、aggregate文件 | D2-T04；D4-T04；D4-T05 | 先完成PBOC最小多周期闭环，再运行跨周期黄金数据，检查四级文件可重建且无精度流失。 | `data/processed/aggregate/<itemId>/{month,quarter,halfyear,year}/YYYY.csv`、复算报告、sourceFingerprint | BASELINED |
| F10 | 预警记录必须写入本地文件留存。 | A. 官方需求 | P0-必须 | 规则预警、成本影响、warning 存储 | D5-T05；D8-T02；D10-T04 | 触发阈值边界、数据不足和重复运行场景，检查预警证据、去重和重启读取。 | warning JSON、ruleId、evidenceRefs、面板截图、测试报告 | BASELINED |
| F11 | 系统必须自动分卷/轮转，例如按月或按年生成新文件，避免单文件过大。 | A. 官方需求 | P0-必须 | 文件轮转、时间状态 | D5-T01；D10-T02 | 以可控时钟和 Windows 系统时间分别跨期，检查新卷创建、旧卷保留和恢复。 | 轮转前后目录、time-state、日志、文件哈希 | BASELINED |
| F12 | 面板和智能体进行跨周期分析时，必须跨文件检索、拼接历史数据并保持连续。 | A. 官方需求 | P0-必须 | 历史查询、Agent 工具 | D5-T02；D6-T01；D10-T02 | 对跨月和跨年范围执行面板及 Agent 查询，核对结果、缺失说明、去重和排序。 | 跨卷查询响应、Agent EvidencePack、面板截图、测试报告 | BASELINED |
| F13 | 用户能够自主动态配置监测对象，并联动采集、历史回填、面板和历史保留。 | A. 官方需求 | P0-必须 | 动态配置、回填、面板联动 | D5-T03；D5-T04；D8-T01；D10-T04 | 执行停用欧元、新增英镑、替换 AZ91D 的完整业务验收。 | configVersion、任务状态、前后截图、历史文件哈希、验收录屏 | BASELINED |
| F14 | 交付完整工程源代码及 Windows 本地化部署手册。 | A. 官方需求 | P0-必须 | 全工程、文档、发布 | D9-T01至D9-T05；D10-T01；D10-T05 | 按 release-manifest 清点源码、桌面应用、内置 JRE、数据目录、配置、README 和部署手册，并在干净机按手册复现。 | release-manifest、源码清单、部署手册、干净机测试报告 | BASELINED |

## 5. 项目冻结架构决策追踪

以下 C 类内容不是官方原文，但已经由项目决策冻结。任何修改必须进入决策日志并重新评估 H01-H09 和 F01-F14。

| 需求编号 | 需求描述 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| C01 | P0 后端采用 Java 17、单个 Spring Boot 工程和模块化单体，按业务包划分，不拆微服务。 | C. 架构冻结决策 | P0-冻结 | 后端工程骨架 | D1-T03 | 编译、启动并检查模块依赖；仓库仅有一个后端运行单元。 | 构建日志、模块依赖图、健康检查 | FROZEN |
| C02 | 不使用任何数据库栈、驱动、服务或文件，包括MyBatis/JPA/JDBC/R2DBC及MySQL、Redis、SQLite、H2。 | C. 架构冻结决策 | P0-冻结 | 依赖治理、文件存储 | D1-T03；D2-T02；D10-T01 | 审计依赖树、进程、端口和程序目录。 | 依赖报告、进程/端口/文件清单 | FROZEN |
| C03 | 前端采用 Vue3，承载仪表盘、历史趋势、质量、配置、预警和 Agent 工作台。 | C. 架构冻结决策 | P0-冻结 | Web 前端 | D7-T01；D7-T02；D7-T03；D8-T01；D8-T02 | 构建前端并执行六类页面的 Web 业务验收。 | 构建产物、页面截图、Web 验收报告 | FROZEN |
| C04 | Windows 桌面端采用 Electron，不采用 JavaFX。 | C. 架构冻结决策 | P0-冻结 | Electron 桌面壳 | D9-T01；D9-T02；D9-T03；D9-T04 | 无开发服务器启动桌面应用，验证窗口、后端生命周期和退出清理。 | 便携包、启动/退出录屏、进程日志 | FROZEN |
| C05 | Electron 启动内置 JRE 和 Spring Boot 子进程；用户无需安装 Java、Node.js、Maven、Docker 或数据库。 | C. 架构冻结决策 | P0-冻结 | 桌面启动器、JRE、端口与进程 | D9-T02；D9-T03；D9-T04；D10-T01 | 在干净 Windows 环境双击启动，检查动态端口、单实例和安全退出。 | 干净机环境清单、进程树、日志 | FROZEN |
| C06 | JSON/CSV 是唯一业务持久化方式；raw/lifecycle等使用JSON，daily/aggregate使用CSV；不存在数据库文件、数据库索引或隐藏数据库。 | C. 架构冻结决策 | P0-冻结 | 文件存储 | D1-T03；D2-T02；D2-T03；D10-T01 | 重启恢复全部业务数据并执行无数据库审计。 | data 目录、文件清单、恢复测试、进程清单 | FROZEN |
| C07 | 使用Jackson及RFC 4180兼容CSV库完成无损读写；data先于manifest原子提交，raw不覆盖，可变文件备份后原子替换，ATOMIC_MOVE不可用即fail-fast，并按dirty marker确定性恢复。 | C. 架构冻结决策 | P0-冻结 | 文件基础设施 | D1-T03；D2-T03 | 执行往返精度、目标碰撞、两文件各崩溃窗口、损坏文件、ATOMIC_MOVE不可用和Windows文件占用测试。 | 文件基础设施测试报告、恢复记录 | FROZEN |
| C08 | 所有金额、价格、汇率和聚合运算从字符串构造 BigDecimal，禁止 float/double 进入业务真值链路。 | C. 架构冻结决策 | P0-冻结 | 数值规范、计算引擎 | D1-T03；D2-T03；D2-T04；D4-T03；D4-T04 | 静态检查和黄金数据精度测试。 | 依赖/代码扫描、精度测试结果 | FROZEN |
| C09 | 原始精度原样保存；中间过程不得按展示精度提前舍入；每个标的配置计算精度、展示精度和舍入规则。 | C. 架构冻结决策 | P0-冻结 | 数据字典、计算规则 | D1-T03；D1-T04；D2-T03；D2-T04；D4-T03；D4-T04 | 检查数据契约及循环小数、尾零、不同标的精度测试。 | calculation-rules、数据样本、单元测试 | FROZEN |
| C10 | 聚合结果保存 sum、validCount 和 avg；缺失日不按 0；季度、半年、年度从有效每日加工值重新计算，禁止平均已舍入月均值。 | C. 架构冻结决策 | P0-冻结 | 每日加工、周期聚合 | D1-T03（schema）；D2-T03；D2-T04；D4-T03；D4-T04；D4-T05 | 使用含缺失、不同月样本数和循环小数的黄金数据复算。 | 聚合文件、独立复算表、门禁报告 | FROZEN |
| C11 | 前端只展示 Java 返回的精确字符串；图表数值副本仅用于绘图，不得重新计算或持久化。 | C. 架构冻结决策 | P0-冻结 | Vue 数据适配、图表 | D7-T01；D7-T02；D8-T01 | 对比 API 精确字符串与页面；检查前端不存在业务均值重算。 | API/页面对比、前端测试和审计结果 | FROZEN |
| C12 | 模型调用通过 LLMService 解耦；P0 实现 CloudLLMService，LocalLLMService 仅保留接口和扩展点。 | C. 架构冻结决策 | P0-冻结 | LLM 适配层 | D6-T03 | 使用测试替身和云实现执行同一请求契约；切换不影响 Agent 领域对象。 | 契约测试、配置样例、结构化响应 | FROZEN |
| C13 | 云模型不可用时使用 Java 模板报告降级；P0 不依赖 RAG、本地模型或 LoRA。 | C. 架构冻结决策 | P0-冻结 | Agent 降级、范围控制 | D6-T05；D10-T03 | 断网或模拟模型超时，验证结构化模板报告仍可生成并留存。 | 故障注入日志、模板报告、验收记录 | FROZEN |
| C14 | 所有六类逻辑Provider固定进入：获取/输入→raw（RECEIVED）→标准化候选（PARSED）→校验（VALIDATED）→发布（PUBLISHED）→每日加工→多级聚合→持久化→预警→面板/查询/Agent。 | C. 架构冻结决策 | P0-冻结 | 数据全链路 | D1-T04；D2-T01至D2-T05；D3-T01至D3-T06；D4-T01至D4-T04 | 通过runId追踪自动、FreePublic和Manual批次，验证均不可绕过发布门禁。 | runId时间线、各层文件、集成测试 | FROZEN |
| C15 | `ValidationStatus`精确固定为PENDING、VERIFIED、VERIFIED_WITH_NOTICE、REJECTED、CONFLICT并独立于`ProcessingStage`；合法组合、唯一初态、迁移边和条件必填按总计划8.4.3。只有PUBLISHED+两种VERIFIED可进入正式业务层。 | C. 架构冻结决策 | P0-冻结 | 双维状态机、发布门禁 | D1-T03；D2-T01；D2-T02；D3-T04；D4-T01；D4-T02 | 对组合、相邻迁移边和六类Provider逐一访问加工、聚合、面板、预警和Agent。 | 双维状态迁移测试、拒绝响应、quarantine投影 | FROZEN |
| C16 | 三个失败终态不得覆盖合法历史值，必须生成QuarantineProjectionV1；PENDING或可发布VALIDATED不得被错误隔离。面板可显示上一合法值，但必须显示业务日期和过期状态。 | C. 架构冻结决策 | P0-冻结 | 隔离、最后合法值、质量展示 | D1-T03；D4-T01；D4-T02；D7-T02 | 注入各终态和PENDING，核对旧值哈希不变、投影生成边界、面板显示stale。 | 新旧哈希、quarantine文件、timeline、面板截图 | FROZEN |
| C17 | DataProvider至少区分OfficialWebDataProvider、AuthorizedApiDataProvider、FreePublicDataProvider、ManualDataProvider、LocalImportDataProvider、SyntheticDemoDataProvider六类逻辑接入；实现类可合并，来源身份不可合并。 | F+C. 补充说明与架构冻结 | P0-冻结 | Provider端口与注册表 | D3-T01至D3-T06 | 契约测试验证六类Provider输出统一记录，并保留providerType、accessMethod、actualSourceName。 | Provider契约测试、能力矩阵 | FROZEN |
| C18 | 合法OfficialWeb、AuthorizedApi、FreePublic及可追溯Manual/LocalImport可经校验进入真实数据链；Synthetic仅属演示模式并全页面标明。 | F+C. 补充说明与架构冻结 | P0-冻结 | 模式、来源标签、发布门禁 | D2-T02；D3-T03至D3-T05；D4-T02；D7-T02至D7-T04 | 验证真实来源均先校验；Synthetic不能进入正式验收；免费/手工来源显示真实身份。 | 模式隔离、来源真实性测试、页面截图 | FROZEN |
| C19 | 禁止绕过登录、验证码、会员、访问控制或反爬；指定商业源不可合法自动获取时记录能力不可用并按FreePublic→Manual降级，不阻塞整个P0。 | F+C. 补充说明与合规门禁 | P0-冻结/合规门禁 | 数据来源治理 | D1-T01；D3-T02至D3-T06；D10-T05 | 审查条款、适配器行为、routeDecision和fallbackReason；任何绕过行为直接失败。 | 访问依据、能力矩阵、路由记录、验收报告 | FROZEN |
| C20 | Agent 固定流程为意图识别 → Java 受控工具 → 已验证数据 → Java 指标 → EvidencePack → LLM 解释 → 证据核验 → 结构化报告持久化。 | C. 架构冻结决策 | P0-冻结 | Agent 编排、EvidencePack、报告 | D6-T01；D6-T02；D6-T03；D6-T04 | 跟踪一次 Agent 请求的工具调用、证据引用、模型响应和后端核验。 | Agent trace、EvidencePack、结构化报告 | FROZEN |
| C21 | P0 Agent 工具至少包含 series.resolve、history.query、period.metrics、quality.inspect、cost.impact、warning.explain、provenance.trace。 | C. 架构冻结决策 | P0-冻结 | Agent 只读工具 | D6-T01；D6-T02 | 对七个工具执行契约、权限、日期范围和证据字段测试；不得提供任意文件、HTTP 或配置写工具。 | 工具契约、权限测试、调用日志 | FROZEN |
| C22 | LLM 不判断数据有效性、不读取未校验原始文件、不计算均值/成本/风险、不编造来源或无证据归因；只接收 Java 结构化指标和证据摘要。 | C. 架构冻结决策 | P0-冻结 | LLM 输入策略、报告核验 | D6-T02；D6-T03；D6-T04 | 提示注入和故障测试；核验模型无法取得原始路径，数字和引用必须匹配 EvidencePack。 | 提示注入测试、声明核验报告、降级报告 | FROZEN |
| C23 | 最终提供 Windows 便携桌面应用目录和 ZIP；JAR 仅作为内部组件，不能作为唯一交付；Docker 不能成为最终用户运行前提。 | C. 架构冻结决策 | P0-冻结 | Windows 打包、发布 | D9-T01；D9-T02；D9-T03；D9-T04；D10-T01 | 解压 ZIP 后直接启动，检查内置组件和用户环境前置条件。 | ZIP、release-manifest、干净机测试 | FROZEN |
| C24 | P1 仅包含 Ollama/Qwen 连通性等非阻断增强；正式本地模型、RAG、vLLM、LoRA 属于 P2，均不得阻塞 P0。 | C. 架构冻结决策 | 范围冻结 | 范围管理 | D1-T01；D10-T05 | 审计 P0 任务和发布包，不应存在这些能力作为运行前提。 | 任务清单、依赖清单、发布验收记录 | FROZEN |
| C25 | Day1至Day2必须先完成PBOC EUR/CNY、USD/CNY真实获取与文件闭环，AT-SRC-002通过前不得开始材料Provider实现。 | F+C. 补充说明与架构冻结 | P0-最高 | PBOC垂直切片 | D1-T01至D1-T05；D2-T01至D2-T05 | 执行Day2硬退出门。 | 双币全链文件、测试报告、台账 | FROZEN |
| C26 | 三层降级是受控配置决策而非网络错误时静默换源；免费/Manual数据不得冒充SMM/Asian Metal。 | F+C. 补充说明与架构冻结 | P0-冻结 | 路由、provenance、UI、Agent | D3-T02至D3-T06；D7-T02至D7-T04；D8-T03 | 检查routeDecision、来源元数据和各业务出口标签。 | AT-SRC-005至AT-SRC-008 | FROZEN |
| C27 | TaskExecutionStatus、AcceptanceStatus、TraceabilityStatus三套状态命名空间独立；任务DONE不自动等于任何AT PASS。D1-T02字段事实为EXTERNAL_CONFIRMED，本轮曾因重放证据不完整而重开；实时任务状态唯一以docs/05为准，本矩阵不复制瞬时状态。 | C. 编码前基线对齐 | P0-冻结 | 进度、验收、PBOC门禁 | D1-T02；D1-T03；D1-T05；D2-T05 | 比对台账任务记录与AT-SRC-002；失败证据下AT必须为NOT_RUN、BLOCKED或FAIL，不能PASS。 | 任务记录、AT记录、失败证据 | FROZEN |
| C28 | 每条LifecycleRecord持久化ProcessingStage与ValidationStatus两个字段；只有PUBLISHED+VERIFIED类组合可进入业务读模型。 | C. 编码前基线对齐 | P0-冻结 | ingestion、validation、storage、查询 | D1-T03；D2-T01；D2-T02；D3-T04；D4-T01；D4-T02 | 执行允许/非法组合矩阵与业务读模型过滤测试。 | LifecycleRecord、迁移日志、门禁测试 | FROZEN |
| C29 | 物理业务数据目录唯一冻结为data/config/monitor-series.json与data/config/history/<configVersion>.json、data/raw、data/staging、data/quarantine、data/processed/daily、data/processed/aggregate/<itemId>/{month,quarter,halfyear,year}、data/warning、data/report、data/runtime/{jobs/active,jobs/history,dirty,conflicts/raw}；monitor-series是唯一活动配置，history只是不可变审计快照；根runtime仅JRE、根logs仅日志。normalized/published等只能是逻辑名称，conflicts/raw不是正常数据链。 | C. 编码前基线对齐 | P0-冻结 | storage、桌面发布、验收 | D1-T03；D2-T03；D9-T02；D9-T05 | 扫描目录树和文件路由；不得出现竞争性业务真值目录或第二活动配置。 | 目录树、路径测试、AT-FILE-001 | FROZEN |
| C30 | RawReceiptV1按单item建档；一次多item响应共享acquisitionId，但每个item拥有独立runId/rawRef/timeline并保存相同完整payload bytes/hash。raw文件固定为`raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json`并按receivedAt分区，processed按已验证businessDate分区。 | C. D1-T03编码契约 | P0-冻结 | ingestion、storage、path | D1-T03；D1-T04；D2-T01 | 双币共享响应夹具生成两个互不冲突的raw/timeline；日期缺失仍可保存raw。 | schema样例、路径测试、共享响应测试 | FROZEN |
| C31 | RawReceipt不保存生命周期状态；LifecycleTimelineV1保留全部recordVersion和不可变CandidateV1并强制合法迁移/条件必填；QuarantineProjectionV1只投影三个失败终态。 | C. D1-T03编码契约 | P0-冻结 | lifecycle、storage | D1-T03；D2-T01；D2-T02 | 对4×5组合、所有相邻迁移边和条件字段参数化测试；重启后从PUBLISHED timeline生成daily；核对投影边界。 | timeline JSON、CandidateV1、QuarantineProjectionV1、迁移矩阵 | FROZEN |
| C32 | 唯一配置`supplymind.data-root`解析为规范绝对路径；业务data先于manifest提交；raw与config history不覆盖、异hash incoming完整留证，可变文件原子替换；DirtyMarkerV1以单调markerRevision、targets[]和逐target phase表达单文件/配置激活/聚合批次，并可从合法canonical/tmp/bak候选组确定性自恢复；配置变更先提交history数据/manifest再激活monitor-series数据/manifest；manifest不递归，ATOMIC_MOVE不可用即fail-fast。 | C. D1-T03编码契约 | P0-冻结 | storage、desktop、recovery | D1-T03；D2-T03；D9-T01；D9-T05 | 临时dataRoot、目标碰撞、业务及marker自身各崩溃窗口、同revision异字节冲突、路径穿越、Windows占用、dirty状态矩阵、配置targets[]两项（history/active，各含data+manifest，共四个物理文件）提交、manifest恢复测试。 | 配置/路径/原子提交测试、冲突证据、dirty/manifest样例 | FROZEN |
| C33 | monitor-series顶层configVersion/mode及item级来源意图、Provider配对、routeDecision/fallback、动态替换、外部代码/解析键、baseCurrency/currency/unit、calculationVersion/scale/rounding/calendar字段完整冻结；每个已生效版本有不可变history快照，RawReceipt保存并可解析启动时configVersion，PBOC两个itemId正式固定。daily/aggregate逐行保存configVersions与计算上下文，使用完整inputRefs；aggregate按来源身份和计算上下文分组并保存确定性sourceFingerprint。 | C. D1-T03编码契约 | P0-冻结 | config、codec、provenance | D1-T03；D1-T04；D2-T03；D2-T04；D3-T02；D5-T03；D4-T03；D4-T04 | 配置/history合法非法黄金文件、双币映射、运行中配置切换、同日多输入及来源/计算规则切换聚合测试。 | 活动配置与history快照、raw configVersion、CSV黄金文件、inputRefs/sourceFingerprint向量 | FROZEN |
| C34 | daily精确sum后按calculationScale/roundingMode求avg；aggregate直接从有效daily avg重算且不读取月均或displayScale结果；CSV显式保存validationStatus、validationVersion、configVersions、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion，不同校验结论/版本或计算版本分行且aggregate inputRefs唯一定位daily行；qualityStatus严格由complete派生。计算/日历版本定义追加式不可改写，缺失不补0，正式口径仍受EXT-03/EXT-06门禁。 | C. D1-T03/D2计算契约 | P0-冻结/业务口径待确认 | codec、daily、aggregate、provenance | D1-T03（schema）；D2-T03；D2-T04；D4-T03；D4-T04 | 固定12位计算/9位展示、循环小数、校验/计算规则切换、日历缺失和从daily直接重算黄金测试。 | CALCULATION-RULES、配置快照、daily/aggregate CSV、AT-PREC-002/003 | FROZEN |

## 6. 外部待确认项追踪

| 需求编号 | 需求描述 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| EXT-01 | PBOC EUR/CNY、USD/CNY的具体发布字段、单位、报价方向与业务日。 | D. 外部待确认 | P0-PBOC口径 | 汇率Series、OfficialWeb | D1-T02；D1-T04；D2-T01 | 保存官方raw并核对发布字段和换算；实施不等待商业源。 | docs/evidence/D1-T02/ 中的 PBOC 官方页面、字段映射、样例和确认记录 | EXTERNAL_CONFIRMED（字段事实；D1-T02重放证据、Java HTTPS与全链仍待后续任务） |
| EXT-02 | 各实际来源ADC12/AZ91D的规格、地区、单位、含税口径和价格字段。 | D. 外部待确认 | P0-材料口径 | 材料Series、Provider | D3-T02；D3-T03；D3-T04；D3-T06 | 按实际来源×品种确认，不跨规格混算。 | 来源序列映射、数据字典、确认记录 | OPEN_EXTERNAL |
| EXT-03 | “每日加工均值”的准确业务定义。 | D. 外部待确认 | P0-计算口径 | 每日加工 | D2-T03；D4-T03 | 冻结官方日均字段或sum/validCount规则并制作黄金样例。 | calculation-rules、手工复算表、确认记录 | OPEN_EXTERNAL |
| EXT-04 | SMM/Asian Metal是否存在合法公开页面、接口或已授权自动路径。 | D. 外部待确认 | 指定源自动能力/非全局阻塞 | 来源合规、路由 | D3-T02；D3-T06 | 能合法自动则使用；否则记录依据并转FreePublic→Manual。 | 条款/授权依据、能力矩阵、routeDecision | OPEN_EXTERNAL_NON_BLOCKING |
| EXT-05 | 新增标的及初始化所需的历史回填范围。 | D. 外部待确认 | P0-验收范围 | 回填、历史能力 | D5-T04；D10-T04 | 确认起止日期；无自动历史时可由Manual/LocalImport进入同一门禁。 | 书面范围、回填报告、来源审计 | OPEN_EXTERNAL |
| EXT-06 | 节假日、周末、停报和未发布日如何计入完整率及均值。 | D. 外部待确认 | P0-计算口径 | 日历、完整率 | D2-T03；D4-T03；D4-T04 | 缺失不补0，日历规则配置化。 | 日历配置、黄金样例、完整率测试 | OPEN_EXTERNAL |
| EXT-07 | 价格、汇率、质量和成本影响预警阈值。 | D. 外部待确认 | P0-业务规则 | 预警规则 | D5-T05；D10-T04 | 业务方确认阈值；未确认时只使用显式测试规则。 | alert-rules、确认记录、边界测试 | OPEN_EXTERNAL |
| EXT-08 | 动态调价公式、成本权重和自动执行边界。 | D. 外部待确认 | P0-业务边界 | 成本影响、Agent | D5-T05；D6-T02；D10-T04 | P0默认非约束建议，不自动调价。 | 公式确认、审批说明 | OPEN_EXTERNAL |
| EXT-09 | “跨卷”是多个轮转文件还是物理磁盘卷。 | D. 外部待确认 | P0-存储边界 | 轮转、历史查询 | D5-T01；D5-T02；D10-T02 | 默认同一data根目录多个轮转文件。 | 术语确认、跨卷测试 | OPEN_EXTERNAL |
| EXT-10 | 获认可的免费公开材料信源、许可条款、更新频率和字段映射。 | D. 外部待确认 | 免费源能力/非全局阻塞 | FreePublic、provenance | D3-T03；D3-T06 | 选择无需绕限制且可留证据的来源；未确认时Manual保底。 | URL、条款、字段映射、解析测试 | OPEN_EXTERNAL_NON_BLOCKING |
| EXT-11 | Manual是否要求实名操作人、复核人及附件证据。 | D. 外部待确认 | 审计深度/非全局阻塞 | Manual、审计 | D3-T04；D7-T04 | P0先记录operatorRef、实际来源和版本；复核深度配置化。 | 字段定义、确认记录、审计样例 | OPEN_EXTERNAL_NON_BLOCKING |

## 7. P1/P2 增强项追踪

| 需求编号 | 需求描述 | 来源类别 | 优先级 | 对应模块 | 计划任务 ID | 验收方式 | 验收证据 | 当前状态 |
|---|---|---|---|---|---|---|---|---|
| E01 | P1 增加 Ollama/Qwen 连通性验证，不作为 P0 运行依赖。 | E. P1/P2 增强 | P1 | LocalLLMService | P1-T01 | 在完成 P0 后，以同一 LLMService 契约完成本地模型结构化响应冒烟测试。 | P1 连通性报告、契约测试 | DEFERRED_P1 |
| E02 | P2 建设可正式使用的本地模型推理能力。 | E. P1/P2 增强 | P2 | 本地模型服务、模型路由 | P2-T02 | 完成容量、延迟、稳定性、结构化输出和数据安全评测。 | 性能与质量评测报告 | DEFERRED_P2 |
| E03 | P2 引入 RAG 工业知识库。 | E. P1/P2 增强 | P2 | 知识库、检索、引用 | P2-T01 | 使用冻结评测集验证召回、引用可追溯和无证据拒答。 | RAG 评测报告、引用样例 | DEFERRED_P2 |
| E04 | P2 评估 vLLM 等本地推理服务。 | E. P1/P2 增强 | P2 | 模型服务基础设施 | P2-T02 | 在目标硬件执行吞吐、延迟、稳定性和部署评测。 | 基准测试和部署记录 | DEFERRED_P2 |
| E05 | P2 在具备领域数据、授权和评测集后再评估 LoRA 微调。 | E. P1/P2 增强 | P2 | 数据治理、训练与评测 | P2-T02 | 先完成数据授权、基线模型和离线评测，再比较微调收益。 | 数据授权、基线/微调对比报告 | DEFERRED_P2 |
| E06 | Docker 可作为后续开发或运维辅助，但不得替代 P0 Windows 便携桌面交付。 | E. P1/P2 增强 | P1/可选 | 开发环境、运维 | P1-T04 | 验证容器仅为辅助路径，删除 Docker 后正式 Windows 包仍完整可用。 | 辅助部署说明、Windows 独立启动证据 | DEFERRED_P1 |
| E07 | P2 研究预测、情景模拟与多Agent协作，不自动执行调价。 | E. P1/P2 增强 | P2 | 预测、模拟、Agent研究 | P2-T03 | 使用冻结回测集、边界与人审流程评估，不改变P0确定性真值。 | 回测/情景报告、边界审计 | DEFERRED_P2 |
| E08 | P1 增加诊断包、备份恢复演练和可审计数据导出。 | E. P1/P2 增强 | P1 | 运维、备份、导出 | P1-T02 | 在P0通过后验证诊断脱敏、备份恢复一致性和导出可复算。 | 诊断包、恢复报告、导出样例 | DEFERRED_P1 |
| E09 | P1 增加多来源对比、可配置通知和签名安装体验，不改变P0来源真实性与便携交付。 | E. P1/P2 增强 | P1 | 来源比较、通知、桌面发布 | P1-T03 | 验证来源不混算、通知去重/关闭、签名状态透明且无签名仍可回退便携ZIP。 | 对比报告、通知记录、安装/回退测试 | DEFERRED_P1 |

## 8. 外部待确认事项处理表

| 编号 | 问题 | 推荐默认解释 | 不确认风险 | 最晚确认日 | 临时开发方案 | 是否影响正式验收 |
|---|---|---|---|---|---|---|
| EXT-01 | PBOC字段、单位与报价方向 | 按EUR/CNY、USD/CNY人民币中间价建可配置Series，保存官方raw后再转换。 | 错序列会使全部汇率结果失效。 | Day1 | 立即做OfficialWeb连通与raw，不等待材料授权。 | 是，影响PBOC硬门。 |
| EXT-02 | ADC12/AZ91D精确业务口径 | 按每个实际来源×品种分别建序列，规格、地区、含税和单位可配置。 | 不可比报价被混算。 | Day3 | 使用完整元数据黄金样本；Manual保底。 | 是，影响材料值口径。 |
| EXT-03 | 每日加工均值公式 | 已正式接受版本化默认：`calculationVersion=arithmetic-mean-v1`（DEC-053），D2-T03 EXT Gate 已满足。同一daily group全部合法PUBLISHED输入：sum以BigDecimal完整精度求和、validCount只统计合法正式输入、avg仅在最终除法按calculationScale/roundingMode舍入、displayScale仅展示不回写、missing不进入validCount/sum且不补0；未来口径变更须新增calculationVersion与configVersion。 | H01/H02期望不统一。 | Day2 | DEC-053 已生效；黄金数据同时覆盖单值日和多观测日。 | 是（已接受版本化默认）。 |
| EXT-04 | 指定商业源自动能力 | 合法公开/授权自动优先；不可用则FreePublic→Manual，禁止绕过限制。 | 只缺少指定网站自动能力，不应拖停整体P0。 | Day3 | 记录routeDecision/fallbackReason并走替代链。 | 否；仅影响对应能力声明。 |
| EXT-05 | 历史回填范围 | 至少准备连续13个自然月并跨年，最终范围按确认。 | 随机历史月和H08覆盖不足。 | Day5前 | 黄金跨年验证；真实数据可通过Manual/LocalImport进入门禁。 | 是，影响范围。 |
| EXT-06 | 节假日和未发布日 | 已正式接受版本化默认：`calendarVersion=weekday-asia-shanghai-v1`（DEC-054），D2-T03 EXT Gate 已满足。Asia/Shanghai周一至周五为预期业务日期；daily expectedCount=1、missingCount=max(expectedCount-validCount,0)、complete=validCount>=expectedCount；缺失不补0、空月不生成虚构数据；仅覆盖expected-count/completeness，不代表完整法定节假日/调休/停报/特殊交易日日历，提升日历精度须新增calendarVersion与configVersion。 | 完整率和均值权重错误。 | Day2 | DEC-054 已生效；可配置日历和黄金样例。 | 是（已接受版本化默认）。 |
| EXT-07 | 预警阈值 | 规则版本化；未确认时只启用质量告警或显式demo阈值。 | 误报漏报。 | Day5前 | 不宣称测试阈值为生产规则。 | 部分。 |
| EXT-08 | 调价公式和执行权限 | P0仅输出Java计算的影响与非约束建议，不自动修改价格。 | 越权调价。 | Day5前 | 演示成本权重。 | 不影响H01-H09。 |
| EXT-09 | 跨卷含义 | 默认同一data根下多轮转文件，不含多个物理盘。 | 若指物理盘需重构路径发现。 | Day5前 | dataRoot可配置并显著记录假设。 | 是，影响H05/H06。 |
| EXT-10 | 免费公开信源认可与字段映射 | 选无需绕限制、URL可核验、规格可比的来源。 | 来源失效或语义不等价。 | Day3 | 未确认即用Manual保底，不能冒充商业源。 | 否，不阻塞整体P0。 |
| EXT-11 | Manual操作与复核责任 | P0记录operatorRef、输入/更新时间、来源说明和版本；实名/双人复核可配置。 | 审计责任不足。 | Day3 | 先实现必填字段与状态门禁。 | 否，不阻塞基础Manual。 |

## 9. 正式验收门禁与更新规则

1. H01-H09、F01-F14和SUP-01至SUP-08均属于P0基线；没有证据不得宣布完成。
2. TaskExecutionStatus、AcceptanceStatus和TraceabilityStatus独立记录。任务DONE只说明任务范围完成，绝不自动使需求、Day1/Day2退出门禁或AT用例PASS。
3. D1-T02字段事实为EXTERNAL_CONFIRMED；其任务实时状态只看docs/05，调查或重放证据无论处于READY、IN_PROGRESS、REVIEW_PENDING或DONE都不能替代AT-SRC-002。AT-SRC-002应保持NOT_RUN、BLOCKED或FAIL，直到EUR/CNY、USD/CNY均完成真实PBOC自动获取至raw/lifecycle JSON、PARSED/PENDING、VALIDATED、PUBLISHED+VERIFIED类状态、daily/aggregate CSV和重启读取后才能PASS。
4. AT-SRC-001、AT-SRC-005、AT-SRC-008必须PASS；四个P0来源意图×材料序列还必须分别满足“指定商业源合法自动PASS、AT-SRC-006 FreePublic PASS、AT-SRC-007 Manual PASS”三者之一。
5. AT-SRC-003/004若因会员、无公开接口或合法反爬被判N/A_APPROVED_FALLBACK，只表示对应指定源自动能力未实现；认可替代路线PASS后不阻塞整体P0。
6. FreePublic/Manual是项目方认可的真实降级通道，但不得冒充SMM/Asian Metal；Synthetic仍不能作为真实来源证据。
7. 只有ProcessingStage=PUBLISHED且ValidationStatus为VERIFIED或VERIFIED_WITH_NOTICE可进入每日加工、聚合、面板、预警和Agent；Manual不得直达业务层。
8. 物理业务真值目录只能使用C29规定的路径；normalized/published等不得作为竞争性目录。
9. 任何绕过登录、验证码、会员或反爬、来源冒充或手工直达面板均直接判FAIL。
10. E01-E09不得抢占P0时间或成为Windows包运行前提；任务完成后同步本矩阵和进度台账。
11. 需求变化先保存独立官方补充或D类确认记录，再更新矩阵、决策、任务与测试；不得覆盖原始正文。
