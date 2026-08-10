# SupplyMind AI 10天可执行开发任务

> 文档性质：跨窗口可独立执行的任务清单  
> 规范版本：v1.4（任务状态字段与进度锚点可按执行协议更新，需求/契约/依赖/测试/DoD冻结）  
> 执行顺序：P0完成并通过退出门禁后，才允许进入P1；P2不进入本次10天交付  
> 当前进度锚点：Day 1 与 Day 2 已完成（D1-T01～D1-T05、D2-T01～D2-T05 均`DONE`；Day 1=`COMPLETE`、Day 2=`COMPLETE`；AT-SRC-002=`PASS`、DEC-056 implementation=`PASS`）；下一正式任务：`D3-T01` 六类DataProvider端口、注册表与来源模型，`readyState=READY`、`TaskExecutionStatus=NOT_STARTED`。
> 功能冻结：Day 8完成后禁止新增业务功能，仅允许修复P0验收缺陷

## 1. 新窗口执行协议

每个新Codex窗口开始任务前必须：

1. 完整读取`00-OFFICIAL-REQUIREMENTS.md`、`00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`、`01-PROJECT-MASTER-PLAN.md`、本文件和`05-PROGRESS-LEDGER.md`。
2. 确认目标 `TaskExecutionStatus` 为`READY`，且所有依赖任务为`DONE`；它与AT用例的`AcceptanceStatus`分开记录。
3. 检查工作区现有修改，不覆盖用户或其他任务的未提交内容。
4. 只执行一个任务编号；发现需求冲突时停止并登记，不自行修改冻结决策。
5. 执行任务规定的测试，保存证据，更新进度台账后才可结束；任务`DONE`绝不自动改写任何AT用例为`PASS`。
6. 开发/调查窗口完成产物后先将状态改为`REVIEW_PENDING`并交回技术负责人；只有Code Review通过后，技术负责人才能改为`DONE`，未通过则改回`READY`或进入相应`BLOCKED_*`。

PBOC EUR/CNY、USD/CNY真实获取与存取是最高P0，Day2闭环通过前不得启动材料Provider。SMM/Asian Metal商业授权缺失只使对应指定源自动能力条件化；材料必须按合法指定源自动→FreePublic→Manual降级，不能停止其他P0，也不得绕过访问限制。

D1-T02的外部访问失败证据只能完成调查产物，不能让PBOC真实数据验收、Day1/Day2退出门禁或AT-SRC-002记为`PASS`。D1-T03开始前必须满足C27-C34编码前基线，并完成D1-T02逐币种`fieldContractResult`、环境限定的`connectionResult`和可复现重放证据归档。

## 2. 需求编号速查

| 编号 | 摘要 |
|---|---|
| H01-H09 | 官方九项硬性验收要求，以需求追踪矩阵原文为准 |
| SUP-01至SUP-08 | 项目方实施补充：汇率优先、六类Provider、三层降级、Manual门禁、来源真实性 |
| C27-C34 | 编码前基线：状态命名空间、唯一目录、单item raw/run、Lifecycle/Candidate/Quarantine、活动config+不可变history、inputRefs/sourceFingerprint、显式计算上下文、dataRoot/manifest/原子提交 |
| F01 | Windows桌面运行 |
| F02 | PBOC自动获取；材料按合法三层路线接入 |
| F03 | PBOC EUR/CNY、USD/CNY真实闭环 |
| F04/F05 | ADC12/AZ91D优先指定源，允许FreePublic/Manual降级 |
| F06 | 所有Provider校验后发布，未校验数据不得进入业务层 |
| F07/F08/F09/F10 | 原始、每日加工、多级聚合、预警持久化 |
| F11/F12/F13 | 文件轮转、跨文件查询、动态配置 |
| F14 | 完整源码和Windows本地部署手册 |

## 3. P0任务总览与退出门禁

| 开发日 | 主题 | 退出门禁 |
|---|---|---|
| Day 1 | PBOC真实获取与raw存取 | EUR/CNY、USD/CNY均从PBOC真实获取并生成可追溯raw JSON |
| Day 2 | PBOC校验、daily文件、历史和聚合闭环 | AT-SRC-002 PASS；双币从raw/lifecycle JSON到PARSED/PENDING、VALIDATED、PUBLISHED+VERIFIED类、daily/aggregate CSV和重启读取闭环 |
| Day 3 | 六类Provider与材料三层接入 | 四个来源意图×材料序列各有一条non-synthetic合法路线；来源不可冒充 |
| Day 4 | 全Provider治理与五级计算 | Manual/FreePublic也走统一门禁；黄金数据与来源治理测试通过 |
| Day 5 | 轮转、跨年、动态配置、回填、预警 | 后端可完成H05-H09核心闭环 |
| Day 6 | Agent与LLM | Agent只调用受控工具；LLM失败时模板报告可用 |
| Day 7 | Vue核心页面 | 浏览器完成仪表盘、历史、质量、导入闭环 |
| Day 8 | Vue联动与Web验收 | 配置、预警、Agent可演示；P0 Web链路通过；功能冻结 |
| Day 9 | Electron桌面交付 | 无开发服务器的Windows便携目录可双击启动和安全退出 |
| Day 10 | 正式验收与发布 | 干净Windows、时间、跨年、断网、无数据库、文档证据全部完成 |

---

## 4. Day 1：PBOC真实获取与raw存取

### D1-T01 项目方实施补充基线与PBOC优先级冻结

- **优先级/状态：** P0 / `DONE`（本轮仅文档工作）。
- **任务目标：** 独立保存项目方实施补充说明，并同步总计划、追踪、验收、任务、风险和决策。
- **对应需求：** SUP-01至SUP-08、F02-F07。
- **输入：** 原需求书、项目方补充原文、00-06基线文档。
- **创建或修改文件：** `docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`及01-06相关Markdown。
- **输出：** PBOC第一优先、六类Provider、材料三层降级、Manual门禁和来源真实性的统一基线。
- **依赖任务：** 无。
- **具体测试：** 跨文档检索旧“四类Provider”“商业授权全局阻塞”“PBOC在Day3”口径；校验任务和AT引用。
- **Definition of Done：** 补充原文独立保存；所有文档采用同一新口径；未创建业务代码。
- **失败回退：** 恢复至修订前文档并保留补充原文，不在口径冲突时开始代码。
- **是否阻塞后续：** 是；完成后D1-T02成为下一任务。

### D1-T02 PBOC EUR/CNY、USD/CNY数据契约与连通性验证

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=CODE_REVIEW_APPROVED`（字段事实与逐币种 Windows 原生重放证据已通过技术负责人 Code Review；两币环境限定的连接结论均为`EXTERNAL_ACCESS_BLOCKED`）。
- **任务目标：** 调查并归档PBOC合法公开入口、双币发布字段、报价方向、业务日期、单位和连接结果，为后续通用文件层提供可追溯契约。
- **对应需求：** SUP-01、SUP-02、F02、F03、EXT-01。
- **输入：** PBOC公开页面/接口说明、双币Series定义、访问条款。
- **创建或修改文件：** 来源能力记录、Series配置草案、连通性验证和字段样例；不提交秘密。
- **输出：** EUR/CNY、USD/CNY各一行：`fieldContractResult=CONFIRMED`及环境限定的`connectionResult=CONFIRMED / EXTERNAL_ACCESS_BLOCKED`；同时保存实际URL/引用、访问频率、精确命令、客户端版本、代理模式（脱敏）、时间戳、HTTP状态/退出码、重试次数、错误全文/结构摘要、字段摘录与SHA-256。
- **依赖任务：** D1-T01。
- **具体测试：** 在当前目标Windows原生环境用PowerShell与curl重放列表/详情访问；双币可共享同一详情响应但结果必须逐币种记录。成功时保存完整响应字节、HTTP状态/contentType/hash并核对标题/正文/落款日期、值、单位、发布时间；失败时保存上述全部可复现诊断。Java客户端明确记`NOT_RUN（属于D1-T04）`。
- **Definition of Done：** 两个币种逐行结果齐全；每次尝试可由另一人复放；字段摘录含标题、文章来源、正文和落款且SHA-256匹配；成功与失败都不含秘密。只有完成Code Review后才能改回`DONE`。失败证据不能构成真实PBOC采集、Day1/Day2退出或AT-SRC-002=PASS。
- **失败回退：** 不改用非PBOC冒充；保留官方页面手动诊断证据并登记EXT-01；AT-SRC-002保持NOT_RUN、BLOCKED或FAIL，不写PASS。
- **是否阻塞后续：** 阻塞D1-T03，直到双币`connectionResult`和本次编码前基线齐备；即使调查以失败证据结束，仍硬阻塞Day1真实退出、Day2/AT-SRC-002通过，直至真实双币链路恢复。

### D1-T03 最小Spring Boot、data/raw与BigDecimal文件基础

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_AT_FILE_000_PASS_SOL_FINAL_REVIEW_APPROVED`。Sol 技术负责人最终二次 Review=`PASS`；`AT-FILE-000=PASS`。本任务完成仅代表文件存储与基础设施验收通过，不代表AT-SRC-002、真实PBOC数据闭环或Day 1/Day 2总门禁通过。
- **任务目标：** 严格按总计划8.3至9节建立Java 17单Spring Boot模块化单体、唯一dataRoot、RawReceiptV1、含CandidateV1的LifecycleTimelineV1、活动配置及不可变history、manifest、无数据库文件基础和BigDecimal字符串规范；只实现daily/aggregate固定codec与计算上下文契约，不实现Provider或业务计算。
- **对应需求：** H02-H04、SUP-02、F07、C01、C02、C06-C10、C15、C16、C27-C34。
- **输入：** Review通过且`DONE`的D1-T02；总计划8.3至9节；AT-FILE-000。
- **创建或修改文件：** `backend/pom.xml`、Maven Wrapper、单一Spring Boot工程、模块包骨架、storage/path/schema/codec基础、precision config schema、枚举、测试和依赖版本记录。Spring Boot/Jackson/JUnit/CSV库的Java17兼容版本必须在pom与任务证据中精确锁定。
- **输出：** 可编译/启动后端；唯一`supplymind.data-root`；含完整来源/动态替换、`baseCurrency/currency（语义等于quoteCurrency）/unit`、calculationVersion/scale/rounding/calendar字段及PBOC默认值的唯一活动`data/config/monitor-series.json`，以及每个已生效configVersion逐字节相同、不可覆盖的`data/config/history/<configVersion>.json`；冻结目录树与固定raw文件名；含configVersion的RawReceiptV1与相邻manifest；按runId保存全部版本及CandidateV1的LifecycleTimelineV1；QuarantineProjectionV1与含单调markerRevision、targets状态机及canonical/tmp/bak专用自恢复的DirtyMarkerV1；确定性JSON/CSV及规范行排序、validationStatus/validationVersion、qualityStatus派生、configVersions、完整计算上下文、可唯一定位daily行且在schema v1固定指向PUBLISHED recordVersion=4的inputRefs、sourceFingerprint和BigDecimal精确字符串codec；`FILE-SCHEMA-V1.md`、追加式`CALCULATION-RULES.md`及覆盖RawReceipt、Timeline/Candidate、活动config/history、quarantine、manifest/dirty、daily/aggregate的合法/非法黄金文件。运行JSON不得自创quoteCurrency字段。所有样例必须标为test/fixture，不得冒充真实PBOC采集。
- **依赖任务：** D1-T02=`DONE`且Code Review通过；C27-C34已冻结。
- **具体测试：** 执行AT-FILE-000：编译/启动；临时dataRoot/中文路径/只读目录/fail-fast；路径穿越拒绝；同一标记为contract fixture的完整PBOC形状响应生成共享acquisitionId但两个独立run/raw/timeline；RawReceipt required/null与完整payload hash；4×5状态组合、合法迁移边、各状态条件必填及多recordVersion保留；CandidateV1的null/必填/同run不可变与重启恢复；双币baseCurrency/currency/unit映射；活动config/history一致性、同版本同hash幂等/异hash冲突、configVersion解析、DirtyMarkerV1状态矩阵、markerRevision及marker自身三个原子替换崩溃窗口、同revision异字节/跳号/回退/字段漂移fail-closed、配置targets[]两项所覆盖四个物理文件的各崩溃窗口；daily/aggregate固定表头/规范行排序、validationStatus/validationVersion、qualityStatus派生、configVersions、计算上下文、可唯一定位daily行且在schema v1固定指向PUBLISHED recordVersion=4的inputRefs和sourceFingerprint黄金向量；打乱输入后CSV/hash不变；raw同hash幂等、异hash冲突、绝不覆盖；Windows文件占用、ATOMIC_MOVE不可用、写入中断、临时/备份文件及业务文件与manifest恢复；manifest双哈希；`999999999999.123456789`、`0.000000001`、`100.0`、非终止除法、toPlainString及重启往返；依赖树审计。
- **Definition of Done：** 依赖树不含MyBatis/JPA/JDBC/R2DBC及MySQL、Redis、SQLite、H2或任何数据库驱动/服务/文件，也不依赖Docker；raw不含processingStage/validationStatus且原词法值不变；timeline保存全部版本与CandidateV1并强制合法迁移/条件必填；活动配置、不可变history、schema/计算规则数据字典和全部黄金文件齐全；RawReceipt.configVersion可长期解析，CSV逐行保存可复算上下文；目录、配置、格式、日期路由、manifest、inputRefs、sourceFingerprint、DirtyMarkerV1自恢复和原子写严格符合总计划；失败不留下正式半文件或歧义marker；不得宣称PBOC真实采集或Day1/Day2已通过。
- **失败回退：** 回退最小骨架，不用数据库、隐藏目录或虚构PBOC样例规避文件问题。
- **是否阻塞后续：** 是。

### D1-T04 OfficialWebDataProvider真实PBOC获取与raw落盘

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_SOL_AND_OPENCODE_APPROVED_20260809`。Sol技术负责人和OpenCode独立Review均已确认通过；本任务完成不代表AT-SRC-002、Day 1或Day 2总门禁通过。
- **任务目标：** 实现PBOC OfficialWeb逻辑，从真实入口获取EUR/CNY、USD/CNY并先保存不可变raw。
- **对应需求：** SUP-01、SUP-02、F02、F03、F07。
- **输入：** D1-T02契约、D1-T03文件层、双币配置。
- **创建或修改文件：** PBOC适配、HTTP/页面解析、raw元数据、脱敏日志和集成测试。
- **输出：** 带configVersion、providerType=official_web、actualSourceName=`中国人民银行官网（授权中国外汇交易中心公布）`、业务/采集时间、来源引用、完整payload bytes/hash的两个item级不可变raw JSON，以及各自独立初始`RECEIVED+PENDING` LifecycleRecord；一次响应共享acquisitionId但runId/rawRef独立。
- **依赖任务：** D1-T02、D1-T03。
- **具体测试：** 双币正常获取、字段缺失、网络超时、重复响应、页面结构变化和日志脱敏。
- **Definition of Done：** 两个币种真实raw均落盘；解析失败不造数、不覆盖旧raw；来源可追溯。
- **失败回退：** 停止发布并保存错误响应/结构摘要，不硬编码假值或使用synthetic冒充。
- **是否阻塞后续：** 是。

### D1-T05 PBOC双币raw闭环冒烟门禁

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_DAY1_GATE_COMPLETE_20260809`。真实双币 raw 闭环冒烟门禁 Review 通过并随 Day 1 收口（BLOCKER 修复、重跑与 Review 历史见 docs/05 与 D1-T05 evidence）；Day 1=`COMPLETE`、Git 基线 `day1-complete`。
- **任务目标：** 在Day1结束前证明两个币种真实获取、raw可检查、重复执行幂等和失败可诊断。
- **对应需求：** SUP-01、SUP-02、F03、F07。
- **输入：** D1-T03、D1-T04和独立data目录。
- **创建或修改文件：** 集成冒烟测试、证据目录和进度台账。
- **输出：** 双币raw、SHA-256、任务日志、幂等结果和Day1退出报告。
- **依赖任务：** D1-T03、D1-T04。
- **具体测试：** 清空测试目录后真实采集；重复触发；重启读取raw；断网重试不造数。
- **Definition of Done：** EUR/CNY、USD/CNY均有真实PBOC raw证据；任一缺失则Day1不退出。
- **失败回退：** 保留失败证据并继续修复D1-T02/T04，不进入Day2计算链。
- **是否阻塞后续：** 是。

---

## 5. Day 2：PBOC校验、daily文件、历史与聚合闭环

### D2-T01 PBOC标准化与基础校验

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_SOL_FINAL_APPROVED_20260809`。Sol 最终 Review 通过；Finding 1-4 均`CLOSED`、D2-T01 DoD=`PASS`、DEC-050 已生效。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。
- **任务目标：** 将双币raw转换为统一候选，并校验来源、字段、日期、单位、数值范围、重复和时效性。
- **对应需求：** SUP-02、F03、F06、H02。
- **输入：** D1-T05真实raw、Series定义和校验规则。
- **创建或修改文件：** standardization、validation基础、状态/原因码和测试。
- **输出：** 先追加含不可变CandidateV1的`PARSED+PENDING`快照，再追加`ProcessingStage=VALIDATED`与`ValidationStatus=VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT`的Lifecycle快照、原因码和校验报告；解析未通过由RECEIVED+PENDING转为RECEIVED+REJECTED且candidate保持null。
- **依赖任务：** D1-T05。
- **具体测试：** 正常双币、缺字段、错误单位、未来日期、重复、异常范围和过期记录；验证不得从RECEIVED跳到VALIDATED、不得从PARSED跳到PUBLISHED，CandidateV1同run不可变。
- **Definition of Done：** 同输入结果确定；PARSED及以后每条快照持久化CandidateV1，VALIDATED记录有ProcessingStage、ValidationStatus、规则版本和按状态要求的原因码/时间；无效数据不覆盖合法值。
- **失败回退：** 整批保持PENDING或隔离，不允许临时绕过。
- **是否阻塞后续：** 是。

### D2-T02 PBOC VERIFIED发布门禁

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_SOL_FINAL_APPROVED_20260809`。Sol 最终固定快照 Review 通过（审查 commit=`12766c9`）；publishRef MAJOR=`CLOSED`、stale CHANGE_REQUEST=`CLOSED`、DEC-051 与实现一致、D2-T02 DoD=`PASS`、Evidence=`VALID`。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。
- **任务目标：** 建立PBOC最小发布边界，确保只有`ProcessingStage=PUBLISHED`且ValidationStatus为两种VERIFIED状态的记录可被加工与查询。
- **对应需求：** SUP-02、F06、H01、H02。
- **输入：** D2-T01校验结果、raw引用。
- **创建或修改文件：** publish边界、Lifecycle timeline原子追加、查询过滤和门禁测试；不创建published目录或隐藏“已发布仓储”。
- **输出：** 双币timeline中的`PUBLISHED+VERIFIED类`新快照；非发布资格组合保持原合法状态，只有终态失败按契约生成quarantine证据投影。
- **依赖任务：** D2-T01。
- **具体测试：** 从公开API尝试读取PENDING、REJECTED、CONFLICT；验证最后合法值及stale信息。
- **Definition of Done：** 所有非PUBLISHED或非VERIFIED类组合在业务入口不可见；发布记录仍能追溯到PBOC raw和LifecycleRecord。
- **失败回退：** 关闭发布动作并恢复最后合法文件，不降低校验规则。
- **是否阻塞后续：** 是。

### D2-T03 PBOC每日加工与CSV持久化

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_EXT_GATE_PASS_DEC053_DEC054_20260809`。Sol Implementation Review=`PASS`（固定 commit=`607e859`）；第二方固定快照 Review=`PASS`；MAJOR 1/2=`CLOSED`、DEC-052=`PASS`；EXT-03=`ACCEPTED_VERSIONED_DEFAULT`（DEC-053）、EXT-06=`ACCEPTED_VERSIONED_DEFAULT`（DEC-054）、EXT Gate=`PASS`。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。
- **任务目标：** 以BigDecimal从已发布双币记录计算每日加工值，并按月写daily CSV。
- **对应需求：** SUP-02、F03、F08、H01、H02。
- **输入：** D2-T02已发布记录、不可变配置history、追加式CALCULATION-RULES、每日均值规则和业务日历；EXT-03/EXT-06确认记录或书面接受的版本化P0默认。
- **创建或修改文件：** daily processing、CSV writer、计算规则和测试。
- **输出：** 严格符合总计划8.4.5 Daily CSV固定表头的daily文件；包含来源/状态/业务日期、configVersions、calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、精确sum/validCount/avg、完整inputRefs和完整率字段。inputRefs覆盖全部validCount对应的runId/rawRef/recordVersion，禁止退化为单一rawRef。
- **依赖任务：** D2-T02。
- **具体测试：** 单值日、多观测日、缺失日、重复、非法记录、循环小数、12位持久化/9位展示、配置版本切换、计算规则切换和重算幂等。
- **Definition of Done：** EUR/CNY、USD/CNY均生成daily；sum不舍入、avg只按calculationScale/roundingMode舍入、displayScale不回写；缺失不补0；configVersions均能解析到history；重启可读。EXT-03/EXT-06未关闭或未书面接受版本化默认时，本任务不得标DONE或宣称正式业务口径通过。
- **失败回退：** 保留raw/已发布值和旧daily，按恢复标记重算。
- **是否阻塞后续：** 是。

### D2-T04 PBOC历史读取与多周期聚合最小闭环

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=REVIEW_PASS_IMPLEMENTATION_FINDINGS_CLOSED_DUAL_REVIEW_20260810`。Implementation commit=`1ac8233`（首轮 Implementation Review=`CHANGES_REQUESTED`，Finding A/B/C）；Findings closure commit=`1178307`（Finding A 四级跨Clock、Finding B 四级Read-only Restart、Finding C 多configVersion 全部`CLOSED`）；Sol Final Delta Review=`PASS`、Second-party Final Delta Review=`PASS`（BLOCKER=无、MAJOR=无）；DEC-055=`PASS`；四级aggregate direct-from-daily、BigDecimal precision、四级persistence、Manifest/Atomic、四级cross-clock determinism、四级read-only restart、multi-configVersion traceability 均`PASS`；真实PBOC aggregate evidence=`VALID`。本任务完成不代表 AT-SRC-002、Day 2 总门禁通过。
- **任务目标：** 从daily文件读取历史并生成月、季、半年、年持久化聚合，形成最小完整闭环。
- **对应需求：** SUP-02、F03、F09、H01、H02。
- **输入：** D2-T03 daily、黄金历史样本、自然周期与精度规则。
- **创建或修改文件：** history最小查询、aggregation、四级writer和复算测试。
- **输出：** 双币历史序列及`processed/aggregate/<itemId>/{month,quarter,halfyear,year}/YYYY.csv`；每个文件逐字符合总计划8.4.5 Aggregate CSV固定表头、来源/计算上下文分组、configVersions和完整daily inputRefs规则。
- **依赖任务：** D2-T03。
- **具体测试：** 跨月黄金样本、重启读取、直接从daily avg重算季度/半年/年度、不能读取月均或displayScale结果、来源/计算版本切换分行、缺失日不计权重。
- **Definition of Done：** 所有层级可从daily直接重建并持久化；sum/min/max不发生未声明舍入，avg按行内calculationScale/roundingMode，configVersions/规则版本可追溯，数值与独立BigDecimal复算一致。
- **失败回退：** 只清理由本次dirty marker/transactionId明确归属且已按总计划8.5完成回退的聚合tmp/bak；来源不明残留必须保留报告。随后从daily重建，不修改raw。
- **是否阻塞后续：** 是。

### D2-T05 PBOC调度、幂等、重启端到端硬门

- **优先级/状态：** P0-最高 / `TaskExecutionStatus=DONE`；`statusReason=DUAL_REVIEW_PASS_DEC056_AT_SRC_002_PASS_20260810`。实现链：initial implementation=`24d24b6`（首轮 Review=`CHANGES_REQUESTED`：raw-first FAIL / idempotency ambiguity / runner evidence 不足）→ Fact Adjudication 落 `DEC-056` → Findings Fix=`2b7d2f4`（Review=`CHANGES_REQUESTED`：强制追溯/decodeHtml 契约/evidence 自动化/状态唯一）→ traceability/evidence fix=`a482087`（Review=`CHANGES_REQUESTED`：仅剩 summary UTF-8 encoding MAJOR）→ UTF-8 final fix=`79680ec`（Sol UTF-8 Evidence Finding Review=`PASS`、Second-party UTF-8 Delta Review=`PASS`）；AT-SRC-002 正式=`PASS`（真实 gated 1/1/0/0/0，businessDate=2026-08-10 USD=6.7884/EUR=7.8171，runner XML=`6e9d7c50…2e1b8`）；DEC-056 implementation=`PASS`；BLOCKER/MAJOR=无；Day 2 退出条件满足。
- **任务目标：** 完成真实定时/立即采集、raw先写、校验、发布、daily、聚合和重启读取的双币端到端验收。
- **对应需求：** SUP-01、SUP-02、F02、F03、H01-H03。
- **输入：** D1-T04、D2-T01至D2-T04、AT-SRC-002。
- **创建或修改文件：** scheduler、幂等批次、端到端测试、证据和台账。
- **输出：** AT-SRC-002完整证据、双币任务摘要、文件树和Day2退出报告。
- **依赖任务：** D2-T01、D2-T02、D2-T03、D2-T04。
- **具体测试：** 执行AT-SRC-002全部步骤；断网、重复触发、重启离线查询和数值复算。
- **Definition of Done：** AT-SRC-002=PASS；任一币种缺链、未持久化或无法重启读取均不得进入Day3。
- **失败回退：** 冻结材料开发，回到失败的PBOC任务修复并重跑全链。
- **是否阻塞后续：** 是，硬阻塞所有材料Provider任务。

---

## 6. Day 3：六类Provider与材料三层合法接入

### D3-T01 六类DataProvider端口、注册表与来源模型

- **优先级/状态：** P0 / `TaskExecutionStatus=DONE`；`statusReason=R1_REVIEW_PASS_20260810`。implementation commit=`86c8e3f`；Review Level=R1；第二方 R1 Review=`PASS`（BLOCKER=无、MAJOR=无、BUSINESS_DECISION_REQUIRED=无、R2_REQUIRED=NO）；技术 DoD=`PASS`；支持状态收口=`YES`。
- **任务目标：** 定义OfficialWeb、AuthorizedApi、FreePublic、Manual、LocalImport、SyntheticDemo六类逻辑入口和统一RawRecord。
- **对应需求：** SUP-03至SUP-08、F02、F04-F07、H07、H08。
- **输入：** D2-T05通过证据、数据字典和来源能力要求。
- **创建或修改文件：** Provider端口、注册表、能力模型、来源元数据和契约测试。
- **输出：** 六类providerType、current/history能力、actualSourceName、accessMethod和routeDecision边界。
- **依赖任务：** D2-T05。
- **具体测试：** 六类测试替身输出统一记录；不支持标的显式拒绝；来源字段不可覆盖。
- **Definition of Done：** 上层无厂商DTO/URL逻辑；实现类可复用但六类身份不丢失；Provider不直写聚合或调用LLM。
- **失败回退：** 保留端口和来源模型，移除不稳定适配器，不退回四类方案。
- **是否阻塞后续：** 是。

### D3-T02 材料三层路由与AuthorizedApi能力

- **优先级/状态：** P0 / `TaskExecutionStatus=DONE`；`statusReason=R1_REVIEW_PASS_20260810`。implementation commit=`ee7cbc7`；Review Level=R1+；第二方 R1+ Review=`PASS`（BLOCKER=无、MAJOR=无、BUSINESS_DECISION_REQUIRED=无、R2_REQUIRED=NO）；技术 DoD=`PASS`；支持状态收口=`YES`。任务级 PASS，不代表 Day 3 阶段 Gate 已通过（阶段 Acceptance 待 Day 3 收尾统一执行）。
- **任务目标：** 对SMM/Asian Metal逐项判断合法自动能力，并为ADC12/AZ91D形成受控三层路由。
- **对应需求：** SUP-03、SUP-08、F04、F05、EXT-04。
- **输入：** 公开条款/授权、材料规格、Provider能力矩阵。
- **创建或修改文件：** routeDecision配置、AuthorizedApi适配边界、能力探测和合规测试。
- **输出：** 每个标的的activeProvider、fallbackReason、生效时间和条件验收状态。
- **依赖任务：** D3-T01。
- **具体测试：** 合法API、会员限制、无公开接口、反爬提示、凭证缺失和禁止静默换源。
- **Definition of Done：** 能合法自动则配置；不能则明确转FreePublic或Manual，不绕过限制、不阻塞整体P0。
- **失败回退：** 禁用对应自动适配器并记录N/A能力，不使用共享Cookie或模拟值。
- **是否阻塞后续：** 是，阻塞该材料路线选择，但不回阻PBOC。

### D3-T03 FreePublicDataProvider与真实来源追踪

- **优先级/状态：** P0 / `TaskExecutionStatus=REVIEW_PENDING`；`statusReason=D3T03_NO_APPROVED_SOURCE_SURVEY_20260810`。真实公开来源调查完成（仅正常公开 HTTPS 访问，2026-08-10T18:50）：SMM（会员制价格区）、Asian Metal（公开 HTTPS 握手被拒）、CCMN（具体报价会员制）、100ppi（仅壳页）均 NOT_APPROVED → 结论 `NO_APPROVED_SOURCE`（冻结 DoD 路径 B：URL/条款调查 + 转 Manual 路由仍满足 DoD，不编造适配器）；未实现 FreePublicDataProvider（NOT_IMPLEMENTED_WITH_REASON）；调查模型 `FreePublicSourceSurvey`/`FreePublicSurveyReport`（结论一致性 fail-closed）、三层路由 FREE_PUBLIC 不可用→MANUAL 显式降级、配置引用不存在 Provider fail-closed（resolver 急切校验）；未访问受限内容、未伪造数据、未修改 Validation/Publish Gate；证据=`docs/evidence/D3-T03/`；等待第二方 R1+ Review；不得在 Review 前自行 DONE。
- **任务目标：** 接入项目方认可、无需绕限制的同类免费公开材料信源，并保留真实站名和引用。
- **对应需求：** SUP-03、SUP-07、F04-F07、EXT-10。
- **输入：** 候选免费源URL/条款、ADC12/AZ91D规格映射。
- **创建或修改文件：** FreePublic适配、解析映射、来源元数据、字段漂移测试。
- **输出：** providerType=free_public、actualSourceName、URL、许可引用和raw。
- **依赖任务：** D3-T01、D3-T02。
- **具体测试：** 正常解析、页面变化、单位不匹配、过期、网络失败和前端/Agent来源一致性。
- **Definition of Done：** 不冒充SMM/Asian；解析失败不发布。存在获认可免费源时完成适配并验证；不存在获认可来源时以`NO_APPROVED_SOURCE`能力结论、URL/条款调查和转Manual路由结束本任务，仍可标`DONE`，不得编造适配器。
- **失败回退：** 停用该免费源并保留失败证据，使用D3-T04，不抓取受限内容。
- **是否阻塞后续：** D3-T03的调查产物是D3-T06硬依赖；FreePublic能力缺失本身不阻塞。若已用`NO_APPROVED_SOURCE`、URL/条款调查证据及转Manual路由满足本任务DoD，D3-T03可标`DONE`并允许D3-T06继续。

### D3-T04 ManualDataProvider与数据治理门禁

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 提供手工填写入口，并强制记录来源字段、不可变raw + 独立初始`RECEIVED+PENDING` LifecycleRecord、双维校验、PUBLISHED+VERIFIED类发布、加工和版本审计。
- **对应需求：** SUP-03、SUP-05、SUP-06、SUP-07、F04-F07、H08、EXT-11。
- **输入：** Manual字段schema、材料配置、校验规则。
- **创建或修改文件：** Manual适配、受控提交API、raw审计、状态机和测试。
- **输出：** Manual请求`businessDate/value/unit/currency`按总计划7.4唯一映射为RawReceiptV1的`sourceBusinessDateRaw/sourceBusinessDate/rawValue/rawUnit/rawCurrency`；服务端保存actualSourceName、itemId、sourceReference，生成inputAt/receivedAt/updatedAt，固定accessMethod=manual并从认证上下文取得operatorRef；可信字段仅进入PARSED后的CandidateV1；独立Lifecycle timeline保存processingStage、validationStatus及全部版本。
- **依赖任务：** D3-T01、D2-T01、D2-T02。
- **具体测试：** 合法、缺来源、错误单位、未来日期、重复、修订、RECEIVED/PARSED+PENDING查询拒绝、PUBLISHED+VERIFIED后加工。
- **Definition of Done：** 手工提交不直达面板；修订不覆盖原raw；实际来源和手工方式在全链可见。
- **失败回退：** 禁用提交入口并保留raw/错误报告，不允许管理员直接写processed。
- **是否阻塞后续：** 是；它是材料最终保底路线。

### D3-T05 LocalImport与SyntheticDemo隔离

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 支持合法CSV/XLSX导入与可复现演示数据，并严格区分真实导入和synthetic。
- **对应需求：** F04-F07、H08、C类模式边界。
- **输入：** 导入模板、数据字典、黄金场景和固定种子。
- **创建或修改文件：** LocalImport/Synthetic适配、预览、去重、模式隔离和测试。
- **输出：** 导入raw、逐行错误、全链synthetic标签和演示场景。
- **依赖任务：** D3-T01、D1-T03。
- **具体测试：** 正常/缺列/错误单位/重复/中文路径、固定种子、正式模式拒绝synthetic。
- **Definition of Done：** LocalImport保存实际来源与输入方式；synthetic永不能转real或替代PBOC/材料真实路线。
- **失败回退：** 不发布失败批次；禁用Synthetic并保留静态fixture。
- **是否阻塞后续：** 是，阻塞完整导入与演示能力。

### D3-T06 ADC12/AZ91D合规接入闭环

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 对SMM意图×ADC12/AZ91D、Asian Metal意图×ADC12/AZ91D四个P0监测序列分别选择并运行合法指定源、FreePublic或Manual中的一条非synthetic链路；itemId稳定标识来源意图×材料，actualSourceName始终记录实际来源。
- **对应需求：** SUP-03、SUP-06至SUP-08、F04-F07、H08。
- **输入：** D3-T02至D3-T05、材料规格和AT-SRC-001/005至008。
- **创建或修改文件：** 材料Provider配置、routeDecision、集成测试和证据。
- **输出：** 四个来源意图×材料序列各自的raw、实际来源、校验状态、当前值/历史输入状态和P0路线判定。
- **依赖任务：** D3-T02、D3-T03、D3-T04、D3-T05。
- **具体测试：** 执行AT-SRC-001/005/008；按选定路线执行AT-SRC-003、004、006或007。
- **Definition of Done：** 四个P0监测序列各有一条认可非synthetic路径；商业自动N/A不全局阻塞；来源不冒充。
- **失败回退：** 逐级降到Manual；若三条均不可执行则只阻塞材料P0并明确报告。
- **是否阻塞后续：** 是，阻塞Day4材料通用链。

---

## 7. Day 4：全Provider治理与五级计算

### D4-T01 全Provider标准化与校验规则

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 将Day2 PBOC基础校验推广到六类Provider，覆盖来源、日期、单位、规格、范围、重复、时效和冲突。
- **对应需求：** SUP-06、SUP-07、F06、F07、H02。
- **输入：** D3-T06各类raw、validation-rules和Series定义。
- **创建或修改文件：** 通用standardization/validation、规则版本和测试。
- **输出：** ProcessingStage+ValidationStatus组合、原因码、实际来源血缘和隔离报告。
- **依赖任务：** D3-T06。
- **具体测试：** GD-03、GD-07；Manual缺字段、免费源伪标签、规格冲突、正常记录。
- **Definition of Done：** 所有Provider相同门禁；无效值不覆盖旧值；Manual/FreePublic无旁路。
- **失败回退：** 批次保持PENDING或隔离，不降低规则。
- **是否阻塞后续：** 是。

### D4-T02 全Provider统一发布门禁

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 强制加工、查询、预警和Agent只能访问PUBLISHED+两种VERIFIED状态。
- **对应需求：** SUP-06、F06、H01、H02。
- **输入：** D4-T01结果、真实/演示模式和来源策略。
- **创建或修改文件：** 通用publish边界、查询过滤、模式/来源安全测试。
- **输出：** PUBLISHED+VERIFIED类正式记录；非法、冲突和伪来源记录隔离。
- **依赖任务：** D4-T01、D2-T02。
- **具体测试：** 六类Provider各状态访问；synthetic正式模式拒绝；Manual PENDING不可见。
- **Definition of Done：** 所有业务入口采用同一发布边界；最后合法值带日期、来源和stale状态。
- **失败回退：** 关闭受影响来源发布并恢复最后合法文件。
- **是否阻塞后续：** 是。

### D4-T03 全Provider每日加工与持久化

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 将D2-T03推广到材料路线，从已验证同来源同规格样本计算daily并按月写文件。
- **对应需求：** H01、H02、F08、SUP-06。
- **输入：** D4-T02记录、不可变配置history、追加式计算规则和业务日历。
- **创建或修改文件：** 通用daily processing、月度writer和来源维度测试。
- **输出：** 逐字符合总计划8.4.5完整Daily CSV固定表头；按来源/规格/计算上下文分组，保存configVersions、calculationVersion/scale/displayScale/roundingMode/calendarVersion、精确sum/validCount/avg及完整inputRefs；inputRefs覆盖全部validCount对应的runId/rawRef/recordVersion。
- **依赖任务：** D4-T02、D2-T03。
- **具体测试：** PBOC、FreePublic/Manual、单/多样本、缺失、重复、不同规格/来源/计算上下文禁止混算、12位持久化/9位展示和从history重算。
- **Definition of Done：** 结果与黄金期望一致；缺失不补0；来源与计算血缘不丢；sum不舍入、avg按calculationScale固定小数位持久化，displayScale结果不回写。
- **失败回退：** 保留raw/旧daily，从受影响日期重算。
- **是否阻塞后续：** 是。

### D4-T04 月/季/半年/年聚合与持久化

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 从有效daily重算自然月、季度、半年和年度指标，并分别写aggregate CSV。
- **对应需求：** H01、H02、F09。
- **输入：** D4-T03 daily、自然周期规则和精度配置。
- **创建或修改文件：** aggregation、四级writer、来源指纹和重建测试。
- **输出：** 位于`processed/aggregate/<itemId>/{month,quarter,halfyear,year}/YYYY.csv`并逐字符合总计划8.4.5完整Aggregate CSV固定表头；不得在本任务自创`period`等字段。按来源身份、validationVersion和计算上下文分组，保存configVersions、sourceFingerprint及可唯一定位、覆盖全部参与daily行的inputRefs；同period来源、校验版本或计算规则切换时分行。
- **依赖任务：** D4-T03、D2-T04。
- **具体测试：** 跨月/季/半年/年直接从daily复算；不得读取已舍入月均或展示值；不同来源/规格/计算上下文不误合并；configVersions可解析到不可变history。
- **Definition of Done：** 全部层级持久化且可从daily直接重建，无中间展示舍入，固定表头及计算/来源血缘完整。
- **失败回退：** 只清理由本次dirty marker/transactionId明确归属且已按总计划8.5完成回退的聚合tmp/bak；来源不明残留必须保留报告。随后从daily重建，不修改raw。
- **是否阻塞后续：** 是。

### D4-T05 黄金数据、来源治理与发布集成门禁

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 在Day5前证明数值精度、Manual门禁、来源真实性和五级文件完整。
- **对应需求：** H01、H02、F06-F09、SUP-05至SUP-08。
- **输入：** GD-01至GD-07、D4-T01至D4-T04。
- **创建或修改文件：** 集成测试、来源对账、证据目录和进度台账。
- **输出：** 数值期望对比、状态隔离、AT-SRC-006/007/008和文件证据。
- **依赖任务：** D4-T01、D4-T02、D4-T03、D4-T04。
- **具体测试：** 执行精度、复算、发布门禁及来源治理用例；跨出口对账actualSourceName。
- **Definition of Done：** 数值完全匹配；五类文件可检查；手工/免费源不旁路、不冒充。
- **失败回退：** 回退受影响实现并重跑全链，不改黄金期望掩盖错误。
- **是否阻塞后续：** 是。

---

## 8. Day 5：轮转、跨年、动态配置、回填与预警

### D5-T01 文件轮转与系统时间变化检测

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 在跨期、Windows休眠恢复、时间前跳和回拨时创建正确分区并保持幂等。
- **对应需求：** H05、F11。
- **输入：** Clock抽象、业务日期、time-state、文件契约。
- **创建或修改文件：** rotation/time模块、可控Clock测试、恢复状态文件。
- **输出：** 新周期文件预创建、前跳/回拨检测、日志和状态记录。
- **依赖任务：** D2-T03、D4-T04。
- **具体测试：** 可控Clock覆盖月末、季末、6月末、年末、闰日和回拨；物理改系统时间留到D10-T02。
- **Definition of Done：** 新周期自动建文件；旧文件不覆盖；回拨不重复发布；未来无官方数据时不造数。
- **失败回退：** 停止调度写入，恢复time-state备份和正式文件，重新执行幂等检查。
- **是否阻塞后续：** 是。

### D5-T02 跨文件与跨年度历史查询

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 根据日期范围定位多个月度文件，完成读取、拼接、业务键去重、排序、过滤和缺失说明。
- **对应需求：** H06、F12、H01。
- **输入：** 多月daily/aggregate文件、查询条件。
- **创建或修改文件：** history-query模块、跨年fixture和集成测试。
- **输出：** 连续历史序列、缺失月份列表、来源和数据截至时间。
- **依赖任务：** D4-T04、D5-T01。
- **具体测试：** 2025-12到2026-01、多文件重复、缺文件、损坏文件和反向日期输入。
- **Definition of Done：** 结果无重无漏且排序稳定；缺失不插值；损坏文件不导致静默错误。
- **失败回退：** 返回部分结果和明确错误，不重写历史文件；修复或从raw/daily重建。
- **是否阻塞后续：** 是。

### D5-T03 动态监测标的与联动配置

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 运行时新增、停用和替换series，联动Provider、Scheduler、预警、成本方案和面板配置。
- **对应需求：** H07、H09、F13。
- **输入：** monitor-series配置、Provider能力、依赖关系。
- **创建或修改文件：** configuration模块、配置事件、依赖校验和API测试。
- **输出：** 稳定itemId、enabled状态、sourceIntent、Provider/routeDecision/fallbackReason/routeEffectiveAt、supersedesItemId、configVersion、计算/日历上下文和依赖状态；写回唯一活动monitor-series v1，并为每个已生效版本CREATE_NEW写逐字节相同的config/history快照及manifest。history不是第二活动配置，禁止覆盖或删除。
- **依赖任务：** D3-T01、D5-T02。
- **具体测试：** 停用欧元、新增GBP、把SMM/Asian Metal两个来源意图下的AZ91D分别替换为两个独立MAT-REPL-01 item且两条ADC12保持、重启后配置保持；覆盖history/active两个targets所含四个物理文件的事务各崩溃窗口和旧configVersion追溯。
- **Definition of Done：** 不改代码即可操作；停用不删历史；替换不冒充旧序列；依赖无效时显式暂停。
- **失败回退：** 按总计划8.5确定性完成激活或原子恢复上一活动版本；已激活history快照永不删除，运行中的任务使用启动时configVersion完成或安全取消。
- **是否阻塞后续：** 是。

### D5-T04 新标的历史回填与任务状态

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 新增series后按选定Provider获取或等待输入当日数据，并执行历史回填、校验、每日和聚合重算。
- **对应需求：** H08、H09、F02、F12、F13、SUP-03、SUP-06。
- **输入：** Provider当前/历史能力、Manual输入状态、回填范围、series配置。
- **创建或修改文件：** backfill编排、`AWAITING_MANUAL_INPUT`状态、检查点、状态API和重试测试。
- **输出：** WAITING/AWAITING_MANUAL_INPUT/RUNNING/PARTIAL_SUCCESS/SUCCEEDED/FAILED进度及可恢复检查点。
- **依赖任务：** D5-T03、D4-T04、D3-T06。
- **具体测试：** GBP自动当日/跨年历史；材料Manual当前/历史输入；任务中断、部分失败、重复启动和无自动历史能力。
- **Definition of Done：** 自动或Manual数据均校验后才展示；Manual等待不伪装成功；历史逐期聚合生成；重启状态保留。
- **失败回退：** 保留已发布合法分片，暂停失败批次并从检查点重试；不删除旧标的历史。
- **是否阻塞后续：** 是；只有所需标的三条认可路线均不可执行时才形成材料P0阻塞。

### D5-T05 最小规则预警与持久化

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 用Java确定性规则生成价格/汇率变化、成本影响和数据质量预警，并持久化证据。
- **对应需求：** F10、项目题目、EXT-07、EXT-08。
- **输入：** 已验证指标、alert-rules、cost-plans、完整率。
- **创建或修改文件：** warning/costing模块、规则配置、预警JSON和测试。
- **输出：** ruleId、阈值、当前/基准值、风险等级、evidenceRefs、数据状态和时间。
- **依赖任务：** D4-T04、D5-T02。
- **具体测试：** 阈值边界、低完整率、未校验输入、重复运行和规则变更。
- **Definition of Done：** LLM不参与触发和等级；未校验数据不触发正式业务预警；预警可从证据重现。
- **失败回退：** 禁用有问题规则，保留数据质量预警和审计，不自动调价。
- **是否阻塞后续：** 是，阻塞Agent预警解释和Vue预警页。


---

## 9. Day 6：Agent、模型抽象与降级

### D6-T01 Agent工具契约与已验证数据边界

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 实现七个受控工具契约，并保证它们只能通过Java业务服务访问已验证数据。
- **对应需求：** F06、H01、H02、项目Agent冻结边界。
- **输入：** history、aggregation、quality、costing、warning和provenance服务。
- **创建或修改文件：** agent/tool模块、工具DTO、权限边界测试和工具说明文档。
- **输出：** `series.resolve`、`history.query`、`period.metrics`、`quality.inspect`、`cost.impact`、`warning.explain`、`provenance.trace`。
- **依赖任务：** D5-T02、D5-T05、D4-T02。
- **具体测试：** 分别调用七个工具；尝试传入文件路径、未验证ID、越界日期和未知series。
- **Definition of Done：** 工具只接受业务参数；输出包含来源、周期、业务日期、完整率和证据ID；不暴露任意文件读取能力。
- **失败回退：** 禁用不安全工具，仅保留通过契约测试的工具，不允许LLM直接补偿缺失能力。
- **是否阻塞后续：** 是。

### D6-T02 Agent编排与EvidencePack

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 建立受控流程：意图与标的识别、Java工具计划、确定性指标、EvidencePack和证据引用校验。
- **对应需求：** 项目Agent冻结边界、F06、F12。
- **输入：** 七个工具、用户问题、正式/演示模式。
- **创建或修改文件：** agent orchestration、intent模型、EvidencePack、报告装配和测试。
- **输出：** 趋势分析、成本风险概览、预警解释三类P0任务的结构化证据包。
- **依赖任务：** D6-T01。
- **具体测试：** 正常问题、模糊别名、缺少日期、无数据、演示数据和未验证数据提问。
- **Definition of Done：** Java决定工具链和事实；EvidencePack仅含允许状态；每个结论引用存在的evidenceId。
- **失败回退：** 返回结构化“数据不足/无法验证”结果，不进行自由ReAct或编造原因。
- **是否阻塞后续：** 是。

### D6-T03 LLMService、CloudLLMService与Local扩展点

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 用项目内部请求响应模型解耦云端厂商，并保留LocalLLMService接口但不部署本地模型。
- **对应需求：** C类AI架构冻结、Agent要求。
- **输入：** EvidencePack、模型配置、云端API凭证注入方式。
- **创建或修改文件：** llm端口、Cloud实现、Local接口/禁用实现、配置和适配测试；不得提交密钥。
- **输出：** 可配置provider、baseUrl、model和timeout的云端分析调用。
- **依赖任务：** D6-T02。
- **具体测试：** 成功响应、超时、401、限流、非法JSON、厂商字段变化和密钥日志检查。
- **Definition of Done：** Agent不依赖厂商DTO或原生function calling；切换配置不修改业务代码；Local未成为运行依赖。
- **失败回退：** 禁用云端适配器并调用D6-T05模板报告，不影响核心系统。
- **是否阻塞后续：** 是。

### D6-T04 证据核验、结构化报告与报告持久化

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 将Java事实区与LLM解释区分离，校验所有evidenceId并持久化结构化报告。
- **对应需求：** F06、F10、项目Agent冻结边界。
- **输入：** EvidencePack和LLM叙述结果。
- **创建或修改文件：** evidence verifier、report assembler、report JSON writer、Agent API和测试。
- **输出：** 事实、解释、建议、限制、来源引用分区的报告JSON。
- **依赖任务：** D6-T02、D6-T03。
- **具体测试：** 正常报告、未知证据ID、额外数字、无数据、演示模式和无证据因果问题。
- **Definition of Done：** 事实数字来自Java；不存在的引用被拒绝；报告可定位来源、周期和更新时间。
- **失败回退：** 丢弃不合法叙述，只保留Java事实区和限制说明。
- **是否阻塞后续：** 是。

### D6-T05 Java模板降级与后端退出门禁

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 云端LLM失败时根据同一EvidencePack生成Java模板报告，并完成后端端到端验收。
- **对应需求：** C类AI降级决策、F06、F10。
- **输入：** 已验证EvidencePack、LLM成功或失败状态。
- **创建或修改文件：** template reporter、降级策略、集成测试和验收证据。
- **输出：** 可验证的模板分析报告及明确的模型降级状态。
- **依赖任务：** D6-T04。
- **具体测试：** 正常云端、DNS失败、超时、429、5xx、空响应和断网；同时执行采集、查询、聚合、预警。
- **Definition of Done：** 故障时仍返回Java模板报告；核心业务不受影响；后端API可独立完成P0链路。
- **失败回退：** 只返回Java事实表和明确错误，不阻塞数据采集、查询、聚合和预警。
- **是否阻塞后续：** 是，阻塞Vue Agent工作台和Day 8 Web验收。

---

## 10. Day 7：Vue核心展示

### D7-T01 Vue3应用壳与API契约

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 建立Vue3/Vite页面壳、路由、统一API客户端、错误状态和精确小数字符串展示规范。
- **对应需求：** F01、H09、C类前端冻结。
- **输入：** 后端接口说明、UI页面清单。
- **创建或修改文件：** `frontend/`工程、路由、API层、布局和前端测试。
- **输出：** 可在浏览器运行的前端基础；不在前端重算业务值。
- **依赖任务：** D6-T05及相关后端API冻结。
- **具体测试：** API成功、无数据、离线、服务错误、超长小数字符串和中文路径构建。
- **Definition of Done：** 页面可导航；错误不白屏；表格显示后端原字符串；图表副本不回写。
- **失败回退：** 保留最小页面壳，关闭未完成路由，不修改后端数据规则迁就前端。
- **是否阻塞后续：** 是。

### D7-T02 仪表盘与默认监测项

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 展示PBOC EUR/USD，以及SMM来源意图×ADC12/AZ91D、Asian Metal来源意图×ADC12/AZ91D四条材料序列各自当前生效路线、实际来源、均值、质量和预警摘要；指定商业源自动能力单独显示。
- **对应需求：** H01、F03-F05、F06、H09。
- **输入：** 已发布数据API、默认series配置。
- **创建或修改文件：** dashboard页面、卡片、状态组件和页面测试。
- **输出：** 最新值、业务日期、`actualSourceName`、`providerType`、`accessMethod`、`routeDecision`、单位、完整率、更新时间、聚合摘要和演示水印。
- **依赖任务：** D7-T01、D4-T04、D5-T05。
- **具体测试：** 正式已验证、演示、stale、无数据、REJECTED/CONFLICT和来源失败；默认四条材料序列不按同名材料合并，替换时两条AZ91D隐藏、两条对应MAT-REPL-01显示且两条ADC12保持。
- **Definition of Done：** 未验证数值不显示；免费源/Manual显示真实来源且不冒充SMM/Asian Metal；演示模式全页标记；默认项来自配置而非页面硬编码。
- **失败回退：** 隐藏有缺陷组件并显示明确状态，不使用旧值冒充今日值。
- **是否阻塞后续：** 是。

### D7-T03 历史趋势、跨文件与数据质量页面

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 支持日期范围和粒度查询，展示跨年趋势、聚合表、缺失区间、来源和校验质量。
- **对应需求：** H01、H02、H06、F06、F12。
- **输入：** history、aggregation、quality API。
- **创建或修改文件：** history/quality页面、图表、数据表和测试。
- **输出：** 日/月/季/半年/年切换、证据引用和数据截至时间。
- **依赖任务：** D7-T01、D5-T02。
- **具体测试：** 2025-12至2026-01、缺文件、多来源、stale、精确字符串和空区间。
- **Definition of Done：** 图表与表格使用同一后端结果；缺失不插值；跨文件细节对用户透明但证据可追溯。
- **失败回退：** 保留精确表格，临时关闭不准确图表，不在浏览器重新聚合。
- **是否阻塞后续：** 是。

### D7-T04 手工录入、文件导入与来源管理页面

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 提供Manual表单、合法文件导入、来源能力与三层路线状态、模板下载、预览、逐行错误和synthetic演示入口。
- **对应需求：** F02、F04-F07、H08、SUP-03、SUP-05、SUP-06、SUP-07。
- **输入：** Manual/LocalImport/provider API、模板、`routeDecision`和运行模式。
- **创建或修改文件：** data-source/manual/import页面、表单、状态时间线和交互测试。
- **输出：** `actualSourceName`、标的、业务日期、值、单位、输入方式/时间、校验状态、最后更新时间、批次结果和路线状态。
- **依赖任务：** D7-T01、D3-T03、D3-T04、D3-T05、D3-T06。
- **具体测试：** 有效Manual、缺来源/错误单位、PENDING不可见、VERIFIED后可见、正常/错误/重复文件和synthetic隔离。
- **Definition of Done：** Manual提交先为PENDING；失败数据不发布；实际来源不得被改名为SMM/Asian Metal；路线与失败原因透明。
- **失败回退：** 禁用有缺陷入口，保留逐行错误和路线状态；前端不得绕后端直写processed文件。
- **是否阻塞后续：** 是。

## Day 8：动态配置、预警、Agent与Web冻结

### D8-T01 动态监测配置与历史回填闭环

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 在不改代码、不重启的前提下新增、停用和替换监测项，并为新增项触发当前值采集、历史导入/回填和聚合重算。
- **对应需求：** H07、H08、H09、F07、F08。
- **输入：** 配置Schema、Provider能力、系列唯一键、历史数据服务和配置管理API。
- **创建或修改文件：** dynamic-config页面、配置服务、回填编排、配置审计文件和测试。
- **输出：** 配置版本、变更审计、回填任务状态、系列可用状态及重算结果。
- **依赖任务：** D3-T01、D4-T01、D5-T04、D7-T01。
- **具体测试：** 停用EUR、新增GBP、把SMM/Asian Metal两个来源意图下的AZ91D均替换为MAT-REPL-01且ADC12保持不变；验证无需改代码/重启、当前值与历史均值生成、旧历史仍可查询、失败回填可重试。
- **Definition of Done：** 新增与停用全链路生效；界面随配置重构；旧数据不删除；配置写入采用校验、临时文件与原子替换。
- **失败回退：** 回滚到上一配置版本，保留失败审计和已存在历史文件，不执行破坏性清理。
- **是否阻塞后续：** 是，阻塞D10-T04和最终动态验收。

### D8-T02 预警规则、记录与确认闭环

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 对Java计算结果执行阈值、环比/同比和数据质量规则，持久化可追溯预警并支持确认。
- **对应需求：** F09、F10、F11、H02。
- **输入：** 已发布日值与聚合值、完整率、stale状态、可配置规则。
- **创建或修改文件：** warning服务、规则执行器、warning JSON仓储、warning页面/API和测试。
- **输出：** warningId、规则版本、证据引用、严重级别、状态、创建/确认时间和处置备注。
- **依赖任务：** D4-T04、D5-T03、D7-T01。
- **具体测试：** 阈值命中/未命中、缺失数据、规则变更、重复执行幂等、跨月轮转、确认后重启保持。
- **Definition of Done：** LLM不参与规则判定；每条预警可回溯到数据与公式；重复调度不产生重复记录。
- **失败回退：** 暂停故障规则并显示规则不可用，不把未计算结果当作正常。
- **是否阻塞后续：** 是，阻塞Agent风险报告和最终展示。

### D8-T03 工业供应链Agent工作台

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 将用户问题编排为受控工具调用、EvidencePack和可追溯风险报告，而非开放式聊天。
- **对应需求：** F13、F14、H01、H02。
- **输入：** D6工具注册表、EvidencePack、LLM抽象、Java模板降级、预警服务。
- **创建或修改文件：** agent页面、Agent编排API、工具执行时间线、报告视图和测试。
- **输出：** 意图、参数确认、工具调用摘要、证据引用、计算口径、风险等级、建议、模型/模板来源和截至时间。
- **依赖任务：** D6-T01至D6-T05、D8-T02、D7-T01。
- **具体测试：** “分析ADC12近期上涨风险”、歧义对象、无数据、LLM超时、synthetic模式、恶意提示和越权工具请求。
- **Definition of Done：** 所有数值来自工具结果；报告引用可点击回到历史/预警；LLM失败仍能生成Java模板报告。
- **失败回退：** 禁用云模型入口，保留工具查询和模板报告，不返回无证据自由回答。
- **是否阻塞后续：** 是，阻塞AI验收和最终演示。

### D8-T04 Web形态P0预验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 在Electron封装前按验收计划完成浏览器形态全链路预验收并关闭P0缺陷。
- **对应需求：** H01-H09、F01-F14。
- **输入：** D1-D8已完成成果、03验收计划、固定测试夹具。
- **创建或修改文件：** 测试报告、证据索引、缺陷清单和必要的P0修复文件。
- **输出：** AT用例执行记录、日志/截图/数据文件证据、缺陷严重度和复测结果。
- **依赖任务：** D8-T01、D8-T02、D8-T03。
- **具体测试：** 执行全部非Windows封装类P0用例，重点覆盖精度、轮转、跨文件、动态配置、来源隔离和LLM降级。
- **Definition of Done：** P0用例全部通过；无开放的P0/P1级阻断缺陷；证据可由用例ID定位。
- **失败回退：** 冻结新增功能，按数据正确性、可恢复性、展示顺序修复，不把缺陷推迟到Electron阶段。
- **是否阻塞后续：** 是，未通过不得进入封装。

### D8-T05 P0功能冻结与发布候选基线

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 建立Day 8功能冻结点，固定Schema、API、默认配置、夹具和候选版本。
- **对应需求：** H01-H09、F01-F14。
- **输入：** Web预验收报告、决策日志、配置与数据Schema。
- **创建或修改文件：** 版本说明、冻结清单、已知问题、Schema/API基线和任务台账。
- **输出：** 可封装的release candidate、版本号、校验和与冻结后变更审批规则。
- **依赖任务：** D8-T04。
- **具体测试：** 从干净工作副本重建前后端产物并复核固定夹具结果与文件格式一致。
- **Definition of Done：** 冻结内容可复现；只允许阻断缺陷修复进入候选版本；每次修复均有回归证据。
- **失败回退：** 撤销候选标记并回到最近通过预验收的版本，不在封装分支继续叠加功能。
- **是否阻塞后续：** 是，阻塞Day 9。

## Day 9：Electron便携封装与Windows运行闭环

### D9-T01 Electron壳与后端进程托管

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 用Electron封装冻结的Vue静态资源，启动并托管Spring Boot子进程，形成可双击运行的桌面应用。
- **对应需求：** H03、H04、F01及Windows最终交付约束。
- **输入：** D8-T05候选版本、前后端构建产物、Electron主进程设计。
- **创建或修改文件：** desktop主进程、窗口配置、资源打包清单、启动/停止脚本和测试。
- **输出：** Windows Electron EXE、受控后端进程、统一应用窗口和退出处理。
- **依赖任务：** D8-T05。
- **具体测试：** 双击启动、正常退出、强制关闭、后端启动失败、连续启动和前端资源缺失。
- **Definition of Done：** 用户无需命令行即可运行；应用关闭后后端进程退出；失败显示可操作的中文提示和日志路径。
- **失败回退：** 停止子进程并退出，不留下孤儿Java进程；保留浏览器候选版本用于诊断而非最终交付。
- **是否阻塞后续：** 是。

### D9-T02 捆绑JRE与资源路径发现

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 随应用捆绑Java 17运行时并可靠发现JAR、前端资源、配置、data和logs路径。
- **对应需求：** H03、H04及普通Windows电脑免安装Java约束。
- **输入：** 合法可再分发JRE、便携目录决策、冻结配置。
- **创建或修改文件：** runtime资源、路径解析、许可清单、预检逻辑和打包配置。
- **输出：** 自包含运行目录及启动前的路径、写权限和端口预检。
- **依赖任务：** D9-T01。
- **具体测试：** 未安装Java的干净环境、带空格/中文路径、只读目录、移动整个目录后启动和缺失JRE。
- **Definition of Done：** 不读取系统JAVA_HOME作为必要条件；所有业务数据位于EXE同级可见data目录；路径错误不会静默写入隐藏目录。
- **失败回退：** 阻止启动并提示复制到可写目录，不自动切换到不透明的用户目录。
- **是否阻塞后续：** 是。

### D9-T03 动态端口、健康检查与本地通信安全

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 安全选择本地端口，等待健康检查后开窗，并限制前后端仅在本机通信。
- **对应需求：** F01、H04及Windows稳定运行约束。
- **输入：** Spring Boot健康端点、Electron进程管理、端口策略。
- **创建或修改文件：** 端口分配、健康轮询、同源/令牌约束、CORS和超时配置及测试。
- **输出：** 每次启动唯一有效的后端地址、健康状态和安全的渲染进程访问桥。
- **依赖任务：** D9-T01、D9-T02。
- **具体测试：** 默认端口被占用、健康检查超时、恶意远程Origin、重复请求和后端崩溃。
- **Definition of Done：** 不依赖固定端口；不对局域网开放；窗口只在后端就绪后进入主页面；敏感密钥不暴露给渲染进程。
- **失败回退：** 超时后终止后端并展示诊断，不无限白屏或降低网络边界。
- **是否阻塞后续：** 是。

### D9-T04 单实例、生命周期与异常恢复

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 保证单实例运行、前后端生命周期一致，并对异常退出提供可诊断恢复。
- **对应需求：** F01、H05及Windows可运行性约束。
- **输入：** Electron壳、健康检查、日志与文件恢复机制。
- **创建或修改文件：** 单实例锁、进程监听、优雅关停、崩溃提示、启动恢复和测试。
- **输出：** 可预测的启动/退出行为、无孤儿进程、恢复建议和诊断信息。
- **依赖任务：** D9-T03。
- **具体测试：** 双击两次、任务管理器结束窗口/后端、系统休眠恢复、异常断电后重启、锁文件残留。
- **Definition of Done：** 第二实例聚焦首窗口；关闭后无残留进程；不完整临时文件按既定策略恢复或隔离。
- **失败回退：** 安全终止并保留日志与数据，不自动删除锁以外的业务文件。
- **是否阻塞后续：** 是。

### D9-T05 便携目录、ZIP制品与桌面冒烟验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 产出可解压即用的Windows便携目录和ZIP，并完成桌面形态核心链路冒烟测试。
- **对应需求：** H01-H09、F01-F14及最终交付约束。
- **输入：** D9-T01至D9-T04、冻结候选版本、许可证和启动说明。
- **创建或修改文件：** release目录布局、ZIP构建、校验和、README/快速启动说明和冒烟证据。
- **输出：** SupplyMind-AI版本化ZIP、EXE、根runtime/JRE、app、data（含唯一data/config）、logs、licenses和校验和文件。
- **依赖任务：** D9-T01、D9-T02、D9-T03、D9-T04。
- **具体测试：** 解压后双击、dashboard/history/config/warning/Agent、重启保留数据、目录整体移动和无网络降级。
- **Definition of Done：** ZIP在可写目录解压即用；data位于程序根目录可直接检查；不要求Docker、数据库、Redis、Node或系统JDK。
- **失败回退：** 不发布失败ZIP；保留最后一个通过冒烟的便携候选版本。
- **是否阻塞后续：** 是，阻塞Day 10。

## Day 10：正式验收演练、证据归档与发布

### D10-T01 干净Windows环境与无数据库验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 在未安装Java/Node/Docker/MySQL/Redis的普通Windows环境验证最终ZIP可直接运行且数据文件可见。
- **对应需求：** H03、H04及Windows最终交付约束。
- **输入：** D9-T05 ZIP、干净机/虚拟机、验收进程检查清单。
- **创建或修改文件：** 执行记录、进程截图、目录截图、日志和缺陷复测证据。
- **输出：** 安装依赖清单为零的启动证据、程序目录JSON/CSV证据和无数据库进程证据。
- **依赖任务：** D9-T05。
- **具体测试：** 解压双击、离线启动、查看data、任务管理器检查、重启、目录移动以及带空格/中文路径。
- **Definition of Done：** EXE可直接运行；data含可读JSON/CSV；无MySQL、Redis、SQLite/H2服务或隐藏数据库进程；无外置JRE要求。
- **失败回退：** 阻止发布并修复打包/路径问题，不要求验收人员临时安装依赖。
- **是否阻塞后续：** 是。

### D10-T02 系统时间跨期、轮转与跨年精度验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 通过受控修改Windows系统时间验证跨日/月/季/半年/年轮转及跨文件计算。
- **对应需求：** H01、H02、H05、H06。
- **输入：** 固定精度夹具、可恢复系统时间环境、验收用例和期望结果。
- **创建或修改文件：** 时间切换执行记录、生成文件、查询结果、精度对账表和截图。
- **输出：** 新轮转文件证据、跨年拼接/去重/排序证据及逐级均值对账结果。
- **依赖任务：** D10-T01。
- **具体测试：** 2025-12-31至2026-01-01、季/半年边界、重复采集、缺日、超长小数和随机历史自然月。
- **Definition of Done：** H01-H06相关用例全部通过；恢复真实系统时间；无中间精度损失或缺失补零。
- **失败回退：** 在隔离虚拟机执行并恢复快照；若轮转失败则停止验收、不手工补造文件。
- **是否阻塞后续：** 是。

### D10-T03 离线、LLM故障与文件恢复验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 验证网络、云模型和文件异常下核心监测与模板报告可用且数据不会静默损坏。
- **对应需求：** F02、F05、F13、F14、H02-H04。
- **输入：** 最终ZIP、故障注入方案、备份/临时文件和Java模板报告。
- **创建或修改文件：** 故障测试记录、恢复证据、日志、报告样例和必要阻断修复。
- **输出：** 离线状态提示、云模型降级报告、损坏文件隔离/恢复结果及可审计错误。
- **依赖任务：** D10-T01、D10-T02。
- **具体测试：** 断网、401/429/超时、半写文件、损坏CSV/JSON、磁盘只读和进程异常终止后重启。
- **Definition of Done：** 核心文件查询与Java模板报告不依赖LLM；损坏数据不进入计算；恢复过程不覆盖最后有效文件。
- **失败回退：** 切换只读/模板模式并明确告警，要求人工选择备份，不自动猜测修复业务数值。
- **是否阻塞后续：** 是。

### D10-T04 动态EUR/GBP/MAT-REPL-01场景验收

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 用真实验收操作验证停止旧对象、新增英镑/替换材料、历史回填和页面重构。
- **对应需求：** H07、H08、H09、F07、F08。
- **输入：** 最终ZIP、PBOC真实数据、选定的合法自动/FreePublic/Manual路线和动态配置验收脚本。
- **创建或修改文件：** 配置变更审计、回填证据、前后页面截图、查询结果和复测记录。
- **输出：** 无代码变更的配置结果、新系列当前/历史/聚合数据、旧历史保留及无错误页面。
- **依赖任务：** D8-T01、D9-T05、D10-T01。
- **具体测试：** 停止EUR、新增GBP、把SMM/Asian Metal两个来源意图下的AZ91D均替换为MAT-REPL-01且ADC12不变；自动获取或进入`AWAITING_MANUAL_INPUT`后提交；重启、失败重试和来源标签复核。
- **Definition of Done：** H07-H09全部通过；旧对象隐藏但历史可查；路线、完整率与实际来源透明；Manual不得标成自动采集。
- **失败回退：** 使用配置版本回滚并保留审计，不删除旧对象文件或伪造回填完成状态。
- **是否阻塞后续：** 是。

### D10-T05 文档、证据、发布包与最终签署

- **优先级/状态：** P0 / `NOT_STARTED`。
- **任务目标：** 汇总全部验收证据和交付材料，形成可追溯、可复现、可演示的最终发布包。
- **对应需求：** H01-H09、F01-F14及交付文档要求。
- **输入：** 所有AT结果、最终ZIP、官方需求基线、追踪矩阵、风险和外部确认状态。
- **创建或修改文件：** README、Windows部署/操作/演示手册、数据字典、API说明、测试报告、第三方许可、变更记录和进度台账。
- **输出：** 版本化发布目录、ZIP与SHA-256、需求—测试—证据索引、已知限制和演示脚本。
- **依赖任务：** D10-T01、D10-T02、D10-T03、D10-T04。
- **具体测试：** 按README由未参与开发者独立演练；逐项抽查H01-H09证据；校验ZIP哈希和文档链接。
- **Definition of Done：** PBOC双币硬门PASS；四个P0来源意图×材料序列各有一条获准的non-synthetic路线PASS；指定商业源自动能力可为`N/A_APPROVED_FALLBACK`而不阻塞整体P0；来源真实；发布制品与证据一一对应。
- **失败回退：** 不签署发布；退回对应任务修复并重跑受影响用例，禁止仅改文档掩盖缺陷。
- **是否阻塞后续：** 是，完成后项目才可声明交付。

## P1：质量提升任务（P0通过后按剩余时间选择）

### P1-T01 Ollama/Qwen本地模型适配

- **优先级/状态：** P1 / `NOT_STARTED`。
- **任务目标：** 在不改Agent业务编排的前提下增加Ollama/Qwen兼容的LocalLLMService实现。
- **对应需求：** E01。
- **输入：** LLMService契约、Ollama兼容接口、本地模型资源与性能预算。
- **创建或修改文件：** LocalLLM适配、配置、健康检查、超时策略和契约测试。
- **输出：** cloud/local/template三种可配置模式及统一响应元数据。
- **依赖任务：** D6-T03、D6-T05、全部P0验收。
- **具体测试：** 模式切换、Ollama未启动、慢响应、非法输出、云/本地契约一致性。
- **Definition of Done：** Agent层无条件分支改写；本地失败自动回到Java模板；模型安装不是P0交付前置。
- **失败回退：** 关闭local配置并保持cloud/template实现，不把Ollama打入基础ZIP。
- **是否阻塞后续：** 否。

### P1-T02 诊断包、备份恢复与数据导出

- **优先级/状态：** P1 / `NOT_STARTED`。
- **任务目标：** 提供脱敏诊断包、一键备份/恢复和可选择范围的CSV导出。
- **对应需求：** E08（并增强F06、F12的运维可检查性）。
- **输入：** `data/config`、根`logs`目录、脱敏规则、查询服务。
- **创建或修改文件：** 诊断导出、备份清单、恢复校验、导出页面和测试。
- **输出：** 带清单/哈希的备份包、脱敏诊断ZIP和用户查询导出文件。
- **依赖任务：** D9-T05、D10-T03。
- **具体测试：** 完整/增量范围、损坏备份、版本不兼容、敏感密钥脱敏和大范围导出。
- **Definition of Done：** 恢复前校验且可取消；诊断包不含API密钥；导出值保持原精度字符串。
- **失败回退：** 保持手工复制整个便携目录的P0方案，禁用不可靠的一键恢复。
- **是否阻塞后续：** 否。

### P1-T03 多来源对比、通知与签名安装体验

- **优先级/状态：** P1 / `NOT_STARTED`。
- **任务目标：** 增强同对象多来源差异对比、桌面通知，并在条件允许时提供签名安装包。
- **对应需求：** E09（并增强F02、F09-F11的展示/交付质量）。
- **输入：** 来源冲突数据、预警事件、代码签名证书/发行资源。
- **创建或修改文件：** 来源对比视图、通知适配、可选安装构建、签名与升级说明和测试。
- **输出：** 来源差异证据、可控桌面通知以及不替代便携ZIP的可选安装包。
- **依赖任务：** D8-T02、D9-T05、外部证书与授权确认。
- **具体测试：** 冲突来源、通知去重/关闭、无证书构建、安装/卸载不删除外部备份。
- **Definition of Done：** 正式/演示来源不混合；通知可配置；签名状态透明；便携ZIP仍是P0基准。
- **失败回退：** 移除安装/通知增强，保留应用内预警和便携发布。
- **是否阻塞后续：** 否。

### P1-T04 可选开发/运维容器

- **优先级/状态：** P1 / `NOT_STARTED`。
- **任务目标：** 提供仅供开发或运维辅助的容器配置，证明它不替代P0 Windows便携交付。
- **对应需求：** E06。
- **输入：** 已通过P0验收的源码、构建说明和Windows便携包。
- **创建或修改文件：** 可选容器构建文件、辅助说明和隔离测试。
- **输出：** 可删除的辅助容器路径及与Windows正式包独立的验证记录。
- **依赖任务：** 全部P0验收。
- **具体测试：** 使用容器完成辅助构建/诊断；完全移除Docker后重新执行Windows包启动与核心验收。
- **Definition of Done：** Docker不是构建P0、运行EXE或读取业务data的必需条件；容器不承载唯一业务真值。
- **失败回退：** 删除容器增强，保持P0 Windows流程不变。
- **是否阻塞后续：** 否。

## P2：创新加分与研究任务

### P2-T01 可引用RAG知识库

- **优先级/状态：** P2 / `NOT_STARTED`。
- **任务目标：** 引入经授权的企业规则、术语和处置手册检索，为Agent建议提供文档引用。
- **对应需求：** E03；不替代H01-H09验收。
- **输入：** 授权知识文档、切分/索引策略、引用Schema和权限边界。
- **创建或修改文件：** 文档摄取、检索接口、引用展示、版本审计和评测集。
- **输出：** EvidencePack中的知识引用及可追溯回答。
- **依赖任务：** D6-T02、D8-T03、知识授权确认。
- **具体测试：** 命中/未命中、过期版本、提示注入文档、引用准确率和撤回文档。
- **Definition of Done：** 无引用时明确说明；业务数值仍只来自Java工具；文档可删除/重建索引。
- **失败回退：** 关闭RAG并保持P0证据报告，不让检索结果进入数值计算。
- **是否阻塞后续：** 否。

### P2-T02 vLLM、本地Qwen与LoRA实验

- **优先级/状态：** P2 / `NOT_STARTED`。
- **任务目标：** 在独立实验环境评估vLLM托管Qwen及LoRA领域适配的质量、资源和合规性。
- **对应需求：** E02、E04、E05。
- **输入：** 合法模型权重、脱敏训练/评测集、GPU资源、LLMService兼容协议。
- **创建或修改文件：** 实验配置、适配器、评测报告、模型卡和资源基准。
- **输出：** 可复现实验结果、是否进入产品的决策建议及风险清单。
- **依赖任务：** P1-T01、数据/模型许可与GPU资源确认。
- **具体测试：** 质量基线、幻觉、敏感信息、吞吐/延迟/显存、适配器回滚和协议兼容。
- **Definition of Done：** 实验与P0发布隔离；没有授权数据不训练；替换模型不修改Agent业务层。
- **失败回退：** 保留实验报告并回到cloud/Ollama/template，不把模型文件纳入基础交付。
- **是否阻塞后续：** 否。

### P2-T03 预测、情景模拟与多Agent研究

- **优先级/状态：** P2 / `NOT_STARTED`。
- **任务目标：** 研究可解释的成本情景模拟、价格预测和受控多Agent协作，作为决策辅助而非自动调价。
- **对应需求：** E07。
- **输入：** 足量且获授权历史数据、评测基线、业务约束和人工审批规则。
- **创建或修改文件：** 独立实验模块、回测报告、置信区间展示、审批流程原型和风险说明。
- **输出：** 带假设/误差/置信区间的模拟报告及是否产品化建议。
- **依赖任务：** P2-T01、数据质量与业务规则确认。
- **具体测试：** 时间外验证、数据泄漏、极端场景、错误建议拦截、多Agent循环/成本限制。
- **Definition of Done：** 预测与事实清晰分区；不得自动修改价格或采购决策；效果不达标可完全关闭。
- **失败回退：** 仅保留确定性趋势指标和人工决策流程，不包装低质量预测为生产能力。
- **是否阻塞后续：** 否。

## 推荐启动顺序

- D1-T04 已通过 Sol 技术负责人和 OpenCode 独立 Review，状态为 `DONE`；D1-T05 亦已完成 Review 收口为 `DONE`（Day 1=`COMPLETE`，Git 基线 `day1-complete`）。
- D1-T01～D1-T05 均已由 Review 为`DONE`；Day 1=`COMPLETE`；D2-T01～D2-T05 均`DONE`；AT-SRC-002=`PASS`、DEC-056 implementation=`PASS`、Day 2=`COMPLETE`；下一正式任务 D3-T01=`READY_NOT_STARTED`。
- P1/P2仅在P0验收全绿、Day 8功能冻结未被破坏且仍有时间时启动。
