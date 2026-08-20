# SupplyMind AI — 需求 → 验收 → 证据索引

> 文档编号：SMA-EVI-001
> 适用版本：P0 便携发布（Day 10 Final Stage）
> 最后更新：2026-08-21
> 安全边界：按 DEC-062，不修改宿主机网络、VPN/代理、DNS、路由、防火墙、Hyper-V、Docker/WSL、系统服务、注册表或系统时间。

## 1. 状态定义

- `PASS`：按冻结预期真实执行且证据有效。
- `SAFE_EQUIVALENT_PASS`：业务预期通过生产同一边界与临时 dataRoot/受控 Clock/stub 完成；仅替代高风险操作手段。
- `NOT_RUN`：指定物理方法未执行，不得解释为 PASS。
- `N/A_APPROVED_FALLBACK`：指定商业材料源自动能力不可合法取得；获准走 Manual，自动能力本身不是 PASS。

最终机器证据根目录为 `docs/evidence/Day10/`，由该目录 `index.json` 绑定文件大小与 SHA-256。

## 2. 官方验收项（H01-H09）

| 需求 | 验收与证据 | 最终状态 |
|---|---|---|
| H01 每日真实采集与多级均值 | AT-SRC-002 真实 PBOC 双币；`backend-regression-summary.json` 中 daily/aggregate 测试；`final-portable-exe-data-visibility.json` | PASS |
| H02 精度无流失 | DailyProcessing、AggregateCalculator、AggregateProcessing、MaterialDailyAggregate 共 46 个定向测试，BigDecimal 字符串精度与一次舍入合同保持 | PASS |
| H03 程序目录直接可见 JSON/CSV | 最终 EXE 新鲜解压后生成 JSON=35、CSV=15，raw/daily/aggregate 均存在 | PASS |
| H04 无隐藏数据库 | 运行依赖树禁用模式命中 0；制品仅 Electron 与随包 Java；未引入 JDBC/R2DBC/H2/MySQL/Redis/Flyway/Liquibase 等 | PASS |
| H05 跨期轮转 | TimeRotationService 11 个受控 Clock 测试覆盖月/季/半年/年、前跳、回拨、高水位与重启 | SAFE_EQUIVALENT_PASS；AT-TIME-003/004 物理改时方法 NOT_RUN |
| H06 跨年度查询 | HistoryQueryService 8 个测试及 daily/aggregate 跨年合同通过 | PASS |
| H07 动态停用/新增 | DynamicConfig 12、CurrentIntakeAttack 5、PbocDynamicTargetAttack 6，及 Day8 Web 证据 | PASS |
| H08 新标的当日采集/历史能力 | 新目标当前采集、历史能力门禁、Manual 等待态与回填合同均有攻击测试 | PASS |
| H09 面板重构和旧历史保留 | Day8 Web 验收、配置版本历史和最终 EXE 重启读取证据 | PASS |

## 3. 用户补充：真实汇率与材料降级

| 项目 | 最终事实 | 状态 |
|---|---|---|
| PBOC USD/CNY | EXE 启动后真实获取 2026-08-20，`6.7808 CNY/1 USD`，来源“中国人民银行官网（授权中国外汇交易中心公布）”，VERIFIED | PASS |
| PBOC EUR/CNY | 同次启动真实获取 2026-08-20，`7.8815 CNY/1 EUR`，来源同上，VERIFIED | PASS |
| 启动后页面可见 | acquisition `RUNNING → SUCCEEDED`；面板显示双币；失败时显示真实错误且允许重试 | PASS |
| 商业材料源 | 未绕过会员、登录、验证码或反爬；指定源自动能力保持条件化 | N/A_APPROVED_FALLBACK |
| Manual 材料路线 | `PENDING → 显式校验/发布 → PUBLISHED+VERIFIED → daily + 四级 aggregate` | PASS |
| D10 模拟材料 | `MAT.ADC12.SMM`，`18888.50 元/吨`，来源明确为“D10手工模拟验收（非外部市场信源）”，不得冒充实时市场价格 | PASS |

## 4. P0/Windows/故障边界

| 范围 | 证据 | 状态 |
|---|---|---|
| Windows 便携启动、随包 JRE、loopback | `package-verification.json`、`final-portable-exe-data-visibility.json`、Day9 final-attack | PASS；干净 VM 原方法 NOT_RUN |
| JSON/CSV 与重启持久化 | 最终 EXE 首次运行与重启数据一致 | PASS |
| 本地文件存储、无数据库 | `backend-regression-summary.json` 依赖树与最终制品进程/文件事实 | PASS |
| 云模型无 Key/故障降级 | Day6/Day9 Agent 证据；核心监测不依赖 LLM | PASS（stub/历史真实 Cloud）；Day10 新计费调用 NOT_RUN |
| 网络/401/429/超时/5xx/畸形 | 本地 stub 与模板降级矩阵，不主动断开宿主机网络 | SAFE_EQUIVALENT_PASS |
| 文件损坏/半写/重启恢复 | Atomic/manifest/DirtyMarker/历史冲突攻击回归，使用临时 dataRoot | SAFE_EQUIVALENT_PASS |
| 动态 EUR/GBP/MAT-REPL-01 | Day8 Web P0 矩阵与 DynamicConfig/CurrentIntake/PbocDynamic 测试 | PASS |
| 源码、部署/操作/演示/API/数据字典/许可/限制 | README、docs/07～14 | PASS |

## 5. 最终发布制品与回归

- ZIP：`release/SupplyMindAI-0.9.0-win32-x64.zip`
- ZIP SHA-256：`022685093835379162FEBBAF25EA70BA2898AFDE26DD317A33FB6522522151F9`
- Release manifest SHA-256：`48AB0DFD6021AD1D3999E142744825939D818C6131F7817D48EA3B21780AD41A`
- 制品条目：321；白名单违规：0；API Key 命中：0。
- Backend：124 suites / 652 tests / 0 failures / 0 errors / 9 skipped。
- Desktop：31 / 31 PASS。
- Frontend：34 / 34 PASS；production build PASS。
- 最终执行报告：`docs/evidence/Day10/D10-FINAL-ACCEPTANCE.md`。

## 6. 明确保留的非 PASS 项

- AT-TIME-003/004 的物理修改 Windows 时间步骤：`NOT_RUN`。
- 干净 Windows VM 逐项安装态证明：`NOT_RUN`；便携制品边界按 DEC-062 为 `SAFE_EQUIVALENT_PASS`。
- 主动断开宿主机网络：`NOT_RUN`；故障行为按 stub 为 `SAFE_EQUIVALENT_PASS`。
- EXT-07/08：`OPEN_EXTERNAL`；不影响 Java 模板预警/Agent P0。
- 指定商业材料源自动采集：`N/A_APPROVED_FALLBACK`；Manual 合法路线为 `PASS`。
