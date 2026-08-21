# SupplyMind AI — 已知限制与外部待确认项

> 文档编号：SMA-LIM-001
> 适用版本：P0 便携发布（Day 10 Final Stage 状态）
> 最后更新：2026-08-21

## 1. 外部待确认项（OPEN_EXTERNAL，非缺陷）

| 编号 | 内容 | 影响 | P0 处理 |
|---|---|---|---|
| EXT-05 | 新增标的及初始化的历史回填范围 | 回填起止日期口径 | 以配置请求的显式 backfillFrom/To 为准；无自动历史能力时诚实 `AWAITING_MANUAL_INPUT` |
| EXT-07 | 价格/汇率/质量和成本影响预警阈值 | 业务预警规则 | 仅使用显式 TEST/DEMO 规则（demoRule=true），不冒充业务阈值 |
| EXT-08 | 动态调价公式、成本权重与自动执行边界 | 自动调价能力 | P0 不自动调价；Agent 建议为非约束性文本，数值由 Java 确定性计算 |
| EXT-09 | 「跨卷」语义（多轮转文件 vs 物理磁盘卷） | 轮转/跨卷验收口径 | 默认同一 data 根目录下多轮转文件；跨文件拼接/去重/排序已实现 |
| EXT-10 | 指定商业源会员授权及免费基准映射 | 自动材料采集 | `PARTIALLY_RESOLVED`：ADC12 使用获批准的 SHFE 铸造铝合金期货主力合约结算价公开基准；AZ91D 与指定 SMM/Asian 自动能力继续 Manual/LocalImport |

## 2. 能力边界（设计冻结）

- **指定商业源自动采集**：SMM / Asian Metal 无会员授权，P0 不提供该指定源自动采集（DEC-015/037）；能力仍为 `N/A_APPROVED_FALLBACK`。DEC-063 只批准独立的 SHFE ADC12 同类公开期货基准，不等于指定商业源能力实现。AZ91D 继续 Manual/LocalImport。
- **Manual 数据发布**：人工录入先受理为 PENDING，必须由操作员显式点击“校验并发布到面板”，再经过 `material-basic-validation-v2` 与正式 Publish Gate；成功才生成 daily/aggregate。实际来源逐记录保留，不把模拟数据或人工数据冒充商业源。历史回填在无真实输入时保持 `AWAITING_MANUAL_INPUT`。
- **云 LLM**：可选能力；无密钥默认 Java 模板。Agent 专用前端请求与云端 HTTP 客户端默认超时均为 90 秒；超时、无效结构化引用或模型响应不合规时安全降级。密钥只经环境变量注入，不落盘、不入日志。
- **本地模型 / RAG / vLLM / LoRA**：P0 不包含（DEC-032）；Ollama/Qwen 连通性为 P1，正式本地模型为 P2。
- **数据库 / Docker / Redis / MCP / Vector Store**：P0 一律不包含（DEC-004/005/025）。
- **网络依赖**：PBOC、SHFE 采集与云 LLM 依赖公网；断网时本地历史可查、新采集明确失败或等待，不造数。SHFE 基准是期货结算价，不代表 ADC12 现货或采购成交价。

## 3. 运行限制

- 仅支持 Windows x64；不支持 Linux/macOS 桌面形态（后端 JAR 为内部组件，不作为独立交付）。
- 单实例设计：同一目录同时只运行一个实例。
- data 目录不可写时应用 fail-closed 拒绝启动（不静默回退到隐藏目录）。
- 系统时间回拨：rotation 高水位不回退、相同业务键幂等。按项目方安全指令，本轮不修改宿主机系统时间；跨期/回拨验收通过生产同一 Clock 注入边界在临时 dataRoot 执行（DEC-062）。AT-TIME-003/004 的“物理改时”步骤保持 NOT_RUN，不伪报 PASS。

## 4. 验收状态口径

- `BLOCKED / NOT_RUN / N/A_APPROVED_FALLBACK` 均不等于 PASS；任何外部/未执行项在验收报告与证据索引中逐项列出（见 `docs/14-REQUIREMENT-ACCEPTANCE-EVIDENCE-INDEX.md`）。
- 历史证据（Day 1-9）与最终发布证据不互相冒充；Cloud 真实历史 Gate 与本次便携 keyed Gate 分别记录。
