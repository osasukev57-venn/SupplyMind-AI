# SupplyMind AI

供应链成本监测与动态调价预警智能体 — 面向 Windows 桌面的便携式成本监测平台。

## 项目简介

SupplyMind AI 自动聚合官方权威数据源，执行数据核验与本地文件留存，并将核心内容进行面板展示：

- 汇率监测：中国人民银行每日公布的官方汇率（默认 EUR/CNY、USD/CNY，支持动态新增如 GBP/CNY）
- 原材料监测：ADC12 默认自动获取上海期货交易所铸造铝合金期货主力合约结算价，作为明确标注的同类公开基准；AZ91D 与无合适免费源的目标保留受控人工录入
- 计算链路：每日加工均值 → 月度均值 → 季度均值 → 半年度均值 → 年度均值
- 本地持久化：全部业务真值以 UTF-8 JSON/CSV 文件保存在程序根目录 `data` 下，**不使用任何数据库**
- 数据核验：所有未经校验的数据不得进入展示层
- 正式预警：每个启用监测项自动绑定默认变化率规则（材料7日/5%，其他1日/1%），可逐项自定义并保留版本历史；Java 决定是否触发
- Agent 工作台：Java 先生成并校验标准事实，云模型只用`{{factId}}`占位符组织语言，具体数值、日期、来源、趋势和风险由Java权威注入并回绑证据；单次分析最长等待90秒，云模型不可用或回答不合规时显示Java事实报告

## 快速开始（Windows x64）

> [前往 GitHub Releases 下载最新 Windows 运行包与完整交付包](https://github.com/osasukev57-venn/SupplyMind-AI/releases/latest)

1. 从 GitHub Releases 下载并解压 `SupplyMindAI-<版本>-win32-x64.zip` 到任意可写目录（支持普通路径、带空格路径、中文路径）。
2. 双击 `SupplyMindAI\SupplyMindAI.exe`。
3. 应用自动使用内置 JRE 启动本地服务并打开面板（127.0.0.1 动态端口）。联网时会在后台读取中国人民银行最新公开公告，并从上海期货交易所公开日行情取得 ADC12 的同类铸造铝合金期货基准；总览显示真实来源和状态，获取失败不会阻塞本地历史查询。
4. 在「预警」页直接查看全部监测项的系统默认规则和当前计算状态；无需先配置即可使用。需要时选择某项修改周期、百分比阈值和方向，保存后会立即重新评估。

**无需安装** Java、Node.js、Maven、Docker、MySQL、Redis 或任何数据库。

## 发布制品

正式二进制制品发布在 [GitHub Releases](https://github.com/osasukev57-venn/SupplyMind-AI/releases)，不提交到 Git 历史。仓库中的 `release/` 仅为本地构建输出目录，并由 `.gitignore` 排除。

| 制品 | 说明 |
|---|---|
| `SupplyMindAI-<版本>-win32-x64.zip` | 最终用户便携 ZIP（含内置 Temurin JRE 17、Electron 33 壳、Spring Boot 3.5.15 后端、Vue 3 前端） |
| `SupplyMindAI-<版本>-win32-x64.zip.sha256` | Windows 运行包的 SHA-256 校验值 |
| `SupplyMindAI-<版本>-win32-x64.zip.manifest.json` | 构建输入、提交号与制品哈希清单 |
| `SupplyMindAI-<版本>-complete-delivery.zip` | 面向接收方的单文件完整交付包：Windows 运行包、源码包、人工文档、演示数据与验收索引 |
| `SupplyMindAI-<版本>-complete-delivery.zip.sha256` | 完整交付包的 SHA-256 校验值 |
| `Source code (zip/tar.gz)` | GitHub 根据对应版本标签自动生成的开源源码包 |

完整部署步骤见 `docs/07-WINDOWS-DEPLOYMENT-MANUAL.md`。

## 工程结构

```
backend/    Spring Boot 3.5.15 + Java 17 后端（Provider→校验→发布→加工→聚合→文件持久化）
frontend/   Vue 3 + Vite 前端（总览/历史/质量/来源录入/动态配置/预警/Agent）
desktop/    Electron 便携壳（单实例、动态端口、随包 JRE、父进程看门狗）
release/    本地构建输出目录（不纳入 Git；正式制品上传至 GitHub Releases）
docs/       需求基线、追踪矩阵、验收计划、任务、台账、决策日志与全部验收证据
```

## 文档导航

| 文档 | 内容 |
|---|---|
| `docs/00-OFFICIAL-REQUIREMENTS.md` | 官方需求书正文（原始需求） |
| `docs/00A-PROJECT-IMPLEMENTATION-SUPPLEMENT.md` | 项目方实施补充说明（数据获取方式与优先级） |
| `docs/01-PROJECT-MASTER-PLAN.md` | 项目总计划 |
| `docs/02-REQUIREMENT-TRACEABILITY.md` | 需求追踪矩阵（H01-H09、F01-F14、冻结决策、外部待确认） |
| `docs/03-ACCEPTANCE-TEST-PLAN.md` | 验收测试计划（全部 AT 用例） |
| `docs/04-DEVELOPMENT-TASKS.md` | 开发任务定义（Day 1-10、P1/P2） |
| `docs/05-PROGRESS-LEDGER.md` | 跨窗口进度台账 |
| `docs/06-DECISION-LOG.md` | 架构与项目冻结决策日志 |
| `docs/07-WINDOWS-DEPLOYMENT-MANUAL.md` | Windows 部署手册 |
| `docs/08-OPERATIONS-MANUAL.md` | 操作手册 |
| `docs/09-DEMO-MANUAL.md` | 演示手册 |
| `docs/10-DATA-DICTIONARY.md` | 数据字典（文件布局与字段） |
| `docs/11-API-REFERENCE.md` | API 说明 |
| `docs/12-THIRD-PARTY-LICENSES.md` | 第三方许可 |
| `docs/13-KNOWN-LIMITATIONS.md` | 已知限制与外部待确认项 |
| `docs/14-REQUIREMENT-ACCEPTANCE-EVIDENCE-INDEX.md` | 需求 → 验收 → 证据索引 |
| `docs/evidence/` | 各 Day 验收证据（脚本、机器证据、截图、SHA-256） |

## 关键架构边界（冻结）

- 唯一持久化：`data/` 下 JSON/CSV；无数据库栈、无隐藏服务（DEC-004/005）
- 数据链：获取 → 不可变 RawReceipt → 生命周期候选 → 校验 → 发布 → 每日加工 → 多级聚合 → JSON/CSV → 面板/预警/Agent（DEC-010）
- 只有 `PUBLISHED + VERIFIED / VERIFIED_WITH_NOTICE` 进入展示层（DEC-011）
- 精度：全部业务值经 `new BigDecimal(String)` 进入计算，输出 `toPlainString()`，无 float/double、无科学计数法（DEC-008/009）
- 材料接入三层降级：合法指定源自动 → 同类免费公开信源 → Manual；ADC12 的默认免费基准为 SHFE 铸造铝合金期货主力合约结算价，AZ91D 保持 Manual；不得绕过登录/验证码/会员/反爬，且不得把公开基准冒充 SMM/Asian 现货（DEC-015/037/038/063）
- 云 LLM 仅生成非约束性建议文本；数值、均值、成本、风险等级全部由 Java 确定性计算；故障时同一 EvidencePack 生成 Java 模板报告（DEC-028/030/031）
- Agent 工具为 7 个只读工具，无写工具、无任意文件/网络/Shell（DEC-029）

## 数据来源与演示边界

- PBOC：USD/CNY、EUR/CNY 人民币汇率中间价，保存完整官方 HTTP response entity bytes 与 SHA-256。
- SHFE FreePublic：`ad_f` 铸造铝合金期货日行情；按成交量选择主力合约，同量时按较早交割月确定，使用结算价、单位 `元/吨`。这是 ADC12 的同类公开期货基准，不是 ADC12 现货成交价，也不是 SMM/Asian Metal 报价。
- Manual/LocalImport：用于 AZ91D 或暂时没有合适公开源的标的，先 PENDING，再经过统一校验与发布门禁。
- 批量演示：`demo-data/` 提供 LocalImport 六行示例 CSV 与操作说明；上传受理后需显式点击「批量校验并发布到面板」，再到总览/历史趋势查看结果。
- 一键 DEMO：走真实 Synthetic Provider、不可变 DEMO raw、标准化、校验、日/月/季/半年/年演示投影、预警求值和审计报告；所有产物只在 `data/demo` 与 `data/raw/demo`，永不进入正式 PUBLISHED/daily/aggregate。

## 开源许可

本项目以 Apache License 2.0 开源，见根目录 `LICENSE`。外部公开数据仍受其发布机构条款约束；本项目不授予第三方数据的再许可。
