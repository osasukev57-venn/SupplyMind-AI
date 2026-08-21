# SupplyMind AI 验收测试计划

版本：v1.5  
文档状态：P0 验收基线  
适用平台：Windows x64 / Electron 最终交付包  
数据持久化约束：仅本地 JSON/CSV 业务文件，不使用数据库

## 0. 项目方实施补充说明

> “选择供应链成本监测与动态调价预警智能体的同学们，请优先完成汇率爬取和存取。如有关大宗交易网站因会员限制，反爬机制无法自动获取信源，则保留手动填写接口，或者查找同类免费信源网站。”

本说明独立保存于 `00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md`，不与原始需求书正文混写。它正式调整数据获取实施顺序和商业来源阻塞判定，不降低H01-H09、校验门禁或文件验收。

## 1. 目的与验收原则

本文将官方需求 H01-H09 以及项目负责人复核确认的配套功能转换为可重复执行、可留证据、可判定通过或失败的验收用例。

验收遵循以下原则：

1. 未经校验的数据不得进入 Dashboard、业务查询 API、预警或 Agent。
2. “全链路准确、无精度流失”是指展示值与对应实际来源原始证据或手工输入审计记录，以及已声明的确定性转换规则一致；所有派生值可重算，中间不使用二进制浮点或已舍入展示值。
3. 可控 Clock 覆盖周期边界；专用 Windows/VM 修改物理系统时间验证最终 EXE，两类测试不能互相替代。
4. PBOC EUR/CNY、USD/CNY 真实自动获取至raw/lifecycle JSON、`PARSED+PENDING→VALIDATED→PUBLISHED+VERIFIED类`、daily/aggregate CSV和重启读取是Day1-2及P0硬门。
5. 原材料按“合法指定源自动→同类免费公开信源→Manual”逐标的选择；三层路由必须记录决策，不得因网络故障静默换源。
6. SMM/Asian Metal合法自动能力存在时执行对应能力测试；因会员、无公开接口或合法反爬无法获取时可记N/A_APPROVED_FALLBACK，并强制验证认可替代路线。
7. Synthetic/Mock只能证明软件链和演示能力；合法FreePublic和可追溯Manual是项目方认可的真实P0降级通道，但不得冒充指定商业源。
8. 每个AT用例必须保存独立证据，且不得包含API Key、登录Cookie、令牌或未脱敏商业数据。
9. P0发布要求AT-SRC-002及来源治理用例通过，并为SMM/Asian Metal来源意图×ADC12/AZ91D四个P0材料序列分别证明一条非synthetic合法路线；指定商业源自动能力N/A本身不阻塞整体P0。
10. 任务执行状态、验收用例状态和数据生命周期状态必须独立记录。D1-T02字段事实虽已确认，本轮任务曾因重放证据不完整而重开；其实时状态只看docs/05，任何任务状态都不得使AT-SRC-002、PBOC真实数据验收或Day1/Day2退出门禁自动记为PASS。
11. “JSON/CSV”表示系统整体使用两种可检查格式：config（含history快照）/raw/lifecycle/quarantine/warning/report/runtime/manifest为JSON，processed daily与四级aggregate为CSV；不要求每个结果重复生成两种格式。

## 2. 需求基线

| 需求 ID | 验收基线 |
|---|---|
| H01 | 随机自然月展示指定货币和原材料的每日加工均值，并正确计算月、季度、半年和年度均值 |
| H02 | 从原始数据到展示和 Agent 的全链路准确，不发生未声明的精度流失 |
| H03 | 用户可在程序数据目录直接看到 JSON/CSV 业务数据文件 |
| H04 | 不依赖 MySQL、Redis、SQLite、H2 或其他隐藏数据库进程/业务数据库 |
| H05 | 修改系统时间跨周期后，系统自动创建并轮转到正确的新文件 |
| H06 | 多个历史轮转文件可跨年读取、拼接、去重并按业务时间排序 |
| H07 | 用户可动态停用或新增监测标的，不修改代码、不重启应用 |
| H08 | 新增标的后获取当日数据，并自动启动历史回填及各层级均值计算 |
| H09 | 配置变化后面板正确重构：旧项隐藏、新项显示、无异常，且旧项历史不删除 |
| SRC-01 | 按配置定时自动获取PBOC EUR/CNY、USD/CNY，并完成raw、校验、daily文件及重启读取闭环 |
| SRC-02 | SMM/Asian Metal来源意图×ADC12/AZ91D四个P0序列按“合法指定源自动→免费公开信源→Manual”接入，每条序列至少一条非synthetic路线通过 |
| SRC-03 | Manual数据记录必填来源元数据，并经过不可变raw + 独立RECEIVED+PENDING LifecycleRecord、PARSED、VALIDATED、PUBLISHED+VERIFIED类状态、加工和聚合 |
| SRC-04 | 文件、API、页面、预警和Agent始终展示actualSourceName；免费/手工来源不得冒充SMM/Asian Metal |
| PUB-01 | 原始数据完成解析、校验和发布门禁后才能被业务使用 |
| PER-01 | raw/lifecycle等持久化为JSON；日、月、季度、半年、年度结果均持久化为CSV |
| ALT-01 | 对已发布数据执行确定性阈值预警，并保存可追溯证据 |
| AI-01 | Cloud LLM 不可用时系统降级，核心采集、校验、计算、查询和预警继续工作 |
| WIN-01 | 最终交付 Electron Windows EXE，内置运行依赖，可在干净 Windows 环境运行 |

## 3. 测试状态与发布判定

| 状态 | 定义 |
|---|---|
| PASS | 实际结果符合全部预期，且证据完整 |
| FAIL | 已执行真实用例，且至少一项预期不符合；例如双币链路未到daily持久化 |
| BLOCKED | 外部访问、必要业务口径、任何认可数据路线或测试环境缺失，无法合法执行；不是PASS |
| N/A_APPROVED_FALLBACK | 仅用于指定商业源自动能力：有会员/无公开接口/合法反爬证据，且已选择并验证项目方认可的替代路线；不等于该自动能力PASS |
| `NOT_RUN` | 尚未执行 |

以下情况禁止记为 PASS：

- D1-T02或其他开发任务标记DONE，但对应AT用例没有独立、可复核的通过证据。外部失败证据只能导致`NOT_RUN`、`BLOCKED`或`FAIL`。
- 使用Mock、Fixture或Synthetic证明真实来源要求；FreePublic/Manual不得被用来把SMM/Asian Metal自动能力本身判PASS，但可通过独立降级用例满足材料P0。
- 数据值正确，但无法追溯到原始记录和校验版本。
- Dashboard 隐藏了错误，但业务 API 或 Agent 仍能读取未校验值。
- 展示值看似一致，但中间使用 double、float 或 JavaScript Number 作为业务真值。
- 修改可控 Clock 通过，却没有在专用 Windows/VM 上执行物理系统时间测试。
- 开发机已安装 Java、Node 或数据库，因此没有证明最终 EXE 自带运行依赖。

## 4. 测试环境

### 4.1 自动化环境

- 固定时区：Asia/Shanghai。
- 可注入的 Clock，不修改宿主操作系统时间。
- 每个用例通过`supplymind.data-root`显式注入独立临时data目录；解析后只能存在一个规范化绝对dataRoot。
- 固定 Locale 和字符集 UTF-8。
- 使用带 SHA-256 清单的黄金 Fixture。
- 禁止连接正式商业账号，除非该用例明确为授权来源验收。

### 4.2 专用 Windows/VM 物理时间环境

- Windows x64 专用测试机或可回滚 VM。
- 执行前创建快照，关闭系统自动对时。
- 物理改时测试期间优先断开外网，使用本地 Fixture 触发采集/写入，以免错误系统时间影响 TLS、登录或商业来源。
- 每次测试记录改时前后系统时间、时区、文件树、任务日志和应用进程。
- 测试结束后恢复快照或恢复正确时间并重新启用自动对时。
- 禁止在开发者日常物理电脑上执行系统时间前跳或回拨。

### 4.3 干净 Windows 环境

- 不预装 Java、Node.js、Maven、MySQL、Redis、SQLite、H2 服务或开发 IDE。
- 使用最终候选 Electron 便携 ZIP，在可写目录解压后双击 EXE。
- 测试账户对解压后的便携目录可写；另设只读目录验证启动前检查。
- 解压前保存进程、服务、监听端口和环境变量基线。

## 5. 黄金数据

### 5.1 GD-01：精确层级聚合数据

业务标的 A 为 EUR/CNY。对 2025 年每个月 m（m=1…12）建立两天有效数据：

GD-01使用独立测试配置覆盖：`calculationVersion=arithmetic-mean-v1`、`calculationScale=12`、`displayScale=9`、`roundingMode=HALF_UP`、`calendarVersion=golden-calendar-v1`。生产PBOC初始默认仍为arithmetic-mean-v1/8/4/HALF_UP/weekday-asia-shanghai-v1。测试证据必须同时保存活动配置及两个不可变history快照，禁止把测试覆盖冒充生产默认值。

- 当月 10 日的目标日均值：7 + m/100 - 0.001 + m×0.00000001。
- 当月 20 日的目标日均值：7 + m/100 + 0.001 + m×0.00000001。
- 每个目标日均值由三条原始观测组成：目标值减 0.000000001、目标值、目标值加 0.000000001。

以下`persist12(x)`表示`x.setScale(12, HALF_UP).toPlainString()`，`display9(x)`表示API/UI边界的`x.setScale(9, HALF_UP).toPlainString()`。预期 EUR/CNY：

| 周期 | CSV持久化avg（12位） | API/UI展示（9位） |
|---|---:|---:|
| 每月 m | persist12(7 + m/100 + m×0.00000001) | display9(7 + m/100 + m×0.00000001) |
| 2025 Q1 | 7.020000020000 | 7.020000020 |
| 2025 Q2 | 7.050000050000 | 7.050000050 |
| 2025 Q3 | 7.080000080000 | 7.080000080 |
| 2025 Q4 | 7.110000110000 | 7.110000110 |
| 2025 H1 | 7.035000035000 | 7.035000035 |
| 2025 H2 | 7.095000095000 | 7.095000095 |
| 2025 年 | 7.065000065000 | 7.065000065 |

业务标的 B 为 AZ91D，单位 CNY/t。其每条原始值定义为：

AZ91D = 12000 + 1000 × 对应 EUR/CNY Fixture 值。

预期 AZ91D：

| 周期 | CSV持久化avg（12位） | API/UI展示（9位） |
|---|---:|---:|
| 2025 Q1 | 19020.000020000000 | 19020.000020000 |
| 2025 Q2 | 19050.000050000000 | 19050.000050000 |
| 2025 Q3 | 19080.000080000000 | 19080.000080000 |
| 2025 Q4 | 19110.000110000000 | 19110.000110000 |
| 2025 H1 | 19035.000035000000 | 19035.000035000 |
| 2025 H2 | 19095.000095000000 | 19095.000095000 |
| 2025 年 | 19065.000065000000 | 19065.000065000 |

该Fixture的原始值原词法保存；daily/aggregate avg按12位持久化，展示严格按9位输出。两者必须分别断言，尾零不能被省略或相互冒充。

### 5.2 GD-02：非终止小数与大数精度

- 同一天三条值：100.1、100.2、100.2。
- 精确累计值：300.5。
- 有效样本数：3。
- 按展示规则 scale=8、HALF_UP 时，日均展示值：100.16666667。
- 大数样例：999999999999.123456789 与 0.000000001。
- 精确加和预期：999999999999.123456790。

非终止小数不能伪装成无限精确。验收要求是保留原始累计值、有效样本数、精度上下文和舍入规则，并且每次从底层重算得到相同展示结果；中间层不得先按展示精度舍入。

### 5.3 GD-03：发布门禁异常数据

包含：

- 必填值为空。
- 非法负价格。
- 币种不匹配。
- 单位不匹配。
- 业务日期无法解析。
- 相同业务唯一键的完全重复记录。
- 相同业务唯一键但值冲突的记录。
- 原始文件在首次校验后被修改，SHA-256 不再匹配。
- 一条超出已配置变化范围、需要人工复核的记录。

### 5.4 GD-04：跨年拼接数据

业务时间范围为 2025-12-20 至 2026-01-10，至少包含：

- 2025-12-30 和 2025-12-31 的已发布记录。
- 2026-01-01 和 2026-01-02 的已发布记录。
- 文件之间的一条重复业务记录。
- 一条顺序错乱但有效的记录。
- 一条校验失败记录。
- 两个明确无数据的日期。

预期查询只返回四条唯一已发布记录，按业务时间升序；无数据日期保留缺口，不插值造数。

### 5.5 GD-05：动态配置与历史回填

初始配置：

- EUR/CNY：启用。
- USD/CNY：启用。
- SMM来源意图×ADC12、SMM来源意图×AZ91D：两条独立item均启用。
- Asian Metal来源意图×ADC12、Asian Metal来源意图×AZ91D：两条独立item均启用。
- GBP/CNY：不存在。
- 替代材料标识：MAT-REPL-01，由需求方在执行前指定；SMM与Asian Metal两个来源意图下必须分别创建不同的新itemId和fixture，旧两条AZ91D停用但不删除，两条ADC12保持不变，且每个新item必须对应合法可访问路线。

可控 Clock 当前业务日期设为 2026-01-10。GBP 和 MAT-REPL-01 的 Fixture 包含：

- 2026-01-10 当日数据。
- 2025-12-20 至 2026-01-09 历史数据。
- 一个已经成功完成的历史批次。
- 一个中途中断、可断点恢复的历史批次。
- 一个重复批次。

外部真实来源验收使用实际当前日期和需求方书面确认的历史窗口，不使用上述固定日期代替。

### 5.6 GD-06：断网与 LLM 故障

- 本地已有一整年的已发布日/月/季/半年/年度文件。
- 新一轮官方源采集即将触发。
- Cloud LLM 分别模拟 DNS 失败、连接超时、HTTP 429 和 HTTP 5xx。
- 官方数据源分别模拟连接失败和响应超时。

### 5.7 GD-07：来源治理与手工门禁

准备以下记录：

1. 一条带真实免费网站名称、URL、许可引用和完整规格映射的FreePublic材料记录。
2. 一次合法Manual请求：请求体包含actualSourceName、itemId、businessDate、value、unit、currency、sourceReference，认证上下文提供operatorRef；inputAt、receivedAt、updatedAt与accessMethod=manual由服务端生成，不由调用方伪造。
3. 一条缺actualSourceName的Manual记录。
4. 一条错误单位和一条未来业务日期Manual记录。
5. 一条把免费网站故意伪标成SMM的记录。
6. 一条相同数值的Synthetic记录。

GD-07用于AT-SRC-005至AT-SRC-008；每个文件生成SHA-256并固定期望状态、实际来源和下游可见性。

## 6. 需求追踪矩阵

| 需求 | 主要 AT 用例 |
|---|---|
| H01 | AT-AGG-001、AT-AGG-002、AT-AGG-003 |
| H02 | AT-PUB-001、AT-PUB-002、AT-PUB-003、AT-PREC-001、AT-PREC-002、AT-PREC-003、AT-FILE-000 |
| H03 | AT-FILE-000、AT-FILE-001、AT-FILE-002、AT-OPS-002 |
| H04 | AT-FILE-000、AT-OPS-001 |
| H05 | AT-TIME-001、AT-TIME-002、AT-TIME-003、AT-TIME-004 |
| H06 | AT-XR-001、AT-XR-002 |
| H07 | AT-CFG-001、AT-CFG-002、AT-CFG-004 |
| H08 | AT-CFG-002、AT-CFG-003、AT-CFG-004 |
| H09 | AT-UI-001、AT-UI-002 |
| SRC-01 | AT-SRC-001、AT-SRC-002 |
| SRC-02 | AT-SRC-001、AT-SRC-003、AT-SRC-004、AT-SRC-005、AT-SRC-006、AT-SRC-007 |
| SRC-03 | AT-SRC-007、AT-PUB-001、AT-PUB-002 |
| SRC-04 | AT-SRC-006、AT-SRC-007、AT-SRC-008 |
| PUB-01 | AT-PUB-001、AT-PUB-002、AT-PUB-003、AT-FILE-000 |
| PER-01 | AT-FILE-000、AT-FILE-001、AT-FILE-002、AT-AGG-001 |
| ALT-01 | AT-ALT-001 |
| AI-01 | AT-AI-001 |
| WIN-01 | AT-WIN-001、AT-WIN-002、AT-OPS-001、AT-OPS-002 |

## 7. 详细验收用例

### AT-SRC-001 来源合法性与三层降级决策

- 对应需求：SRC-01、SRC-02、SRC-04、H02。
- 前置条件：已建立PBOC、SMM、Asian Metal及候选免费源清单；可查看公开条款、授权状态和Provider配置。
- 测试数据：每个来源的访问方式、频率、历史能力、许可/限制证据和ADC12/AZ91D路由候选。
- 步骤：
  1. 核对PBOC合法公开访问方式，并确认EUR/CNY、USD/CNY为第一优先任务。
  2. 对SMM、Asian Metal逐一判断合法公开/授权自动能力，不尝试绕过登录、验证码、会员或反爬。
  3. 对不可自动获取的材料按FreePublic、Manual顺序选择路线。
  4. 保存每个标的的routeDecision、fallbackReason、actualSourceName和生效时间。
- 预期：
  1. PBOC路线为OfficialWeb且不依赖材料商业授权。
  2. 四个来源意图×材料序列都有明确、可审计的三层选择结果。
  3. 不存在未授权Cookie、验证码规避、会员共享或反爬绕过。
  4. 指定商业源不可用不会把整个P0标记BLOCKED；但也不会被误记为自动能力PASS。
- 证据：来源能力矩阵、公开条款/授权引用、路由配置、fallbackReason、合规评审；存入AT-SRC-001。

### AT-SRC-002 Day1-Day2 PBOC双币真实获取与文件闭环

- 对应需求：SRC-01、F03、H01、H02、PUB-01、PER-01。
- 前置条件：OfficialWebDataProvider启用；EUR/CNY、USD/CNY均配置；网络正常；使用空的独立data目录。
- 测试数据：验收时可获取的PBOC两币种官方记录及原始响应/页面证据。
- 步骤：
  1. 触发真实采集并分别保存EUR/CNY、USD/CNY原始JSON。
  2. 执行标准化、基础校验和发布门禁。
  3. 生成两个币种的daily CSV及可形成的月/季/半年/年aggregate CSV。
  4. 重复同业务日期采集验证幂等。
  5. 关闭并重启程序，从本地文件查询历史和聚合。
- 预期：
  1. 两个币种均带actualSourceName=`中国人民银行官网（授权中国外汇交易中心公布）`、业务日期、采集时间、来源引用和raw哈希。
  2. PENDING阶段对面板/Agent不可见；只有ProcessingStage=PUBLISHED且ValidationStatus为VERIFIED或VERIFIED_WITH_NOTICE的记录进入daily。
  3. daily及聚合数值可由raw按声明规则复算，无精度损失。
  4. 重复采集不重复发布，重启后不联网也可读取已存历史。
  5. 任一币种缺失或链路未到daily持久化时，本用例FAIL且Day2不得退出。
  6. D1-T02调查结束或外部访问失败证据不改变本用例结果；外部环境阻止真实访问时记BLOCKED，已执行但链路不完整时记FAIL，二者都不得记PASS。
- 证据：双币raw/lifecycle JSON、daily/aggregate CSV、manifest、幂等记录、重启查询、SHA-256；存入AT-SRC-002。

### AT-SRC-003 SMM指定源自动能力条件验收

- 对应需求：SRC-02、F04、H02、H08。
- 前置条件：已完成SMM合法访问能力评审和ADC12/AZ91D规格映射。
- 测试数据：合法接口/公开页面测试记录，或会员限制、无公开接口、合法反爬证据。
- 步骤：
  1. 若存在合法公开或授权自动路径，采集两个材料并执行统一链路。
  2. 若不存在，停止自动访问，记录能力限制并选择FreePublic或Manual路线。
  3. 检查系统没有静默换源或沿用SMM标签。
- 预期：
  1. 合法自动路径可用时，实际结果符合即PASS，不符合即FAIL。
  2. 不可合法自动获取时，本用例记N/A_APPROVED_FALLBACK，不记PASS，也不阻塞整体P0。
  3. N/A时必须有routeDecision，并由AT-SRC-005及所选AT-SRC-006/007证明替代路线。
  4. 任何绕过访问限制或来源冒充直接FAIL。
- 证据：SMM能力评审、授权/限制依据、条件结果、routeDecision和替代用例引用；存入AT-SRC-003。

### AT-SRC-004 Asian Metal指定源自动能力条件验收

- 对应需求：SRC-02、F05、H02、H08。
- 前置条件：已完成Asian Metal合法访问能力评审和ADC12/AZ91D规格映射。
- 测试数据：合法接口/公开页面测试记录，或会员限制、无公开接口、合法反爬证据。
- 步骤：
  1. 若存在合法公开或授权自动路径，采集两个材料并执行统一链路。
  2. 若不存在，停止自动访问，记录能力限制并选择FreePublic或Manual路线。
  3. 检查系统没有静默换源或沿用Asian Metal标签。
- 预期：
  1. 合法自动路径可用时，实际结果符合即PASS，不符合即FAIL。
  2. 不可合法自动获取时，本用例记N/A_APPROVED_FALLBACK，不记PASS，也不阻塞整体P0。
  3. N/A时必须有routeDecision，并由AT-SRC-005及所选AT-SRC-006/007证明替代路线。
  4. 任何绕过访问限制或来源冒充直接FAIL。
- 证据：Asian Metal能力评审、授权/限制依据、条件结果、routeDecision和替代用例引用；存入AT-SRC-004。

### AT-SRC-005 ADC12/AZ91D三层路由与P0判定

- 对应需求：SRC-02、SRC-04、F04、F05、H08。
- 前置条件：AT-SRC-001完成；六类Provider能力矩阵可用。
- 测试数据：SMM意图×ADC12/AZ91D、Asian Metal意图×ADC12/AZ91D四条序列各自的指定源、候选免费源和Manual配置。
- 步骤：
  1. 对四个P0来源意图×材料序列逐条按指定源合法自动、FreePublic、Manual顺序评估。
  2. 配置选定路线并记录未选择上一级的理由。
  3. 执行当前数据获取/输入以及最小历史数据进入链路。
  4. 汇总各标的对应的能力测试结果。
- 预期：
  1. 每个P0来源意图×材料序列恰有一个当前生效路线，且切换不静默发生。
  2. 至少一条非synthetic路线完成raw到已验证文件链。
  3. 商业自动能力N/A不会扩散为整个P0 BLOCKED。
  4. 若任一标的三条路线均不可执行，则该标的和整体材料P0为BLOCKED。
- 证据：路由配置版本、能力矩阵、fallbackReason、当前/历史任务、P0判定表；存入AT-SRC-005。

#### 阶段子用例（DEC-058 跨阶段 Acceptance 状态模型）

- **AT-SRC-005-D3（Stage=Day 3）**：
  - Parent Case：AT-SRC-005（父用例保持完整端到端语义，当前`NOT_RUN`）。
  - 前置条件：AT-SRC-001 完成；六类Provider能力矩阵可用；四条P0序列进入生产默认配置。
  - 执行：对四个P0来源意图×材料序列（MAT.ADC12.SMM、MAT.ADC12.AM、MAT.AZ91D.SMM、MAT.AZ91D.AM）按PRIMARY→FREE_PUBLIC→MANUAL顺序评估并记录routeDecision/fallbackReason；通过生产启动路径（active config→Provider Registry→MaterialRoutePlanService→MaterialRouteResolver）解析。
  - 预期（Day3范围）：每条序列恰有一个当前生效合法non-synthetic路线且切换不静默（fallbackReason可审计）；PRIMARY=`NOT_CONFIGURED`（credentials_missing）、FREE_PUBLIC=`NO_APPROVED_SOURCE`（无获认可免费源）、selected=`FALLBACK_MANUAL`/`manual-material`；商业自动能力N/A不扩散为整体P0 BLOCKED；无伪造、无Synthetic正式候选。
  - 不得包含：raw→VALIDATED、VERIFIED、PUBLISHED、daily、aggregate（属 Day4 范围）。
  - 证据：route matrix、routeDecision/fallbackReason、生产配置与Registry证据；存入AT-SRC-005-D3。
  - AcceptanceStatus：`PASS`（Day3 已完整满足自身预期）。
- **AT-SRC-005-D4（Stage=Day 4）**：
  - Parent Case：AT-SRC-005。
  - 范围：预期2"至少一条非synthetic路线完成raw到已验证文件链"（材料 validation→VALIDATED→VERIFIED类，负责人 D4-T01/D4-T02，DEC-057）。
  - AcceptanceStatus：`NOT_RUN`（未实施）。
- 父用例 AT-SRC-005 仅在所有 mandatory stage subcases（含 AT-SRC-005-D3、AT-SRC-005-D4）PASS 并完成完整 evidence reconciliation 后才允许 `PASS`；Day 3 Gate 只引用 AT-SRC-005-D3。

### AT-SRC-006 FreePublicDataProvider全链与真实来源

- 对应需求：SRC-02、SRC-04、H01、H02、PUB-01、PER-01。
- 前置条件：已选定无需绕过限制的免费公开材料来源，记录URL、许可依据和字段/规格映射。
- 测试数据：该免费来源的ADC12或AZ91D当前记录及允许使用的历史样本。
- 步骤：
  1. 通过FreePublicDataProvider自动获取并保存raw。
  2. 执行标准化、校验、VERIFIED发布、daily和多周期聚合。
  3. 检查文件、API、面板、预警和Agent证据中的来源。
  4. 模拟页面字段变化，验证失败不会发布旧标签新值。
- 预期：
  1. 全链保存providerType=free_public、actualSourceName、URL/引用和采集时间。
  2. 实际网站名称在所有出口一致，不出现SMM/Asian Metal伪标签。
  3. 未校验数据不可见；raw/lifecycle以JSON、已验证daily/aggregate以CSV持久化。
  4. 解析失败明确告警，不自动切到未知网站。
- 证据：条款/URL、raw、字段映射、校验报告、daily/aggregate、页面与Agent截图；存入AT-SRC-006。
- **Post-Day10 执行（DEC-063）：** ADC12/SHFE 子范围=PASS：官方公开 HTTPS、完整 response entity raw/SHA、字段漂移 fail-closed、双 item 独立 timeline、material-basic-validation-v2、PUBLISHED、daily 与 month/quarter/halfyear/year 均通过；actualSourceName 明确为 SHFE 公开基准，不等于 SMM/Asian。AZ91D 仍由 AT-SRC-007 Manual 路线覆盖。证据：`docs/evidence/FinalRelease/FREEPUBLIC-DEMO-CLOSURE-20260821.md`。

### AT-SRC-007 ManualDataProvider治理与门禁

- 对应需求：SRC-02、SRC-03、SRC-04、H01、H02、H08、PUB-01。
- 前置条件：ManualDataProvider和表单可用；`manual-material-normalization-v1` 规则固定；业务标的已配置。
- 测试数据：一条合法材料值；缺实际来源、错误单位、未来日期、重复及修订数据。
- 阶段责任（DEC-057）：Day 3 部分验证 Manual 受理与治理边界（受理→immutable raw→RECEIVED+PENDING→机械标准化→PARSED+PENDING、真实来源、operator审计、版本保留、PENDING 正式出口不可见）；Day 4 部分验证完整正式链（材料 validation→VALIDATED→VERIFIED/VERIFIED_WITH_NOTICE→PUBLISHED→daily→aggregate）。最终 AT-SRC-007 完整闭环要求不删除、不降低（官方 P0 最终要求保持不变）。
- 步骤：
  1. 输入actualSourceName、itemId、businessDate、value、unit、currency和sourceReference；由认证上下文提供operatorRef，客户端不提交inputAt/receivedAt/updatedAt/accessMethod。
  2. 提交后立即查询面板、API和Agent（Day 3：PENDING 必须对所有正式业务出口不可见）。
  3. （Day 3）执行机械标准化至 `PARSED+PENDING`；不得产生 VERIFIED/PUBLISHED。（Day 4）执行材料校验（D4-T01 validationVersion）、发布（D4-T02）并运行daily（D4-T03）及聚合（D4-T04）。
  4. 提交缺字段/错误数据并检查隔离（Day 3：机械层 fail-closed；Day 4：业务校验 REJECTED/隔离）。
  5. 修订合法记录并检查原raw与版本审计（新版本保留，不覆盖旧raw）。
- 预期：
  1. 系统按总计划7.4的唯一映射写RawReceiptV1：businessDate→sourceBusinessDateRaw/sourceBusinessDate、value→rawValue、unit→rawUnit、currency→rawCurrency，并由服务端生成审计时间、固定manual访问方式和认证operatorRef；独立Lifecycle timeline记录processingStage、validationStatus和updatedAt；缺必填字段或缺失sourceReference不能形成合法候选（Day 3）。
  2. 提交后先写不可变raw，并建立candidate=null的RECEIVED+PENDING LifecycleRecord；机械标准化（manual-material-normalization-v1）后CandidateV1才保存可信businessDate/value/currency/unit；未校验且未发布前所有业务入口不可见；Day 3 合法记录最多 `PARSED+PENDING`。
  3. 只有PUBLISHED+VERIFIED或PUBLISHED+VERIFIED_WITH_NOTICE进入daily、聚合、面板、预警和Agent（Day 4 起生效；最终 P0 要求不变）。
  4. 错误数据REJECTED/隔离（Day 4）；Day 3 机械层失败保留raw并fail-closed；修订不覆盖原raw，保留操作与时间审计（新 runId/新 RECEIVED+PENDING timeline）。
  5. 页面显示实际来源和"手工录入"，不显示成指定商业网站自动数据。
- 证据：表单截图、Manual raw、状态时间线（RECEIVED+PENDING→PARSED+PENDING→Day4 VALIDATED→PUBLISHED）、隔离记录、daily/aggregate、版本审计；存入AT-SRC-007（Day 3 部分与 Day 4 部分分阶段归档）。

#### 阶段子用例（DEC-058 跨阶段 Acceptance 状态模型）

- **AT-SRC-007-D3（Stage=Day 3）**：
  - Parent Case：AT-SRC-007（父用例保持完整端到端语义，当前`NOT_RUN`）。
  - 前置条件：ManualDataProvider 和表单可用；`manual-material-normalization-v1` 规则固定；业务标的已配置。
  - 执行：提交合法材料值，完成 Manual intake→immutable raw→RECEIVED+PENDING→机械标准化→PARSED+PENDING；检查必填来源元数据、operatorRef（认证上下文）、幂等、修订版本保留及 PENDING 正式出口不可见。
  - 预期（Day3范围）：合法记录最多`PARSED+PENDING`；无 VERIFIED/VERIFIED_WITH_NOTICE/PUBLISHED；sourceReference 非空、sourceUrl 可空；same key+same content=IDEMPOTENT_REUSE、same key+different content=NEW_PENDING_VERSION（旧 raw/timeline/operator 审计保留）；PENDING 被既有 Publish Gate 及 PublishedQuery/Daily/Aggregate 拒绝。
  - 不得包含：材料业务validation、VERIFIED、PUBLISHED、daily、aggregate（属 Day4 范围）。
  - 证据：Manual raw、状态时间线、隔离记录、版本审计；存入AT-SRC-007-D3。
  - AcceptanceStatus：`PASS`（Day3 已完整满足自身预期）。
- **AT-SRC-007-D4（Stage=Day 4）**：
  - Parent Case：AT-SRC-007。
  - 范围：完整正式链（材料 validation→VALIDATED→VERIFIED/VERIFIED_WITH_NOTICE→PUBLISHED→daily→aggregate；负责人 D4-T01~D4-T04，DEC-057）。
  - AcceptanceStatus：`NOT_RUN`（未实施）。
- 父用例 AT-SRC-007 仅在所有 mandatory stage subcases（含 AT-SRC-007-D3、AT-SRC-007-D4）PASS 并完成完整 evidence reconciliation 后才允许 `PASS`；Day 3 Gate 只引用 AT-SRC-007-D3。

### AT-SRC-008 来源不可冒充与跨出口一致性

- 对应需求：SRC-04、H02、AI-01。
- 前置条件：FreePublic、Manual、Synthetic测试记录可用；文件、API、UI、预警和EvidencePack可检查。
- 测试数据：数值相同但actualSourceName/providerType/accessMethod不同的三条记录，以及一条故意伪标SMM记录。
- 步骤：
  1. 分别让三条合法记录经过其允许的数据链。
  2. 检查raw、daily、API、Dashboard、预警、EvidencePack和Agent报告。
  3. 尝试通过配置或前端把免费/手工来源改名为SMM或Asian Metal。
  4. 提交故意伪标记录。
- 预期：
  1. 所有出口的actualSourceName、providerType、accessMethod与raw一致。
  2. 免费/手工来源无法冒充指定商业源，Synthetic始终显著标记演示。
  3. 展示名称不能覆盖血缘字段，故意伪标记录被拒绝或标为冲突。
  4. Agent引用实际来源，不根据用户提示改写来源。
- 证据：跨出口字段对账表、拒绝结果、页面/预警/Agent截图、EvidencePack；存入AT-SRC-008。

#### 阶段子用例（DEC-058 跨阶段 Acceptance 状态模型）

- **AT-SRC-008-D3（Stage=Day 3）**：
  - Parent Case：AT-SRC-008（父用例保持完整端到端语义，当前`NOT_RUN`）。
  - 前置条件：Manual、LocalImport、Synthetic 测试记录可用；现有已实现出口（raw、API/PublishedQuery、PENDING 门禁）可检查。
  - 执行：提交故意伪标记录（如 actualSourceName 含 SMM）；执行 LocalImport/Synthetic；检查 providerType/accessMethod 与 actual source 分离、Synthetic 身份与正式隔离。
  - 预期（Day3范围）：进入方式（providerType/accessMethod）与实际依据（actualSourceName/declaredSourceName/sourceReference/sourceUrl）全链分离；Manual/LocalImport 声明来源不得改变 provider identity（伪标 SMM 恒为 MANUAL/LOCAL_IMPORT）；Synthetic 身份恒 SYNTHETIC_DEMO 且正式查询不可见、不自动 fallback；当前已实现出口 source identity 一致。
  - 不得包含：Dashboard、warning、Agent、EvidencePack 完整跨出口对账、Agent 引用来源、daily 级出口对账（属后续阶段范围）。
  - 证据：跨出口字段对账表（已实现出口）、拒绝结果；存入AT-SRC-008-D3。
  - AcceptanceStatus：`PASS`（Day3 已完整满足自身预期）。
- **AT-SRC-008-D4（Stage=Day 4）**：
  - Parent Case：AT-SRC-008。
  - 范围：材料 daily/aggregate 正式出口后的 daily 级来源一致性对账（负责人 D4-T03/D4-T04）。
  - AcceptanceStatus：`NOT_RUN`（未实施）。
- **AT-SRC-008-DX（Stage=后续出口日）**：
  - Parent Case：AT-SRC-008。
  - 范围：Dashboard（D7-T02）、warning（D5-T05）、Agent（D6-T01~T04）、EvidencePack（D6-T02）完整跨出口全量一致性及预期4（Agent 引用实际来源、不按用户提示改写来源）。
  - AcceptanceStatus：`NOT_RUN`（未实施）。
- 父用例 AT-SRC-008 仅在所有 mandatory stage subcases（含 AT-SRC-008-D3、AT-SRC-008-D4、AT-SRC-008-DX）PASS 并完成完整 evidence reconciliation 后才允许 `PASS`；Day 3 Gate 只引用 AT-SRC-008-D3。

### AT-PUB-001 合法数据通过发布门禁

- 对应需求：PUB-01、H02。
- 前置条件：空 data 目录；校验规则版本固定；使用 GD-01。
- 测试数据：GD-01 中一个月的 EUR/CNY 和 AZ91D 合法记录。
- 步骤：
  1. 导入原始 Fixture。
  2. 依次执行解析、字段校验、单位校验、时间校验、去重和发布。
  3. 查询内部状态、业务 API、Dashboard 和 Agent 工具数据。
- 预期：
  1. `ProcessingStage` 按 RECEIVED→PARSED→VALIDATED→PUBLISHED 单向变化；`ValidationStatus` 独立保存，不得合并为一个status字段；raw中不得包含这两个可变字段。
  2. 完整4×5参数化矩阵中仅允许 RECEIVED+PENDING、PARSED+PENDING、RECEIVED+REJECTED、VALIDATED+VERIFIED/VERIFIED_WITH_NOTICE/REJECTED/CONFLICT、PUBLISHED+VERIFIED/VERIFIED_WITH_NOTICE；其他组合全部拒绝。
  3. 业务 API、Dashboard 和 Agent 仅在 PUBLISHED+VERIFIED或PUBLISHED+VERIFIED_WITH_NOTICE 后获得数值。
  4. 发布快照通过其rawRef解析RawReceipt中的payloadSha256，并通过相邻raw manifest解析fileSha256；这两个hash不在timeline重复内联。快照保存校验规则版本，CandidateV1保存标准化版本，runId/acquisitionId提供批次追踪；staging timeline保留全部recordVersion，CandidateV1从PARSED起必填且同一run逐字段不可变。
- 证据：状态迁移日志、RawReceipt、包含CandidateV1的完整staging timeline、quarantine投影、processed/daily CSV、API响应、Dashboard截图、Agent工具调用摘要；存入AT-PUB-001。

### AT-PUB-002 未校验和非法数据不得展示

- 对应需求：PUB-01、H02。
- 前置条件：校验规则启用；Dashboard、业务 API 和 Agent 可访问。
- 测试数据：GD-03 中的空值、负值、币种错误、单位错误、错误日期和超范围记录。
- 步骤：
  1. 分别导入每类异常数据。
  2. 在解析后、校验中和校验失败后查询业务 API。
  3. 打开 Dashboard，并向 Agent 询问对应日期和标的。
- 预期：
  1. 异常数据只可处于RECEIVED+PENDING/REJECTED、PARSED+PENDING或VALIDATED+REJECTED/CONFLICT，绝不进入PUBLISHED。
  2. 业务 API、Dashboard、告警和 Agent 均不返回异常数值。
  3. 页面只显示“待校验、校验失败或来源冲突”等状态和原因。
  4. 超范围记录在人工复核前不得自动发布。
- 证据：quarantine投影、校验错误码、API响应、Dashboard截图、Agent回答、timeline中PUBLISHED快照计数；存入AT-PUB-002。不得创建或引用物理published目录。

### AT-PUB-003 原始篡改、重复与值冲突

- 对应需求：PUB-01、H02、H06。
- 前置条件：一条合法记录已经发布；文件哈希校验启用。
- 测试数据：GD-03 的重复、冲突和篡改样例。
- 步骤：
  1. 重新导入完全相同的业务记录。
  2. 导入相同业务唯一键但值不同的记录。
  3. 修改已登记的原始 Fixture 内容后执行重校验。
- 预期：
  1. 完全重复记录幂等处理，不增加发布记录数。
  2. 值冲突记录进入 CONFLICT，不覆盖已发布值。
  3. 原始哈希不匹配时相关记录失去可发布资格并生成审计事件。
  4. Agent 不得使用冲突或篡改记录。
- 证据：业务记录前后计数、冲突文件、哈希比较、审计日志、Agent响应；存入AT-PUB-003。

### AT-PREC-001 BigDecimal 大数与微小数无损

- 对应需求：H02。
- 前置条件：序列化、持久化、API 和 Vue 均使用十进制字符串作为业务真值。
- 测试数据：GD-02 的 999999999999.123456789、0.000000001 和精确预期 999999999999.123456790。
- 步骤：
  1. 经过字符串读取、`new BigDecimal(String)`、校验、加和、JSON/CSV持久化、API返回和Vue展示全链路。
  2. 重启应用后从文件重新读取并再次展示。
  3. 对比每一层的十进制文本。
- 预期：
  1. 加和结果严格为 999999999999.123456790。
  2. 使用`toPlainString()`，尾随零和声明scale按数据规范保留；不得调用`stripTrailingZeros()`改写业务值。
  3. 不出现科学计数法、最后数位漂移或 JavaScript Number 截断。
  4. 重启前后结果一致。
- 证据：单元/集成测试报告、JSON/CSV、API 原文、Vue 截图、重启前后 diff；存入 AT-PREC-001。

### AT-PREC-002 非终止小数的显式舍入

- 对应需求：H02、H01。
- 前置条件：精度上下文规定calculationVersion=arithmetic-mean-v1、calculationScale=8、displayScale=8、roundingMode=HALF_UP，且configVersion可解析到不可变history快照；系统保存累计值和有效样本数。
- 测试数据：GD-02 的 100.1、100.2、100.2。
- 步骤：
  1. 计算并持久化日累计值和样本数。
  2. 生成并持久化日均avg，再生成API/UI展示值。
  3. 从原始记录重算并与持久化结果比较。
- 预期：
  1. 精确累计值为 300.5，有效样本数为 3。
  2. 展示值为 100.16666667。
  3. sum保持精确300.5；avg仅在除法时按calculationScale舍入，展示仅在API/UI边界按displayScale处理，展示值不回写或参与聚合。
  4. 重算结果一致，且CSV可逐行追溯configVersions、calculationVersion、calculationScale、displayScale、roundingMode和calendarVersion。
- 证据：聚合CSV、计算测试报告、重算diff、数据字典摘录；存入AT-PREC-002。

### AT-PREC-003 禁止对已舍入均值再求平均

- 对应需求：H02、H01。
- 前置条件：日、月、季度、半年和年度聚合可独立重算。
- 测试数据：GD-01 和 GD-02。
- 步骤：
  1. 从底层有效样本生成全部周期结果。
  2. 人为构造“先按展示精度舍入日均/月均，再继续平均”的错误对照结果。
  3. 比较系统结果、正确黄金结果和错误对照结果。
- 预期：
  1. 系统结果匹配黄金结果，不匹配错误对照结果。
  2. 聚合文件保留足以证明权重和重算路径的累计值、计数或等价精确信息。
  3. 不同查询顺序不会改变结果。
- 证据：正确/错误对照表、聚合文件、自动化测试报告；存入 AT-PREC-003。

### AT-AGG-001 日/月/季/半年/年全层级复算

- 对应需求：H01、H02、PER-01。
- 前置条件：使用可控 Clock；空 data 目录；GD-01 校验通过。
- 测试数据：完整 GD-01 EUR/CNY 与 AZ91D。
- 步骤：
  1. 生成每日加工均值。
  2. 生成 12 个月均值。
  3. 生成 Q1-Q4、H1-H2 和年度均值。
  4. 删除内存状态并从 raw、staging元数据和processed/daily文件独立重算；不得依赖不存在的normalized目录。
  5. 比较持久化结果和本文黄金预期。
- 预期：
  1. 每个日均值等于对应目标值。
  2. EUR/CNY 和 AZ91D 的季度、半年、年度结果逐位匹配 GD-01。
  3. 文件重算结果与首次计算完全一致。
  4. daily及月/季/半年/年每个层级均有独立CSV持久化文件。
- 证据：各层级文件、自动化测试报告、黄金 diff、重算日志；存入 AT-AGG-001。

### AT-AGG-002 随机自然月展示

- 对应需求：H01。
- 前置条件：GD-01 全年数据已发布；随机算法和 seed 可记录；Dashboard 可选择标的和月份。
- 测试数据：GD-01；随机候选月份为 2025-01 至 2025-12。
- 步骤：
  1. 生成并记录一个随机 seed。
  2. 从 12 个自然月中选择一个月份。
  3. 分别查询 EUR/CNY 和 AZ91D 的每日加工均值、月均、所在季度、所在半年和年度均值。
  4. 与按 GD-01 公式独立计算的预期比较。
- 预期：
  1. 随机月份和 seed 可复现。
  2. 每日加工均值完整展示。
  3. 月、季度、半年和年度值全部精确匹配。
  4. 页面显示单位、来源、业务周期和校验状态。
- 证据：seed 记录、独立计算表、API 响应、Dashboard 全屏截图；存入 AT-AGG-002。

### AT-AGG-003 缺失日和无效日不污染周期均值

- 对应需求：H01、H02、PUB-01。
- 前置条件：周期聚合规则明确“仅使用已发布有效日”；使用可控 Clock。
- 测试数据：GD-04 的无数据日期和校验失败记录。
- 步骤：
  1. 执行日、月和跨年范围聚合。
  2. 查询周期有效日数、缺失日数和校验失败数。
  3. 对照仅使用有效日的独立计算。
- 预期：
  1. 缺失日不按零值参与平均。
  2. 校验失败日不参与平均。
  3. 结果中明确给出有效样本数和缺失状态。
  4. 系统不插值或生成虚构数据。
- 证据：聚合文件、有效/缺失计数、API 响应、独立计算表；存入 AT-AGG-003。

### AT-FILE-000 D1 文件契约、原子写与双币共享响应

- 对应需求：H02、H03、H04、PUB-01、PER-01。
- 前置条件：D1-T03候选实现可编译；每例显式注入独立临时`supplymind.data-root`；不要求真实联网。
- 测试数据：一份明确标记为`test/contract fixture`且同时包含USD/EUR锚点的完整响应实体bytes（不是D1-T02字段摘录，也不得声称是真实联网响应）；日期缺失fixture；合法/非法路径段；大数、微小数和`100.0`。
- 步骤：
  1. 校验唯一dataRoot及monitor-series完整schema：临时绝对路径、空格/中文路径可用；只读目录、第二dataRoot和ATOMIC_MOVE不支持时启动fail-fast；核对configVersion、itemId唯一/排序、两个冻结PBOC itemId及mode/sourceIntent/providerType/accessMethod/actualSourceName/routeDecision/fallbackReason/routeEffectiveAt/supersedesItemId/externalCode/sourceFieldKey/rateKind/baseCurrency/currency/unit/calculationVersion/calculationScale/displayScale/roundingMode/calendarVersion、生产默认值和条件约束；初始活动配置必须有逐字节相同的`config/history/1.json`及相邻manifest。
  2. 用同一fixture响应创建两个item收据，检查共享acquisitionId及独立runId/rawRef/timeline；按RawReceiptV1验证required/null、configVersion、Provider条件字段、规范派生rawRef、完整payloadBase64/payloadSha256和item级rawValue/matchAnchor。
  3. 对4×5状态组合及所有相邻状态对参数化测试；验证唯一初态、合法迁移边、禁止跳级/回退/自迁移、currentRecordVersion不变量、各状态条件必填、CandidateV1的null/必填/同run不可变，并在连续追加后重启重读timeline。
  4. 对三个失败终态生成QuarantineProjectionV1，核对字段、hash、终态版本及receivedAt分区；确认PENDING、可发布VALIDATED和PUBLISHED不生成quarantine；processed只按已验证businessDate路由。
  5. 拒绝绝对路径、`..`、非法路径段及调用方伪造rawRef；模拟raw同hash重放和异hash碰撞，核对正常raw不变且完整incoming只进入冻结的runtime/conflicts/raw证据路径。
  6. 对新建raw、可变timeline及其manifest分别注入业务tmp写入中断、业务文件已提交但manifest未提交、旧manifest hash、manifest孤立、Windows文件占用、孤立tmp/bak和重启恢复；逐项校验DirtyMarkerV1的transactionType/transactionPhase/markerRevision/targets[]/role/targetPhase矩阵。对marker自身分别在“下一revision tmp已force”“canonical已移到marker.bak”“tmp已移到canonical”三个窗口断电，验证从合法canonical/tmp/bak候选组选择最高单调revision并恢复canonical；同一最高revision异字节、revision跳号/回退、不可变字段漂移或候选全无效必须保留证据并fail closed，普通无marker孤立tmp/bak仍不得自动采用。另对配置变更的history数据/manifest与活动配置数据/manifest逐窗口断电，验证CONFIG_HISTORY先于CONFIG_ACTIVE、活动manifest为激活点、未激活快照受控恢复、已激活快照永不删除，且dirty marker及其候选仅在最终对账成功后清除。
  7. 核对相邻`<完整业务文件名>.manifest.json`、禁止递归manifest、fileSha256与payloadSha256分离、固定tmp/bak命名及fail-closed恢复。
  8. 检查BigDecimal字符串往返、toPlainString、尾零和无科学计数法；对daily/aggregate固定表头、规范数据行排序、validationStatus/validationVersion、qualityStatus与complete对应关系、configVersions、calculationVersion/calculationScale/displayScale/roundingMode/calendarVersion、可唯一定位daily行且在LifecycleTimeline schema v1中固定指向PUBLISHED recordVersion=4的RFC 4180 inputRefs及sourceFingerprint确定性向量执行codec测试；随机打乱输入后CSV字节与fileSha256必须不变。
  9. 对照schema数据字典和追加式CALCULATION-RULES运行活动配置/history、raw、timeline/Candidate、quarantine、manifest、daily/aggregate全部合法/非法黄金文件；审计依赖树无任何数据库栈。
- 预期：
  1. 两个item不竞争raw或staging文件，且可追溯到同一次acquisition。
  2. raw不含processingStage/validationStatus；timeline保存全部版本与CandidateV1，candidate的null/必填/不可变规则和非法组合全部被强制执行；仅凭重启后的PUBLISHED timeline即可恢复daily所需候选字段。
  3. 日期缺失仍能先保存raw；不得用未验证业务日期路由processed。
  4. raw永不被替换；异hashincoming有完整独立冲突证据；不存在半文件、路径穿越、第二dataRoot、非原子静默降级或哈希混用。
  5. JSON/CSV编码、`data/config/monitor-series.json`、不可变`data/config/history/<configVersion>.json`和PBOC生产默认值符合总计划8.4.5；双币`baseCurrency/currency（语义等于quoteCurrency）/unit`映射唯一，运行JSON不存在额外quoteCurrency字段；config/raw/lifecycle/quarantine/manifest为JSON，daily/aggregate预定codec为CSV；多输入inputRefs不退化成单一rawRef。
  6. data+manifest两文件、DirtyMarkerV1自身原子替换及配置targets[]两项所覆盖四个物理文件的事务任一中断均可确定性完成或回退；markerRevision单调且歧义候选fail closed；RawReceipt.configVersion总能解析到已生效不可变快照；损坏或来源不明文件不会被业务读取；manifest不为自身生成manifest。
  7. schema/计算规则数据字典与合法/非法黄金文件逐字段一致；CSV逐行保存可复算的配置和计算上下文；依赖树不含MyBatis/JPA/JDBC/R2DBC或MySQL/Redis/SQLite/H2等驱动/服务。
- 证据：schema/路径/状态迁移矩阵/CandidateV1/QuarantineProjectionV1/配置history与targets[]两项（四个物理文件）原子提交恢复/精度/codec/依赖测试报告、双币文件树、冲突证据、timeline、dirty marker与manifest样例；存入AT-FILE-000。

### AT-FILE-001 程序 data 目录直接可见 JSON/CSV

- 对应需求：H03、PER-01。
- 前置条件：最终候选程序已完成至少一次采集和全部层级聚合。
- 测试数据：GD-01 的一个季度。
- 步骤：
  1. 从应用“打开数据目录”入口或文档指定路径进入 data 目录。
  2. 检查唯一物理目录：config/monitor-series.json、config/history/<configVersion>.json、raw、staging、quarantine、processed/daily、processed/aggregate/<itemId>/{month,quarter,halfyear,year}、warning、report、runtime/jobs/{active,history}、runtime/dirty与runtime/conflicts/raw；确认monitor-series是唯一活动配置、history只含不可变已生效快照，conflicts/raw仅有异hash诊断证据且不被业务查询读取。
  3. 使用普通文本编辑器打开业务文件。
- 预期：
  1. 用户无需数据库工具即可看到 JSON/CSV。
  2. JSON与CSV文件均为UTF-8；daily/aggregate CSV符合固定表头，字段名、单位、业务时间和精确小数可读。
  3. 所有业务真值均位于 data 目录，不隐藏在 Electron LocalStorage、IndexedDB 或二进制数据库中。
  4. 缓存目录即使存在也不承载业务真值。
- 证据：完整目录树、文件类型清单、代表性文件副本和文本编辑器截图；存入 AT-FILE-001。

### AT-FILE-002 全层级持久化与重启恢复

- 对应需求：H03、PER-01、H01。
- 前置条件：GD-01 全部层级已经生成；记录当前文件哈希。
- 测试数据：GD-01。
- 步骤：
  1. 记录日、月、季度、半年、年度文件及哈希。
  2. 正常关闭并重新启动最终应用。
  3. 不重新导入 Fixture，直接查询所有周期。
  4. 比较重启前后 API、Dashboard 和文件。
- 预期：
  1. 重启后所有周期可查询。
  2. 数值、样本数、来源和业务周期不变。
  3. 重启不会重复写入相同记录。
  4. 必要检查点使用可审计JSON；系统不创建数据库索引。
- 证据：重启前后文件哈希、API diff、启动日志、Dashboard 截图；存入 AT-FILE-002。

### AT-TIME-001 可控 Clock 前跳轮转自动测试

- 对应需求：H05、H01、PER-01。
- 前置条件：自动化测试使用注入 Clock；不修改宿主系统时间；独立 data 目录。
- 测试数据：边界时刻 23:59:50→次日、月末→次月、03-31→04-01、06-30→07-01、12-31→01-01、闰年 02-28→02-29→03-01。
- 步骤：
  1. 在每个边界前写入一条已校验记录。
  2. 将 Clock 前跳到边界后。
  3. 触发调度、采集、校验和持久化。
  4. 检查文件名、目录、聚合归属和任务次数。
- 预期：
  1. 新周期自动创建正确轮转文件。
  2. 旧周期文件不被覆盖。
  3. 新记录按业务时间进入正确周期。
  4. 调度重复触发由业务幂等键消除。
- 证据：自动化报告、每个边界前后文件树、任务日志、记录计数；存入 AT-TIME-001。

### AT-TIME-002 可控 Clock 回拨自动测试

- 对应需求：H05、H06、H02。
- 前置条件：可控 Clock；已经生成边界后的文件；业务幂等键启用。
- 测试数据：01-01 00:05 回拨至上一年 12-31 23:55；07-01 回拨至 06-30；当天 12:00 回拨至 10:00。
- 步骤：
  1. 在回拨前写入并发布记录。
  2. 回拨 Clock。
  3. 重新触发同一业务记录和一条新的旧周期记录。
  4. 查询文件和跨周期结果。
- 预期：
  1. 回拨不覆盖已经存在的新周期文件。
  2. 相同业务记录不重复发布。
  3. 合法的新旧周期记录进入其业务日期所属文件。
  4. 查询结果仍按业务时间排序，而不是按写入顺序排序。
- 证据：自动化报告、回拨前后目录 diff、去重日志、排序后的 API 响应；存入 AT-TIME-002。

### AT-TIME-003 专用 Windows/VM 物理系统时间前跳

- 对应需求：H05、WIN-01。
- 前置条件：专用 Windows/VM 快照已创建；关闭自动对时；安装最终 Electron EXE；使用离线 Fixture，避免外部 TLS 干扰。
- 测试数据：至少覆盖月末、06-30→07-01 和 12-31→01-01 三个物理时间边界。
- 步骤：
  1. 启动 EXE，记录系统时间、应用时间和当前文件树。
  2. 在边界前触发一次写入。
  3. 通过 Windows 系统设置或受控管理命令把物理时间前跳到边界后。
  4. 保持应用运行并触发下一次写入。
  5. 重启 EXE，再次查询轮转前后数据。
- 预期：
  1. 最终 EXE 观察到真实系统时间变化。
  2. 自动创建日/月/半年/年度正确文件。
  3. 旧文件不覆盖，重启后仍可查询。
  4. 日志时间变化不会导致应用崩溃或无法继续写日志。
- 证据：连续录屏、改时前后 Get-Date 输出、文件树、应用日志、重启后 Dashboard 截图；存入 AT-TIME-003。

### AT-TIME-004 专用 Windows/VM 物理系统时间回拨

- 对应需求：H05、H06、WIN-01。
- 前置条件：专用 Windows/VM；自动对时关闭；最终 EXE 已运行并生成新周期文件；使用离线 Fixture。
- 测试数据：01-01 00:05 回拨至上一年 12-31 23:55，并再次触发相同业务键。
- 步骤：
  1. 记录回拨前进程、系统时间、文件哈希和记录计数。
  2. 物理回拨系统时间。
  3. 触发一次重复记录和一次合法旧周期新记录。
  4. 正常退出并重启 EXE。
  5. 恢复 VM 快照。
- 预期：
  1. 已有新年文件不被清空或覆盖。
  2. 重复业务键不会二次发布。
  3. 合法旧周期新记录写入正确文件。
  4. Electron 和 Spring Boot 不因时间回拨陷入重启循环。
  5. 重启后跨年查询正确。
- 证据：连续录屏、系统时间截图、文件哈希/计数前后对比、进程日志、跨年 API 响应；存入 AT-TIME-004。

### AT-XR-001 多轮转文件跨年拼接

- 对应需求：H06、H01、H02。
- 前置条件：2025 和 2026 分区文件均存在；使用 GD-04；所有合法记录已经发布。
- 测试数据：GD-04。
- 步骤：
  1. 查询 2025-12-20 至 2026-01-10。
  2. 查询同范围的日级明细和跨周期聚合。
  3. 将结果与黄金清单比较。
- 预期：
  1. 系统自动读取多个历史轮转文件。
  2. 仅返回四条唯一已发布记录。
  3. 结果按业务时间升序。
  4. 无数据日期保留缺口。
  5. 校验失败记录不参与聚合。
- 证据：参与拼接的文件清单、API 响应、黄金 diff、Dashboard 截图；存入 AT-XR-001。

### AT-XR-002 跨文件重复、乱序和损坏隔离

- 对应需求：H06、H02、H03。
- 前置条件：两个年度文件均可读；准备一份重复记录、一份乱序记录和一个损坏但可隔离的非目标分区文件。
- 测试数据：GD-04，加一个语法损坏的独立历史文件。
- 步骤：
  1. 执行跨年查询。
  2. 检查重复消除和排序。
  3. 查询损坏文件覆盖的范围及不覆盖该文件的范围。
- 预期：
  1. 重复记录按业务唯一键去重。
  2. 乱序写入不影响业务时间排序。
  3. 损坏文件进入隔离并产生明确错误。
  4. 不依赖损坏文件的其他历史范围仍可查询。
  5. 系统不静默吞掉损坏造成的数据缺口。
- 证据：损坏文件副本、隔离日志、API 响应、去重前后计数、错误页面截图；存入 AT-XR-002。

### AT-CFG-001 动态停用欧元

- 对应需求：H07、H09。
- 前置条件：EUR/CNY 已启用且存在历史数据；最终应用正在运行。
- 测试数据：GD-05 初始配置和至少一个月 EUR/CNY 历史。
- 步骤：
  1. 在配置界面停用 EUR/CNY。
  2. 不重启应用，等待一个调度周期。
  3. 查询 Dashboard、任务列表和历史接口。
  4. 重启应用再次检查。
- 预期：
  1. 停用不需要修改代码或重启。
  2. 停用后不再创建新的 EUR/CNY 采集任务。
  3. EUR/CNY 从默认当前面板隐藏。
  4. 旧历史文件不删除，显式历史查询仍可获得。
  5. 重启后停用状态保持。
- 证据：配置前后 JSON、调度日志、Dashboard 截图、历史 API 响应、data 目录 diff；存入 AT-CFG-001。

### AT-CFG-002 动态新增 GBP 并获取当日数据

- 对应需求：H07、H08、H09。
- 前置条件：GBP/CNY 不存在；GBP 来源合法可访问；最终应用正在运行；当前业务日期已知。
- 测试数据：GD-05 的 GBP 当日数据；外部验收时使用实际官方当日记录。
- 步骤：
  1. 通过配置界面新增 GBP/CNY 并启用。
  2. 不重启应用，观察任务创建和即时采集。
  3. 在采集后、校验前和发布后分别查看 Dashboard/API。
- 预期：
  1. 新增不修改代码、不重启。
  2. 系统立即或按书面约定时间获取当日数据。
  3. 校验前不显示数值，发布后 GBP 出现在面板。
  4. 当日日均和后续周期计算自动建立。
  5. 来源无当日数据时显示“当日缺失”，不得使用上一日冒充。
- 证据：配置变更审计、任务日志、当日 raw、LifecycleRecord与processed/daily文件、三个状态时点截图、官方记录对比；存入 AT-CFG-002。

### AT-CFG-003 GBP 历史回填、均值计算与重启恢复

- 对应需求：H08、H01、H03。
- 前置条件：AT-CFG-002 通过；历史窗口已由需求方确认；来源允许自动获取该历史范围。
- 测试数据：GD-05 的 GBP 历史批次；外部验收使用授权来源真实历史。
- 步骤：
  1. 新增 GBP 后观察自动创建的历史回填任务。
  2. 在回填中途正常关闭并重启应用。
  3. 等待回填恢复并完成。
  4. 再次提交相同历史回填请求。
  5. 查询日、月、季度、半年和年度结果。
- 预期：
  1. 回填状态和进度可见。
  2. 重启后从检查点恢复，不从头重复发布。
  3. 重复回填请求幂等。
  4. 已校验批次可发布，失败批次有明确原因和重试入口。
  5. 全层级均值自动生成并写入daily/aggregate CSV。
- 证据：回填任务状态、重启前后检查点、文件计数、去重日志、全层级 API 和文件；存入 AT-CFG-003。

### AT-CFG-004 动态替换 AZ91D

- 对应需求：H07、H08、H09、SRC-02、SRC-03。
- 前置条件：SMM与Asian Metal两个来源意图下的AZ91D均已启用并有独立历史；两个MAT-REPL-01新itemId已指定；各自合法指定源、FreePublic或Manual路线已通过能力评审。
- 测试数据：GD-05的两条AZ91D历史和两条MAT-REPL-01当日/历史数据；Manual路线时分别准备合规输入批次。
- 步骤：
  1. 在界面把SMM意图和Asian Metal意图下的AZ91D两条序列同时替换为各自对应的MAT-REPL-01序列，ADC12两条序列不变。
  2. 不重启，观察旧任务停用和新路线任务创建；核对新configVersion的不可变history快照、活动配置及两份manifest按原子激活协议一致，旧快照仍可读取。
  3. 自动路线等待当前采集与历史回填；Manual路线确认状态为AWAITING_MANUAL_INPUT，再提交当前/历史数据。
  4. 对新数据执行标准化、校验、加工和聚合。
  5. 查询AZ91D旧历史和新材料历史。
- 预期：
  1. 替换不修改代码、不重启。
  2. 两条AZ91D来源意图序列停止未来采集但历史文件不删除。
  3. 两条新材料序列分别按各自选定路线获得当日与历史；Manual不会伪装自动完成。
  4. 新数据在PENDING阶段不可见；只有PUBLISHED+VERIFIED或PUBLISHED+VERIFIED_WITH_NOTICE后才显示。
  5. 新材料所有层级均值自动生成且显示实际来源。
- 证据：替换前后活动配置与history快照、四条独立routeDecision、任务/Manual状态、两条旧AZ91D/两条新MAT-REPL-01及保持不变的两条ADC12文件树与哈希、Dashboard截图；存入AT-CFG-004。

### AT-UI-001 配置变化后的面板重构

- 对应需求：H09、H07。
- 前置条件：初始面板显示EUR/CNY、USD/CNY，以及SMM来源意图×ADC12/AZ91D、Asian Metal来源意图×ADC12/AZ91D四条材料序列；依次执行停用欧元、新增GBP、同时替换两条AZ91D来源意图序列。
- 测试数据：GD-05。
- 步骤：
  1. 记录初始面板组件、图例、筛选项和请求。
  2. 完成三项配置变化。
  3. 不刷新整个应用，观察面板重构。
  4. 再手动刷新页面并重启 EXE。
- 预期：
  1. EUR/CNY及SMM/Asian Metal两个来源意图下的AZ91D共三条序列从默认当前视图隐藏；USD/CNY保持显示。
  2. GBP/CNY及SMM/Asian Metal两个来源意图下各自对应的MAT-REPL-01仅在已发布后显示；两条ADC12序列保持显示。
  3. 替换前四条、替换后两条ADC12加两条MAT-REPL-01的材料卡片/图例均按itemId独立，不因材料名称相同而合并来源意图。
  4. 不出现空图例、重复卡片、未处理异常、无限加载或错误单位。
  5. 刷新和重启后的布局与配置一致。
  6. 历史查询入口仍能选择已停用旧项。
- 证据：变更前后截图/录屏、浏览器控制台错误导出、网络请求摘要、重启后截图；存入 AT-UI-001。

### AT-UI-002 旧监测项隐藏但历史不删除

- 对应需求：H09、H03、H06。
- 前置条件：AT-CFG-001 和 AT-CFG-004 已执行；记录停用前文件哈希。
- 测试数据：EUR/CNY，以及SMM/Asian Metal两个来源意图下两条AZ91D旧历史。
- 步骤：
  1. 检查默认 Dashboard。
  2. 使用历史查询显式选择停用的EUR/CNY及两个来源意图下的两条AZ91D。
  3. 检查 data 目录及文件哈希。
  4. 重装或升级同版本应用后再次查询。
- 预期：
  1. 旧项不占用当前监测面板。
  2. 旧项仍可在历史查询中选择。
  3. 历史文件数量和哈希不因停用/替换变化。
  4. 重装/升级不删除旧历史。
- 证据：Dashboard 和历史页截图、停用前后目录 diff、哈希清单、重装后 API 响应；存入 AT-UI-002。

### AT-ALT-001 确定性预警与未校验隔离

- 对应需求：ALT-01、H02、PUB-01。
- 前置条件：预警阈值、比较周期和舍入规则固定；已有上一周期已发布基准。
- 测试数据：一条刚好低于阈值、一条刚好等于阈值、一条刚好高于阈值，以及一条同值但未校验的数据。
- 步骤：
  1. 分别发布三条合法边界数据。
  2. 导入未校验异常数据。
  3. 查看预警列表、持久化文件和 Agent 解释。
- 预期：
  1. 预警是否触发与书面阈值包含关系完全一致。
  2. 比较使用精确十进制，不受展示舍入影响。
  3. 未校验数据不触发预警。
  4. 预警保存标的、当前值、基准值、规则版本、来源和业务周期。
  5. Agent 解释与确定性预警记录一致。
- 证据：阈值配置、四组输入、预警JSON、Dashboard截图、Agent回答；存入AT-ALT-001。

### AT-ALT-002 预警确认 sidecar 与重启恢复（DEC-061）

- 对应需求：ALT-01、F10、H02。
- 前置条件：AT-ALT-001 至少一条预警已持久化（`warning/YYYY-MM/<warningId>.json` 与 manifest）。
- 测试数据：一条合法预警、一条重复确认请求、一条不同处置备注的冲突确认请求。
- 步骤：
  1. 查询预警列表与详情（真实 from/to 范围）。
  2. 提交确认（dispositionNote）；重复提交同内容；提交不同内容。
  3. 核对原 warning 文件与 ack sidecar 文件字节。
  4. 重启应用后再次查询确认状态。
- 预期：
  1. 确认后 `warning/YYYY-MM/<warningId>.ack.json` + manifest 存在且 verified；原 warning 文件逐字节不变。
  2. 同内容重复确认幂等（不产生第二个 sidecar、不报错）。
  3. 同 warningId 不同确认内容 fail-closed（400 REJECTED）。
  4. dispositionNote 为空/超长/含路径或分隔符被拒绝（400）。
  5. 未知 warningId 确认被拒绝（400 unknown warningId）。
  6. 重启后 ACKNOWLEDGED 状态保持（sidecar manifest 可验证）。
  7. 预警列表查询只识别 `<warningId>.json`，不把 ack/manifest 文件当预警记录。
- 证据：sidecar JSON、manifest、原 warning 前后 SHA-256、冲突/非法请求响应、重启后查询；存入AT-ALT-002。

### AT-NET-001 断网下的本地历史与采集失败

- 对应需求：H02、H03、WIN-01、SRC-01、SRC-02。
- 前置条件：GD-06 本地历史已发布；最终 EXE 正常运行；随后断开网络。
- 测试数据：GD-06。
- 步骤：
  1. 断网前查询一整年历史。
  2. 断开网络并等待已配置的自动Provider（OfficialWeb、AuthorizedApi或FreePublic）调度触发；Manual不应产生网络任务。
  3. 再次查询本地历史、聚合和预警。
  4. 恢复网络并观察后续任务。
- 预期：
  1. 断网不影响已发布本地历史查询。
  2. 新采集任务明确失败或等待重试，不生成虚构数据。
  3. Dashboard 可显示最后已校验值，但明确业务日期和“过期/来源不可用”。
  4. 恢复网络后按策略恢复采集，不重复发布。
- 证据：断网前后 API、Dashboard 截图、任务日志、网络状态和恢复后计数；存入 AT-NET-001。

### AT-AI-000 Framework Compatibility / Upgrade Gate

- 对应需求：C35、DEC-060、D6-T00。
- 前置条件：固定基线为 `day5-complete` / `36dc178`，Java 17、Spring Boot 3.3.6；历史完整回归为 83 suites、407 tests、0 failures、0 errors、8 skipped。
- 步骤：
  1. 仅将框架基线升级为 Spring Boot 3.5.15、Spring AI 1.1.8，生成依赖树并检查无Boot 4.x、Spring AI 2.x、预发布版本和数据库栈。
  2. 从干净构建执行 Day 1～Day 5 完整回归，逐项核对原有测试类、测试用例与skip原因。
  3. 对比升级前后业务文件黄金字节、manifest、BigDecimal结果、调度、validation、publish与warning合同。
  4. 模拟拒绝条件并验证可回退至固定基线，无数据迁移和历史改写。
- 预期：全部既有语义和合同无回归；测试数量变化有逐项证据且无核心测试静默skip；若需大规模生产重写或无法兼容，Gate=FAIL并拒绝升级。
- 证据：精确版本与依赖树、完整Surefire原始结果、测试清点、黄金字节diff、升级/回退报告；存入AT-AI-000。

### AT-AI-001 Cloud LLM / Spring AI Adapter 故障降级为 Java 模板报告

- 对应需求：AI-01、H02、PUB-01、C12、C13、C36。
- 前置条件：本地已有已发布数据；SupplyMind `LLMService`与Spring AI adapter已接入；Java模板报告可独立运行。
- 测试数据：缺失API Key、DNS失败、超时、429、5xx、畸形响应、空响应、非法tool request；一个涉及未校验数据的提问。
- 步骤：
  1. 使用本地stub按同一端口完成成功响应合同，不要求真实API Key。
  2. 逐项注入上述故障并生成风险报告，同时执行采集、查询、聚合和预警。
  3. 询问涉及未校验数据的问题并检查所有业务出口。
  4. 若另行执行真实Cloud gated run，固化runner证据；没有凭据时保持AcceptanceStatus=NOT_RUN或BLOCKED，不得宣称PASS。
- 预期：每类故障明确标记degraded，由Java模板基于同一EvidencePack生成结构化报告；核心链继续工作；未校验数据不可见；秘密不进入前端、URL、日志、EvidencePack或证据。
- 证据：本地合同与故障注入runner、模板报告、核心API结果、脱敏配置检查；真实Cloud如执行则另存gated runner；存入AT-AI-001。

### AT-AI-002 Spring AI 只读 Tool Calling 与生产 Service 复用

- 对应需求：C20、C21、C36、D6-T01。
- 前置条件：D6-T00=Done；七个冻结Tool Adapter可由本地确定性ChatModel stub选择。
- 步骤：逐一选择 `series.resolve`、`history.query`、`period.metrics`、`quality.inspect`、`cost.impact`、`warning.explain`、`provenance.trace`；注入越权参数、提示注入、未发布/未校验引用和未知工具名。
- 预期：仅Tool Adapter声明`@Tool`或稳定`ToolCallback`；应用层校验并执行既有只读Service；只读PUBLISHED+VERIFIED类数据；不得访问任意文件、网络、数据库、配置写、回填写、预警状态写或shell；非法请求fail-closed并降级。
- 证据：工具注册表、input/output DTO、调用trace、权限负向测试、Service复用证明；存入AT-AI-002。

### AT-AI-003 EvidencePack 追溯与 AgentReport 持久化

- 对应需求：C20、C22、C36、D6-T02、D6-T04。
- 前置条件：`AGENT-EVIDENCE-SCHEMA-V1`已冻结；存在已发布可追溯fixture。
- 步骤：生成EvidencePack与LLM/Java模板两种AgentReport；逐项核对tool input/output、精确数值字符串、businessDate/period、来源、质量、warning、版本、file/source refs和lineage；篡改或删除引用后重放核验。
- 预期：EvidencePack与AgentReport由SupplyMind拥有且不含Spring AI DTO；模型记忆、对话历史和tool transcript不能替代证据；数字/结论必须可回指；引用失效或篡改fail-closed；报告按冻结路径和manifest规则原子持久化。
- 证据：schema黄金文件、EvidencePack、AgentReport、manifest、篡改负向结果、重启读取记录；存入AT-AI-003。

### AT-WIN-001 干净 Windows 解压并启动便携 Electron EXE

- 对应需求：WIN-01、H03、H04。
- 前置条件：干净 Windows x64 VM；最终候选便携 ZIP 和 SHA-256；未预装 Java、Node、Docker 或数据库；准备可写测试目录。
- 测试数据：便携 ZIP、离线 GD-01、最小非敏感配置。
- 步骤：
  1. 记录解压前程序、进程、服务和环境基线。
  2. 将 ZIP 解压到可写目录（含空格或中文路径），双击 SupplyMind AI EXE。
  3. 在断网状态首次启动，检查程序根目录中的 data 与 logs。
  4. 导入离线 Fixture 并完成查询。
  5. 关闭并重新启动应用。
- 预期：
  1. 无需安装 Java、Node、Maven、Docker 或数据库即可启动。
  2. Electron 使用随包 JRE 启动 Spring Boot。
  3. Vue 页面正常加载，离线历史可查询。
  4. data 和 logs 位于便携程序根目录、可直接检查；目录不可写时启动前明确阻止并提示。
  5. 重启后数据仍存在。
- 证据：VM 基线、解压/启动录屏、进程路径、应用版本、程序根目录与 data 文件、重启前后截图；存入 AT-WIN-001。

### AT-WIN-002 Electron 单实例、动态端口与子进程生命周期

- 对应需求：WIN-01、H04。
- 前置条件：最终 EXE 已安装；本机有一个常用固定端口被其他程序占用。
- 测试数据：无业务数据要求。
- 步骤：
  1. 启动应用并记录 Electron、Java PID 和实际 loopback 端口。
  2. 再次双击 EXE。
  3. 正常关闭主窗口。
  4. 再次启动，并强制结束 Electron 主进程。
  5. 等待父进程看门狗，再检查 Java 进程和数据锁。
- 预期：
  1. 第二次启动只聚焦已有窗口，不创建第二个 Spring Boot。
  2. 后端使用动态 loopback 端口，不与已占用固定端口冲突。
  3. 正常关闭后对应 Java 子进程退出。
  4. Electron 被强制结束后，Java 在约定看门狗时间内退出。
  5. 下一次启动可正常获得数据目录锁。
- 证据：进程树、端口列表、单实例录屏、正常/异常退出日志、数据锁状态；存入 AT-WIN-002。

### AT-OPS-001 无数据库进程、依赖和业务存储

- 对应需求：H04、H03、WIN-01。
- 前置条件：干净 Windows VM；最终便携 ZIP、后端依赖清单和解压后的发布目录可检查。
- 测试数据：GD-01，用于产生完整业务数据。
- 步骤：
  1. 检查后端构建依赖中是否含MyBatis、JPA、JDBC、R2DBC、MySQL、Redis、SQLite、H2或其他数据库驱动/服务器。
  2. 检查便携 ZIP 和解压目录中是否含数据库二进制。
  3. 启动应用前、中、后分别记录进程、服务、子进程树和监听端口。
  4. 完成采集、聚合、查询后搜索业务 data 目录中的文件类型。
  5. 清理 Electron cache 后再次查询业务数据。
- 预期：
  1. 无MyBatis、JPA、JDBC、R2DBC、MySQL、Redis、SQLite、H2或其他业务数据库依赖、驱动和服务。
  2. 运行中只有 Electron 相关进程及一个随包 Java 后端，不出现数据库进程。
  3. 不监听数据库默认或自定义服务端口。
  4. 所有业务真值均为 data 目录中的 JSON/CSV。
  5. Electron/Chromium 可能存在的内部缓存不承载业务真值；清缓存不影响业务历史。
- 证据：依赖清单、便携目录清单、三阶段进程/服务/端口快照、data 文件类型清单、清缓存前后查询；存入 AT-OPS-001。

### AT-OPS-002 便携 data 目录可见性、整体迁移与备份恢复

- 对应需求：H03、H09、WIN-01。
- 前置条件：最终便携目录已产生 GD-01 和动态配置数据；记录程序根目录、data 目录和哈希。
- 测试数据：GD-01、GD-05。
- 步骤：
  1. 确认 data 位于 EXE 同级程序根目录且可直接打开。
  2. 关闭应用并复制整个便携目录到另一个可写位置。
  3. 从新位置双击 EXE，再次查询历史与动态配置。
  4. 单独备份 data 目录，修改测试数据后关闭应用。
  5. 按文档恢复 data 备份并重新启动。
- 预期：
  1. 业务真值位于 EXE 同级可见 data 目录，不以 Electron userData、缓存或隐藏数据库为规范存储。
  2. 整个便携目录迁移后无需重新安装或改代码即可运行。
  3. 迁移与重启不删除业务历史和动态配置。
  4. 备份恢复后历史、配置和聚合可读取。
  5. data 目录中业务文件仍为 UTF-8 JSON/CSV。
- 证据：两处便携根目录截图、哈希清单、迁移录屏、恢复前后 API 和 Dashboard；存入 AT-OPS-002。

## 8. 10 天验收执行路线与每日退出条件

| 天 | 验收准备/执行重点 | 当日退出条件 |
|---|---|---|
| Day 1 | 补充说明、PBOC字段与真实获取、raw JSON | D1-T02补证完成不等于Day1退出；**Day1退出仅以**EUR/CNY、USD/CNY真实OfficialWeb采集均生成可追溯raw为准，失败证据不得通过门禁 |
| Day 2 | PBOC标准化、校验、PUBLISHED+VERIFIED类、daily/aggregate CSV、历史与聚合 | AT-SRC-002 PASS；两个币种重启后可读；任一币种未到daily持久化不得退出 |
| Day 3 | 六类Provider、材料三层路由、FreePublic、Manual、LocalImport/Synthetic | AT-SRC-001=PASS、AT-SRC-005-D3=PASS、AT-SRC-008-D3=PASS（DEC-058 阶段子用例；父用例 AT-SRC-005/008 保持`NOT_RUN`至后续阶段完成）；AT-SRC-007-D3=PASS（Manual 保底路线）；四个来源意图×材料序列各有non-synthetic路线；指定源不可用仅条件N/A（AT-SRC-006=`BLOCKED`，EXT-10 非阻断） |
| Day 4 | 全Provider校验门禁、加工、五级聚合和来源治理 | AT-PUB、AT-PREC、AT-AGG及选定AT-SRC-006/007通过；GD-01至GD-07均有SHA-256 |
| Day 5 | 轮转、跨文件/跨年、动态配置、回填和预警 | AT-TIME、AT-XR、AT-CFG、AT-ALT后端路径通过 |
| Day 6 | AT-AI-000框架升级Gate、七个只读Tool Adapter、SupplyMind EvidencePack、LLMService+Spring AI adapter和Java模板降级 | AT-AI-000、AT-AI-002、AT-AI-003本地合同通过；工具不重算业务值；报告引用actualSourceName；Cloud真实gated run未执行时保持NOT_RUN/BLOCKED，故障仍产出模板报告 |
| Day 7 | Vue仪表盘、历史、质量、手工录入与导入页面 | Manual提交先PENDING；真实来源跨页面一致；不显示未校验数据 |
| Day 8 | 动态配置、预警、Agent与Web预验收 | H01-H09、SUP-01至SUP-08的Web侧P0通过；功能冻结 |
| Day 9 | Electron、捆绑JRE、动态端口、生命周期和便携ZIP | 桌面冒烟通过；data在EXE同级可见；无数据库和外部运行时 |
| Day 10 | 干净Windows、物理改时、故障恢复、三层路线和全量回归 | PBOC硬门PASS；四个来源意图×材料序列认可路线PASS；商业自动N/A不全局阻塞；证据、ZIP和文档完整 |

## 9. 最终发布验收清单

### 9.1 数据来源与发布

- [ ] PBOC EUR/CNY、USD/CNY按配置真实自动获取，并完成raw/lifecycle JSON、PARSED/PENDING、VALIDATED、PUBLISHED+VERIFIED类状态、daily/aggregate CSV和重启读取。
- [ ] SMM/Asian Metal两个来源意图×ADC12/AZ91D四条独立序列各有routeDecision/fallbackReason，严格按合法指定源自动→FreePublic→Manual选择。
- [ ] SMM/Asian Metal合法自动能力可用时对应AT-SRC-003/004为PASS；不可合法获取时有N/A_APPROVED_FALLBACK证据。
- [ ] 四个来源意图×材料序列各有指定源自动、AT-SRC-006 FreePublic或AT-SRC-007 Manual中的一条非synthetic路线PASS。
- [ ] FreePublic保存真实网站名称、URL/引用和字段映射；Manual保存全部必填字段和版本审计。
- [ ] 免费/手工来源没有被标记为SMM或Asian Metal，Synthetic没有被当作真实来源。
- [ ] 每条发布记录可追溯到raw、哈希、业务时间、获取/输入时间、实际来源和校验规则。
- [ ] 只有PUBLISHED+VERIFIED或PUBLISHED+VERIFIED_WITH_NOTICE进入API、Dashboard、预警或Agent；PENDING、冲突、篡改和异常待复核数据均不可进入。
- [ ] 没有绕过登录、验证码、会员或反爬；来源切换不静默发生、不沿用旧标签。

### 9.2 精度与聚合

- [ ] BigDecimal 大数和微小数全链路无未声明精度损失。
- [ ] 非终止小数的累计值、样本数、scale 和舍入模式可审计。
- [ ] 不对已经舍入的展示均值继续求平均。
- [ ] EUR/CNY 和 AZ91D 的日/月/季/半年/年结果与 GD-01 逐位一致。
- [ ] 随机自然月测试可通过记录 seed 复现。
- [ ] 缺失日和无效日不按零值参与平均。
- [ ] raw/lifecycle等按契约持久化为JSON，daily及四级aggregate持久化为CSV，并可重启后读取。

### 9.3 轮转与跨年

- [ ] 可控 Clock 前跳和回拨自动测试通过。
- [ ] 专用 Windows/VM 物理系统时间前跳和回拨验收通过。
- [ ] 跨日、月、季度、半年、年时创建正确轮转文件。
- [ ] 时间回拨不覆盖文件、不重复发布。
- [ ] 2025→2026 多文件读取、拼接、去重和排序正确。
- [ ] 损坏文件可隔离，未受影响历史仍可查询。

### 9.4 动态配置

- [ ] 停用 EUR/CNY 不修改代码、不重启，不再创建新任务。
- [ ] EUR/CNY 当前卡片隐藏，但历史文件和查询保留。
- [ ] 新增 GBP/CNY 后获取当日数据。
- [ ] GBP/CNY 自动启动历史回填，可显示进度并在重启后恢复。
- [ ] GBP/CNY 日/月/季/半年/年结果自动生成。
- [ ] 两个来源意图下的AZ91D均替换为MAT-REPL-01；旧序列停止未来采集但历史保留，ADC12不变。
- [ ] 新材料获取当日和历史，并经过同一发布门禁。
- [ ] 配置变化后面板无重复、空图例、无限加载或未处理异常。

### 9.5 降级与预警

- [ ] 阈值边界比较使用精确十进制。
- [ ] 未校验数据不触发预警。
- [ ] 断网时本地已发布历史仍可查询。
- [ ] 断网时新采集明确失败或等待，不造数。
- [ ] Cloud LLM 的 DNS、超时、429、5xx 均有明确降级。
- [ ] Cloud LLM 不可用不影响采集、校验、聚合、查询和预警。
- [ ] Agent 不读取未校验数据。

### 9.6 Windows、无数据库与 data 目录

- [ ] 最终交付 Windows x64 Electron 便携 ZIP，内含可双击 EXE。
- [ ] 干净 Windows 无 Java、Node、Maven、Docker 和数据库也能运行。
- [ ] Electron 单实例、动态端口、正常退出和异常看门狗通过。
- [ ] 便携目录内含根runtime/JRE、app内Spring Boot JAR与Vue生产资源、data/config业务配置和licenses许可说明。
- [ ] 无 MySQL、Redis、SQLite、H2 或其他业务数据库依赖/进程。
- [ ] 不存在隐藏数据库业务存储；Electron userData/缓存不承载业务真值。
- [ ] 用户可直接在 EXE 同级 data 目录看到 UTF-8 JSON/CSV。
- [ ] 整个便携目录可迁移；重启和停用监测项不会删除历史。
- [ ] 离线可启动并读取本地历史。

## 10. 验收报告最小内容

最终验收报告至少包含：

1. 发布版本号、构建 ID、便携 ZIP 与 EXE 的 SHA-256。
2. Windows 版本、时区、VM 快照 ID。
3. 每个 AT-ID 的状态、执行人、执行时间和证据路径。
4. 每个来源意图×材料序列的activeProvider、actualSourceName、source URL/引用、routeDecision、fallbackReason、商业自动能力状态和Manual审计；不得复制秘密凭证。
5. 黄金数据版本和 SHA-256。
6. 自动 Clock 测试报告。
7. 物理系统时间前跳/回拨录屏及恢复说明。
8. 干净 Windows 解压启动录屏、进程树、端口和 EXE 同级 data 目录清单。
9. 所有 FAIL、BLOCKED、豁免及需求方书面决定。
10. 最终发布结论：ACCEPTED、REJECTED 或 BLOCKED。

不得把绕过访问限制、来源冒充、Manual直达面板、任一材料无可用认可路线或PBOC双币闭环失败解释为“演示环境限制”。指定商业源自动能力有合法N/A证据且替代路线PASS时，整体P0可以ACCEPTED，但报告必须明确该能力未实现。
