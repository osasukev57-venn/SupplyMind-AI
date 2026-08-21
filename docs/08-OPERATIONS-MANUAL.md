# SupplyMind AI — 操作手册

> 文档编号：SMA-OPS-001
> 适用版本：P0 便携发布（`SupplyMindAI-<版本>-win32-x64.zip`）
> 最后更新：2026-08-21

## 1. 页面导航

| 页面 | 入口 | 用途 |
|---|---|---|
| 总览 | 监测 → 总览 | 各启用监测项的最新值与状态卡片 |
| 历史趋势 | 监测 → 历史趋势 | 指定监测项的每日历史与趋势图（缺失期间显式列出，不插值、不补零） |
| 数据质量 | 监测 → 数据质量 | 校验状态、时效（stale）与质量问题 |
| 来源与录入 | 数据管理 → 来源与录入 | 查看来源/路线；人工录入、文件导入、演示数据入口 |
| 动态配置 | 数据管理 → 动态配置 | 新增/停用/替换监测项、历史回填任务、配置历史、能力视图 |
| 预警 | 运营分析 → 预警 | 预警评估与确认 |
| Agent 工作台 | 运营分析 → Agent 工作台 | 基于本地证据链的智能分析问答 |

## 2. 日常操作

### 2.1 查看汇率与材料价格

打开「总览」，每张卡片显示：最新值、单位、业务日期、数据来源、采集路线、完整率与校验版本。桌面启动后会后台获取人民银行最新公开公告，顶部显示采集状态；完成后默认 USD/CNY、EUR/CNY 卡片自动刷新。“最新”指人民银行最近已公布业务日的中间价，不是流式行情。两条 ADC12 卡片自动使用 SHFE 铸造铝合金期货主力合约结算价公开基准，并显示真实来源；该值不是 SMM/Asian Metal 现货报价。「暂无数据」表示尚无已发布（PUBLISHED + VERIFIED 类）数据。

### 2.2 自动 ADC12 公开基准

联网启动后系统读取 SHFE 最近完整交易日 `ad_f` 日行情，按最高成交量选择主力合约（同量按较早交割月），保存完整响应实体与 SHA-256，再走材料校验、发布、daily 和四级 aggregate。来源名称与路线在总览/历史/质量页透明显示。若请求或解析失败，系统不造数、不使用 Synthetic 冒充，旧数据仍可查询。

### 2.3 人工录入（Manual 路线）

1. 打开「来源与录入」→「人工录入」。
2. 填写：监测项编号（如 `MAT.ADC12.SMM`）、**实际数据来源**（不得冒充 SMM/Asian Metal 等非实际来源）、业务日期、数值、单位（必须与配置单位一致）。
3. 点击「提交录入」。受理成功后页面显示 `PENDING` 状态与受理编号（runId）、原始记录（rawRef）、生命周期（timelineRef）。
4. 人工数据先进入 PENDING；核对来源、日期、数值和单位后，点击「校验并发布到面板」。后端使用冻结校验与发布门禁，只有 VERIFIED 类结果才生成 daily/aggregate 并进入总览；失败会明确 REJECTED。**Manual 不会伪装成自动采集**，输入的实际来源写入业务记录。

### 2.4 文件导入（LocalImport 路线）

1. 「来源与录入」→「文件导入」→ 下载导入模板（CSV）。
2. 按模板填写（schemaVersion、itemId、businessDate、value、unit、currency、实际来源名称、引用、URL）。
3. 上传 CSV 或 XLSX。系统逐行解析并受理为 RECEIVED+PENDING（返回逐行受理证据），无效行逐行报告，文件级错误整体拒绝。
4. 导入的数据同样必须通过校验/发布门禁才能进入展示层。

### 2.5 动态配置（H07-H09 场景）

- **停用监测项**：动态配置 → 找到目标行 → 点击「停用」。面板实时隐藏该卡片；历史数据与文件保留，仍可在历史趋势中查询（选择器包含已停用项）。
- **新增监测项**：点击「新增监测项」填写表单：
  - 汇率：来源意图 `PBOC`，接入方式「官方网站」，数据类型「人民币汇率中间价」，如 `FX.GBP.CNY.PBOC_MID` / 英镑/人民币中间价 / 单位 `CNY/1 GBP` / 基础币种 `GBP`。
  - 新增后系统**立即执行真实当前采集**（诚实返回 SUCCEEDED / AWAITING_MANUAL_INPUT / FAILED 及原因），并可同时提交历史回填范围。
  - 后端按配置元数据（providerType/accessMethod/rateKind/sourceIntent/route）判断自动能力，**不按 itemId 字符串猜测**。
- **替换监测项**：点击「替换监测项」，填写旧编号、新编号、名称、来源意图与回填范围。旧项停用（历史保留），新项以独立 itemId + supersedesItemId 激活（不冒充旧序列）。示例：把 SMM 与 Asian Metal 两个来源意图下的 `MAT.AZ91D.*` 分别替换为 `MAT.REPL-01.SMM` / `MAT.REPL-01.AM`，两条 `MAT.ADC12.*` 保持不变。
- **历史回填任务**：创建任务（itemId + 起止日期）→「运行」。任务状态诚实：自动能力缺失显示 `AWAITING_MANUAL_INPUT` / 失败原因；「重试」可重开失败任务。重启后任务从检查点恢复。
- **配置历史**：每次变更生成不可变快照并校验（verified）；快照被篡改时显式报告 `verified=false`，不静默跳过。

### 2.6 预警

打开「预警」→ 评估。预警只消费已发布数据；未校验数据不可能触发。当前阈值规则为显式 TEST/DEMO 规则（EXT-07/EXT-08 业务阈值与调价公式待业务方确认，P0 不自动调价）。预警支持确认（ack）与重启恢复。

### 2.7 Agent 工作台

输入问题（可选 itemId、日期范围、周期），系统：
1. 用 7 个受控只读 Java 工具从本地已发布数据收集证据（series.resolve / history.query / period.metrics / quality.inspect / cost.impact / warning.explain / provenance.trace）；
2. 云模型可用时基于 EvidencePack 起草建议；不可用时用同一 EvidencePack 确定性生成 Java 模板报告；
3. 后端核验全部证据引用后持久化报告。

### 2.8 一键完整 DEMO

在「来源与录入」点击“运行完整演示流程”。后端真实执行 Synthetic Provider→DEMO raw→标准化→材料校验→日/月/季/半年/年演示投影→预警求值→审计报告，并返回每阶段和每标的结果。演示产物只写 `data/demo/` 与 `data/raw/demo/`，不会生成 PUBLISHED，也不会进入正式 `processed/`、预警或 Agent 业务证据。重复运行使用相同确定性字节与 SHA-256。
## 3. 数据查看

所有业务数据在 `data` 目录下可直接打开：

| 位置 | 内容 |
|---|---|
| `data/config/monitor-series.json` | 唯一活动配置 |
| `data/config/history/<n>.json` | 不可变配置历史快照 |
| `data/raw/<mode>/<provider>/<itemId>/<YYYY>/<MM>/` | 原始数据（按接收时间分区） |
| `data/staging/` | 生命周期时间线 |
| `data/processed/daily/<itemId>/<YYYY-MM>.csv` | 每日加工（按业务月轮转） |
| `data/processed/aggregate/<itemId>/<grain>/<YYYY>.csv` | 月/季/半年/年聚合（按年轮转） |
| `data/warning/<YYYY-MM>/`、`data/report/<YYYY-MM>/` | 预警与 Agent 报告（按月轮转） |
| `data/runtime/` | 任务/时间状态/dirty marker/冲突证据 |
| data/demo/showcase/、data/raw/demo/ | Synthetic 演示报告和 DEMO raw；与正式数据严格隔离 |

## 4. 操作红线

- 不要手工修改 `data` 下文件后继续做正式业务判断——损坏文件会被系统显式报告并隔离，最后有效文件不会被自动覆盖，但手工改动无法被系统追溯。
- 人工录入必须填写真实来源；把免费/手工来源标记为 SMM 或 Asian Metal 属于来源冒充，违反冻结合规边界。
- 演示数据（演示入口）只用于功能演示，不进入正式业务判断。
- 云密钥只通过环境变量传入（见部署手册），不要写入日志或证据。
