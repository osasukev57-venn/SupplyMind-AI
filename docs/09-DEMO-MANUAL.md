# SupplyMind AI — 演示手册

> 文档编号：SMA-DEMO-001
> 适用版本：P0 便携发布
> 最后更新：2026-08-21

## 1. 演示目标

按官方需求书场景演示：汇率监测、原材料监测、多级均值计算、本地 JSON/CSV 存储、动态配置联动（停用 EUR、新增 GBP、替换 AZ91D）、人工录入受理、预警与 Agent 分析。整场演示不需要数据库、不需要 Docker、不需要任何开发环境。

## 2. 环境准备（约 2 分钟）

1. 校验 ZIP SHA-256，解压到演示目录（可用中文路径，如 `D:\供应链演示`）。
2. 双击 `SupplyMindAI.exe`，等待主窗口出现。

## 3. 演示脚本（约 10 分钟）

### 3.1 默认面板与数据存储（H03/H04）

- 打开「总览」：PBOC 成功后 EUR/CNY、USD/CNY 自动出现最近业务日中间价；SHFE 成功后两条 ADC12 来源意图显示最近完整交易日的铸造铝合金期货主力合约结算价，并明确标为“公开基准”，不冒充 SMM/Asian 现货。AZ91D 无自动数据时保持“暂无数据”。
- 打开程序目录 `data`：直接展示 `config/monitor-series.json`、`raw/`、`processed/daily/`、`processed/aggregate/` 等 JSON/CSV 文件——业务真值全部可肉眼检查。
- 打开任务管理器：只有 `SupplyMindAI.exe`（Electron）与一个 `runtime\jre\bin\java.exe` 子进程，无任何数据库进程。

### 3.2 动态配置：停用 EUR（H07/H09）

- 「动态配置」→ 欧元/人民币中间价 行点击「停用」。
- 「总览」实时重构：EUR 卡片消失，其余不变；配置版本 +1。
- 「历史趋势」选择器仍包含已停用的 EUR——旧历史可查、文件未删。

### 3.3 动态配置：新增 GBP（H07/H08）

- 「动态配置」→「新增监测项」：编号 `FX.GBP.CNY.PBOC_MID`、名称 英镑/人民币中间价、来源意图 `PBOC`、接入方式 官方网站、数据类型 人民币汇率中间价、单位 `CNY/1 GBP`、基础币种 `GBP`，可选回填范围。
- 点击「新增并立即采集」：页面如实显示当前采集状态（联网时 PBOC 真实采集；失败/等待时诚实展示原因，绝不伪造成功）。
- 展示「可用数据来源」能力视图：能力由配置元数据驱动（非 itemId 硬编码）。
- 「历史回填任务」运行 GBP 任务：若自动历史能力不可用，任务诚实显示 `AWAITING_MANUAL_INPUT` 与原因。

### 3.4 动态配置：替换 AZ91D（H07/H09）

- 「动态配置」→「替换监测项」：`MAT.AZ91D.SMM` → `MAT.REPL-01.SMM`（来源意图 SMM），再执行 `MAT.AZ91D.AM` → `MAT.REPL-01.AM`（来源意图 Asian Metal）。
- 两条 ADC12 保持启用；旧 AZ91D 项停用但历史保留；新项显示 supersedesItemId。
- 「来源与录入」查看新项路线：人工补录（Manual）+ 诚实降级原因，不冒充自动采集。

### 3.5 人工录入受理（Manual 路线）

- 「来源与录入」→「人工录入」：监测项 `MAT.REPL-01.SMM`、实际来源（如演示人员姓名/机构）、业务日期、数值、单位 `元/吨` → 提交。
- 展示 `PENDING` + 受理编号（runId）/原始记录（rawRef）/生命周期（timelineRef）。再次核对后点击「校验并发布到面板」，展示 `PUBLISHED + VERIFIED`、daily 与月/季/半年/年 aggregate；演示来源必须写明“模拟验收数据（非外部市场信源）”。

### 3.6 一键完整 Synthetic DEMO

- 「来源与录入」点击“运行完整演示流程”。
- 展示 RAW_CAPTURED→PARSED→VALIDATED→DEMO_PROJECTED→DAILY_CALCULATED→MONTH_QUARTER_HALFYEAR_YEAR_CALCULATED→WARNING_EVALUATED→COMPLETE 八阶段。
- 展示 ADC12/AZ91D 的原始值、校验状态、日/月/季/半年/年演示均值、预警结果和 demoRef。
- 打开 `data/demo/showcase/supplymind-demo-showcase-v1.json` 与 `data/raw/demo/`，证明演示证据可审计；再确认没有生成对应正式 PUBLISHED、processed/warning/report 文件。

### 3.7 预警与 Agent

- 「预警」→ 评估：演示确定性规则与诚实 `NOT_TRIGGERED`/触发状态；未校验数据不触发。
- 「Agent 工作台」提问（如「分析 ADC12 近期价格变化」）：
  - 无云密钥时：展示 `JAVA_TEMPLATE` 确定性模板报告（degraded=true）——证明核心能力不依赖外部 LLM。
  - （可选）配置云密钥后：展示云模型建议 + 全部数值/证据引用来自本地 Java 证据链。

### 3.8 重启与迁移（H03/F14）

- 关闭并重启应用：配置、录入、历史全部保持。
- 关闭应用，把整个目录移动到新位置（或复制到另一台 Windows 机器），双击 EXE 继续使用。

## 4. 演示要点（避免误解）

- 「暂无数据」= 尚未有已发布数据，是诚实状态，不是故障。
- Manual/免费公开来源的展示名称与真实来源一致；演示数据（演示入口）有明显标识且不进入正式判断。
- 指定商业源（SMM/Asian Metal）自动采集能力仍为 `N/A_APPROVED_FALLBACK`。ADC12 另有获批准的 SHFE 同类公开期货基准自动链；AZ91D 保留 Manual。公开基准、Manual、Synthetic 均不得冒充指定商业源。
- 预警阈值为 TEST/DEMO 规则；自动调价不在 P0 范围（EXT-07/EXT-08 待业务方确认）。

## 5. 对应验收证据

- 最终 EXE 真实人民银行采集、人工模拟材料、精度/轮转/跨年/动态配置、无数据库与发布包机器证据：`docs/evidence/Day10/`。
- 安全约束：不修改验收主机网络、代理、系统时间、Hyper-V、Docker/WSL 或系统服务；物理改时与干净 VM 特定步骤不伪报 PASS，按 DEC-062 采用受控 Clock/便携包等价证据。
