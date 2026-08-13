# Day 6 Agent Implementation（2026-08-13）— Spring AI Read-Only Agent（D6-T01~T05）

> 性质：Day6 阶段汇总 Evidence（D6-T01~T05 实施侧，FAST-R0；非 Stage Review）。
> 基线：Day5=`COMPLETE`（`8ec3aaa`/`7855fc2`）；D6-T00=`DONE`（Spring Boot 3.5.15 + Spring AI 1.1.8，Java 17，DEC-060）。
> 冻结 schema：AGENT-EVIDENCE-SCHEMA-V1（`docs/data-dictionary/AGENT-EVIDENCE-SCHEMA-V1.md`）。
> Branch：`integration/day6`（@ `d6220e8` D6-T00 merge → D6-T01~T05 commits）。

## D6-T01 Spring AI 只读 Tool Boundary（PASS）

- 七个冻结工具（DEC-060 精确名单），全部为 Spring AI 1.1.8 `@Tool` adapter，只暴露给模型的**唯一** ToolCallbackProvider：
  `series.resolve`、`history.query`、`period.metrics`、`quality.inspect`、`cost.impact`、`warning.explain`、`provenance.trace`。
- 全部 READ_ONLY：只调用 Day1-Day5 production services（HistoryQueryService/ConfigManagementService/DataRoot 只读链），
  测试断言工具执行前后 data-root 快照逐字节不变。
- Tool Result 版本化契约：toolName/toolVersion/requestId/status(SUCCESS|NO_DATA|REJECTED)/inputSummary/result/notices/evidenceRefs；无 stack trace 进 LLM。
- 输入安全（ToolArguments）：identifier 白名单（拒绝 `/`、`\`、`..`、`~`、drive letter、空白/引号）、ISO 日期、grain 枚举、范围上限 10 年；
  path traversal / 未知 series / 非法日期 / 超大范围 → 结构化 REJECTED。
- 生产 service 复用：history.query/quality.inspect/provenance.trace 走 HistoryQueryService（conflict EXCLUDED_AND_REPORTED、missing != zero）；
  series.resolve 走 active config（H07/H09 动态）；cost.impact 只读持久化 aggregate（EXT-08 权重未确认→仅变化率并显式 notice，无基线→NO_DATA 不编造）。
- 测试：AgentToolBoundaryTest 7/7（含 Spring AI ToolCallback 元数据真实暴露断言、只读快照断言）。

## D6-T02 EvidencePackV1 + AgentReportV1（PASS）

- 严格按 AGENT-EVIDENCE-SCHEMA-V1：schemaVersion=`AGENT-EVIDENCE-SCHEMA-V1`/`AGENT-REPORT-V1`；
  evidencePackId/requestId/mode(FORMAL|DEMO 无隐式默认)/scope/toolExecutions(readOnly=true)/facts/evidenceRefs/warnings/notices/limitations。
- EvidenceRefVerifier：每个引用文件按 dataRoot 相对路径核验存在性 + 相邻 manifest 校验；缺失/无效/不安全 → MISSING/INVALID/UNAVAILABLE，绝不让 LLM 把不可验证事实说成 verified。
- sha256 取自核验通过的 manifest（有则填、无则 null 并说明）。
- facts：value 为精确十进制字符串（BigDecimal），无值 null 不补 0；每个 fact 至少一个可核验 evidenceRef，未核验引用 → fact 排除并写入 limitations。
- 测试：AgentPipelineIntegrationTest + AgentAttackTest（A5 缺失证据不冒充 verified）。

## D6-T03 LLMService + Spring AI ChatClient Adapter（PASS）

- `LLMService`（application port：LLMRequest/LLMResponse/LLMStatus SUCCESS|UNAVAILABLE|MALFORMED|REJECTED）为唯一业务 LLM 端口。
- `SpringAiLlmService`（infrastructure 层）内部使用 Spring AI ChatClient；Spring AI 类型不进入 history/warning/backfill/validation/storage/aggregation/config 包。
- Cloud 配置全部 externalized（`supplymind.agent.llm.*`，环境变量占位）：provider/base-url/model/api-key/timeout；无 secret 入 Git/yml/测试/日志。
- ChatClient 未配置（无 key）→ port 返回 UNAVAILABLE，ApplicationContext 仍正常启动（FoundationStartupAcceptanceTest 2/2 PASS 验证）。
- 真实 Cloud LLM network = `PENDING_EXTERNAL`（EXT 未确认供应商；本地 stub ChatModel 验证 ChatClient 边界与 tool 元数据，不伪造真实网络 PASS）。
- 测试：ChatModel stub（成功/异常/空白）验证 SUCCESS/UNAVAILABLE/MALFORMED 分支。

## D6-T04 证据核验、结构化查询、Agent API 与报告持久化（PASS）

- AgentOrchestrator pipeline：Java 决定工具链（ToolExecutor）→ 执行只读工具 → EvidencePack 装配与核验 → LLM 解释（或降级）→ AgentReportV1 装配（事实区/解释区分离）→ 持久化。
- 报告持久化：`data/report/YYYY-MM/<reportId>.json` + 相邻 manifest，走既有 AtomicFileStore/JsonV1Codec/ManifestFactory（report/ 模式已由 FILE-SCHEMA 冻结）；
  StorageSchemaVerifier/ManifestDerivedFieldsVerifier 增加 report/ 目标注册（机械扩展，不改既有业务文件语义）。
- Agent API：`POST /api/agent/query`（结构化响应：requestId/answer/llmStatus/degraded/degradeReason/toolTrace/evidenceRefs/reportRef/facts）；
  缺 question → 400 REJECTED（无 stack trace）；pipeline 异常 → 结构化 500（不暴露内部）。
- 测试：AgentApiTest 2/2（结构化响应 + 报告落盘 + 400 拒绝）。

## D6-T05 Java 确定性模板降级（PASS）

- 降级触发：ChatClient 未配置 / 异常（超时、5xx、断网）/ 空响应 / 畸形响应 → `JAVA_TEMPLATE` 报告（generatedBy=JAVA_TEMPLATE、degraded=true、degradeReason 稳定原因码）。
- 模板仅使用 LLMRequest 中的确定性 facts + EvidencePack notices，不重算、不编造数字/来源/evidence。
- 攻击 A9-A11：missing key / timeout / 5xx / malformed → 全部降级成功且报告仍持久化；Agent 整体不 500。
- 测试：AgentAttackTest a9ToA11。

## 攻击 harness A1-A12（全绿）

A1 仅 7 个注册工具可达；A2 非法参数 REJECTED；A3 path traversal 全入口 BLOCKED；A4 工具执行不突变 active config；
A5 缺失证据 UNAVAILABLE 不冒充 verified；A6 missing != zero；A7 BigDecimal 8 位精度保持；A8 LLM 编造数字不进 facts；
A9-A11 缺 Key/超时/畸形 → Java 降级；A12 EvidencePack/facts/refs 无 secret（LLM 叙述为自由文本区，非证据）。

## Regression（真实执行，`mvn clean test`，Java 17 / Boot 3.5.15 / Spring AI 1.1.8）

| 指标 | 结果 |
|---|---|
| suites (classes) | 87（83 Day1-Day5 基线 + 4 新增 D6 suite：AgentToolBoundaryTest/AgentPipelineIntegrationTest/AgentApiTest/AgentAttackTest） |
| tests | 430（407 基线 + 23 D6 新测试） |
| failures | 0 |
| errors | 0 |
| skipped | 8（与 Day5 基线逐项相同：7=真实联网/真实 raw 门禁，1=AT-TIME-003/004 D10 物理时间；Day1-Day5 核心测试 0 丢失、0 新增 skip） |

- Day5 Regression Protection：ScheduledGuardProductionPathTest/ScheduledEntryPostFixAttackTest/TimeRotationServiceTest/BackfillRangeCompletionRuleTest/HistoryQueryServiceTest/Day5R2* 全部绿（含于 430）。
- No Database：无 MySQL/PostgreSQL/SQLite/H2/Redis/MongoDB；报告与证据均为本地文件+manifest。
- No Secret：无提交 key；Cloud 配置经环境变量注入；A12 验证 EvidencePack 无 secret。

## 状态

- D6-T01~T05=实施完成（docs/04 状态已更新为 DONE，`statusReason=D6T0X_*_20260813`）。
- Day6 Development Tasks=`ALL_DONE`（实施侧）；Day6 Final Acceptance=implementation-side PASS；Day6 Stage Review=`PENDING`；Day 6=`NOT_COMPLETE`。
- Cloud LLM 真实网络=`PENDING_EXTERNAL`（EXT 供应商未确认，未伪造 PASS；ChatClient 边界已由本地 stub 验证）。
- 禁止项均未执行：未开始 Day7/Vue、未 merge main、未改 Day1-Day5 业务口径/冻结 Decision/JSON/CSV schema/BigDecimal 规则、未引入数据库、未引入 RAG/Vector/MCP/Memory。
