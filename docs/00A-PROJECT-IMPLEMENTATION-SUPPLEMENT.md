# 项目方实施补充说明

> 文档编号：SMA-REQ-SUP-001  
> 性质：项目方对原需求书“数据获取方式”与实施优先级的正式补充  
> 纳入基线日期：2026-08-08  
> 适用范围：SupplyMind AI P0 数据接入、开发排序、验收与风险判定

> 架构实施增补（2026-08-13）：Day 6 尚未进入正式实现时，项目批准以 DEC-060 和 D6-T00 受控门禁评估 Spring Boot 3.5.15 + Spring AI 1.1.8；本增补不改写下方项目方原文，也不改变 Day 1～Day 5 已冻结业务语义。

## 1. 项目方原文

> “选择供应链成本监测与动态调价预警智能体的同学们，请优先完成汇率爬取和存取。如有关大宗交易网站因会员限制，反爬机制无法自动获取信源，则保留手动填写接口，或者查找同类免费信源网站。”

## 2. 基线关系

1. 本文件独立保存，不修改、不覆盖 `00-OFFICIAL-REQUIREMENTS.md` 中的原始需求书正文。
2. 本说明是项目方对“数据获取方式”和“实施优先级”的正式补充；该范围内与旧规划冲突时，以本说明为准。
3. 本说明不降低 H01-H09、JSON/CSV 本地持久化、校验门禁、精度、轮转、跨文件查询、动态配置和 Windows 交付要求。

## 3. 正式实施解释

### 3.1 P0最高优先级

Day 1 至 Day 2 优先形成以下真实汇率闭环：

```text
中国人民银行真实数据
→ EUR/CNY、USD/CNY
→ 原始 JSON
→ 标准化
→ 基础校验
→ VERIFIED 发布
→ 每日加工
→ JSON/CSV 持久化
→ 历史读取
→ 多周期聚合
```

至少完成到每日加工及 JSON/CSV 存取并验证闭环后，才推进大宗原材料 Provider。

### 3.2 大宗原材料三层合法接入策略

1. 指定数据源存在合法公开数据或合法接口时，优先自动获取。
2. 指定商业网站因会员权限、无公开接口或合法反爬机制无法自动获取时，采用同类免费公开信源。
3. 仍无合适免费信源时，提供手动填写接口。

禁止绕过登录、验证码、会员权限、访问控制或反爬机制。SMM/Asian Metal 商业授权缺失只影响对应“指定源自动采集能力”，不再阻塞整个 P0。

### 3.3 Provider逻辑类型

架构至少区分以下六类逻辑来源；10天实现中可以共用基础类，但来源身份和接入方式不得合并丢失：

- `OfficialWebDataProvider`
- `AuthorizedApiDataProvider`
- `FreePublicDataProvider`
- `ManualDataProvider`
- `LocalImportDataProvider`
- `SyntheticDemoDataProvider`

### 3.4 统一治理链

所有自动、免费公开、手动和本地导入数据都必须进入同一处理链：

```text
DataProvider
→ 原始数据
→ 标准化
→ 校验
→ VERIFIED发布
→ 每日加工
→ 聚合
→ JSON/CSV
→ 面板/预警/Agent
```

手动录入不得直达面板。每条手工数据至少记录：实际来源、标的、业务日期、输入时间、数据单位、输入方式、校验状态、最后更新时间。

### 3.5 来源真实性

免费公开信源必须记录并展示真实网站名称、链接或可核验引用，不得标记成 SMM、Asian Metal 或其他并非实际来源的网站。所有替代路径必须保留来源类型、获取方式和原始证据。

## 4. P0验收影响

- PBOC EUR/CNY、USD/CNY 真实自动获取与本地文件闭环是 P0 前置验收项。
- 对大宗原材料，指定源可合法自动获取时验证自动通道；不可合法自动获取时，使用“免费公开信源”或“手动填写”仍可满足 P0 接入要求。
- 商业源自动采集未实现必须如实展示能力状态，但不再单独阻止整个 P0 交付。
- synthetic 仍只用于演示和测试，不能替代真实汇率或冒充真实材料来源。

## 5. Day 6 Agent实施架构增补

1. Java 17 保持不变；Day 6 在正式实现前先执行 `D6-T00 Framework Compatibility / Upgrade Gate`，候选组合固定为 Spring Boot `3.5.15` 与 Spring AI `1.1.8`。仅允许稳定 release，禁止 Spring Boot 4.x、Spring AI 2.x、SNAPSHOT、MILESTONE、RC。
2. SupplyMind 保留自有 `LLMService` 业务门面；Spring AI `ChatClient`/`ChatModel` 只在 infrastructure adapter 内实现该门面，用于模型请求响应和受控 Tool Calling，不得把 Spring AI 类型扩散到业务服务、EvidencePack 或报告契约。
3. Agent Tool 必须经 SupplyMind 自有只读 Tool Adapter/Application Layer 注册。模型只可选择已登记工具；输入校验、Java Tool 执行、输出校验、权限控制和 EvidencePack 构造均由应用程序负责。禁止向模型提供任意文件、网络爬取、配置写入、回填写入、规则修改、数据库或 Shell 工具。
4. EvidencePack 与 AgentReport 继续由 SupplyMind 定义、校验和持久化；Spring AI conversation history、prompt transcript 或模型 memory 不得替代正式证据对象。
5. API key、base URL、model、timeout 必须外部化配置且不得提交秘密。云模型缺失密钥、超时、限流、5xx、空响应、非法工具请求或畸形响应时，仍由同一 EvidencePack 生成确定性 Java 模板报告，不得使核心数据链或 Agent API 整体 500。
6. D6-T00 必须以 `day5-complete` / `36dc178` 为升级基线执行 Day 1～Day 5 全量回归和依赖审计。若升级要求改变文件字节、JSON/CSV schema、BigDecimal、调度、validation/publish、warning 或大规模重写既有业务代码，则拒绝升级并放弃 framework-upgrade commit，回到 Spring Boot 3.3.6 的轻量 Agent 方案；不得通过修改已冻结业务语义迁就框架。
