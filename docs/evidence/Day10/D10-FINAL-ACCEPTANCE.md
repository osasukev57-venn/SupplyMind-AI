# Day10 Final Acceptance

> 执行日期：2026-08-21（Asia/Shanghai）
> 分支：`integration/day10`
> 安全决策：DEC-062
> 宿主机配置变更：`NONE`

## 1. 最终结论

SupplyMind AI 的 H01-H09 业务结果、PBOC 真实汇率、文件存储、BigDecimal 精度、多级聚合、跨年查询、动态配置、Manual 材料降级和 Windows 便携制品边界均已通过。高风险的物理改时、主动断网与干净 VM 方法没有执行；按 DEC-062 使用生产同一 Clock/文件/故障边界完成安全等价验收，并明确保留原方法为 `NOT_RUN`。

## 2. 用户补充要求闭环

- 最终 EXE 启动后自动执行 PBOC 当前采集，状态从 `RUNNING` 进入 `SUCCEEDED`，不会阻塞页面启动。
- USD/CNY：2026-08-20，`6.7808 CNY/1 USD`，来源为中国人民银行官网，`VERIFIED`。
- EUR/CNY：2026-08-20，`7.8815 CNY/1 EUR`，来源为中国人民银行官网，`VERIFIED`。
- 两币经过真实 raw、lifecycle、publish、daily 与 aggregate 链，并在 EXE 重启后继续可见。
- 商业材料源不可合法自动取得时保留 Manual；用户提交后先为 `PENDING`，必须显式执行校验与发布，不自动可信。
- D10 验收人工录入 `MAT.ADC12.SMM = 18888.50 元/吨`，来源固定显示“D10手工模拟验收（非外部市场信源）”，随后生成 daily 与 month/quarter/halfyear/year 聚合。它只用于模拟验收，不是实时市场价格。

权威机器证据：`final-portable-exe-data-visibility.json`。

## 3. 原始验收要求

| 验收要求 | 结果 | 证据 |
|---|---|---|
| 随机自然月 daily 与月/季/半年/年均值、无精度流失 | PASS | `backend-regression-summary.json`：DailyProcessing 19、AggregateCalculator 15、AggregateProcessing 6、MaterialDailyAggregate 6，全部 0 failure |
| JSON/CSV 本地文件、无数据库 | PASS | 最终 EXE JSON=35、CSV=15；依赖禁用模式命中 0；仅随包 Java/Electron 进程 |
| 跨期轮转 | SAFE_EQUIVALENT_PASS | TimeRotationService 11/11；生产同一 Clock 边界与临时 dataRoot；物理改时 NOT_RUN |
| 多份历史文件跨年度读取 | PASS | HistoryQueryService 8/8 及 aggregate 回归 |
| 用户停用/新增/替换监测标的 | PASS | DynamicConfig 12/12、CurrentIntakeAttack 5/5、PbocDynamicTargetAttack 6/6 与 Day8 Web P0 证据 |
| 面板重构、旧项隐藏、旧历史保留 | PASS | Day8 Web P0 与版本化配置/历史查询证据 |
| 新标的当日数据/历史能力 | PASS | PBOC 动态目标真实 provider 合同；无历史能力时 fail-closed；Manual 诚实等待输入 |

## 4. 最终回归与制品

- Backend：124 suites / 652 tests / 0 failures / 0 errors / 9 skipped。
- Desktop：31 / 31 PASS。
- Frontend：34 / 34 PASS；build PASS。
- ZIP SHA-256：`022685093835379162FEBBAF25EA70BA2898AFDE26DD317A33FB6522522151F9`。
- Manifest SHA-256：`48AB0DFD6021AD1D3999E142744825939D818C6131F7817D48EA3B21780AD41A`。
- ZIP entries=321，白名单违规=0，API Key 泄漏=0。
- 完整依赖树禁用数据库/迁移/缓存模式命中=0。

## 5. 安全边界与未执行项

- 宿主机网络/VPN/代理/DNS/路由/防火墙：未修改。
- Hyper-V、Docker/WSL、系统服务、注册表：未修改。
- Windows 系统时间：未修改。
- AT-TIME-003/004 物理改时：`NOT_RUN`。
- 干净 VM 原方法：`NOT_RUN`；最终 ZIP 新鲜解压、随包 JRE、去外部运行时依赖、loopback、白名单、无数据库依赖为 `SAFE_EQUIVALENT_PASS`。
- 主动断网：`NOT_RUN`；网络/LLM 错误由本地 stub 验证为 `SAFE_EQUIVALENT_PASS`。
- Day10 未再次执行可能计费的 Cloud 请求；历史真实 Bailian Gate 保留 PASS。

## 6. 最终 Gate

- BLOCKER：无。
- MAJOR：无。
- H01-H09：满足；H05 的业务预期为 `SAFE_EQUIVALENT_PASS`，物理方法保持 `NOT_RUN`。
- D10-T01～D10-T05：支持 `DONE`。
- Day10：支持 `COMPLETE`。
- 最终发布：支持签署 P0 便携发布；所有限制必须随 `docs/13-KNOWN-LIMITATIONS.md` 一并交付。
