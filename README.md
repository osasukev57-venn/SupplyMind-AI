# SupplyMind AI

供应链成本监测与动态调价预警智能体 — 面向 Windows 桌面的便携式成本监测平台。

## 项目简介

SupplyMind AI 自动聚合官方权威数据源，执行数据核验与本地文件留存，并将核心内容进行面板展示：

- 汇率监测：中国人民银行每日公布的官方汇率（默认 EUR/CNY、USD/CNY，支持动态新增如 GBP/CNY）
- 原材料监测：ADC12、AZ91D 等材料价格（SMM / Asian Metal 两个来源意图；指定商业源无法合法自动获取时，按「同类免费公开信源 → 人工录入」受控降级）
- 计算链路：每日加工均值 → 月度均值 → 季度均值 → 半年度均值 → 年度均值
- 本地持久化：全部业务真值以 UTF-8 JSON/CSV 文件保存在程序根目录 `data` 下，**不使用任何数据库**
- 数据核验：所有未经校验的数据不得进入展示层
- Agent 工作台：基于 Java 受控只读工具的智能分析；云模型不可用时确定性降级为 Java 模板报告

## 快速开始（Windows x64）

1. 解压 `SupplyMindAI-<版本>-win32-x64.zip` 到任意可写目录（支持普通路径、带空格路径、中文路径）。
2. 双击 `SupplyMindAI\SupplyMindAI.exe`。
3. 应用自动使用内置 JRE 启动本地服务并打开面板（127.0.0.1 动态端口）。联网时会在后台读取中国人民银行最新公开公告；总览显示“采集中 / 已更新 / 获取失败”，失败可手动重试且不会阻塞本地历史查询。

**无需安装** Java、Node.js、Maven、Docker、MySQL、Redis 或任何数据库。

## 发布制品

| 制品 | 说明 |
|---|---|
| `release/SupplyMindAI-<版本>-win32-x64.zip` | 最终用户便携 ZIP（含内置 Temurin JRE 17、Electron 33 壳、Spring Boot 3.5.15 后端、Vue 3 前端） |
| `release/SupplyMindAI-<版本>-win32-x64.zip.sha256` | ZIP 的 SHA-256 校验值 |
| `release/SupplyMindAI-<版本>-win32-x64.zip.manifest.json` | 构建输入/制品哈希与确定性构建清单 |

完整部署步骤见 `docs/07-WINDOWS-DEPLOYMENT-MANUAL.md`。

## 工程结构

```
backend/    Spring Boot 3.5.15 + Java 17 后端（Provider→校验→发布→加工→聚合→文件持久化）
frontend/   Vue 3 + Vite 前端（总览/历史/质量/来源录入/动态配置/预警/Agent）
desktop/    Electron 便携壳（单实例、动态端口、随包 JRE、父进程看门狗）
release/    最终发布 ZIP 与校验文件
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
- 材料接入三层降级：合法指定源自动 → 同类免费公开信源 → Manual；不得绕过登录/验证码/会员/反爬；来源不得冒充（DEC-015/037/038）
- 云 LLM 仅生成非约束性建议文本；数值、均值、成本、风险等级全部由 Java 确定性计算；故障时同一 EvidencePack 生成 Java 模板报告（DEC-028/030/031）
- Agent 工具为 7 个只读工具，无写工具、无任意文件/网络/Shell（DEC-029）
