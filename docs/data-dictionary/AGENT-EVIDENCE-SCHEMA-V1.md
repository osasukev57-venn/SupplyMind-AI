# AGENT-EVIDENCE-SCHEMA-V1

> 状态：FROZEN  
> 生效决策：DEC-060  
> 适用任务：D6-T01～D6-T05  
> 所有权：SupplyMind AI（不是Spring AI、模型厂商、conversation memory或tool transcript）

## 1. 范围与不变量

本合同只定义Day6的`EvidencePackV1`与`AgentReportV1`。它不修改`FILE-SCHEMA-V1`、`CALCULATION-RULES`、Lifecycle、daily、aggregate、warning或既有业务目录。

- EvidencePack和AgentReport只引用`PUBLISHED + VERIFIED/VERIFIED_WITH_NOTICE`正式数据；DEMO必须显式标记且不得冒充FORMAL。
- 金额、价格、汇率、均值、成本影响和其他业务数值一律保存为Java产生的精确十进制字符串；模型不得重算。
- Spring AI DTO、ChatResponse、Message、memory、conversation history和原始tool transcript不得进入本schema，也不得作为正式evidence。
- 所有引用必须由SupplyMind应用层核验；未知、缺失、篡改或不允许状态的引用必须fail-closed。
- 本合同不得引入数据库、第二dataRoot或新的业务真值目录。

## 2. `EvidencePackV1`

固定顶层字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `schemaVersion` | string | 固定`AGENT-EVIDENCE-SCHEMA-V1`。 |
| `evidencePackId` | string | 非空、单次请求内唯一；AgentReport必须引用同值。 |
| `requestId` | string | 非空；用于请求/trace关联，不替代业务证据。 |
| `mode` | string | `FORMAL`或`DEMO`；禁止隐式默认。 |
| `question` | string | 用户问题的审计文本；不得包含秘密。 |
| `createdAt` | ISO-8601 offset datetime | 由应用Clock产生；不参与业务数值计算。 |
| `scope` | object | 明确的itemIds、businessDate/periodStart/periodEnd与timezone。 |
| `toolExecutions` | array | 按`invocationIndex`升序，规则见2.1。 |
| `facts` | array | 按`factId`稳定排序，规则见2.2。 |
| `evidenceRefs` | array | 按`evidenceRefId`稳定排序，规则见2.3。 |
| `warnings` | array | 已持久化warning引用或明确的空数组。 |
| `notices` | array | stale、incomplete、degraded-data等已验证限制；不得由模型自行增加事实。 |
| `limitations` | array | 数据不足、外部能力边界和无法验证事项。 |

### 2.1 `toolExecutions[]`

每项固定包含：

- `invocationIndex`：从0开始的整数。
- `toolName`：只允许`series.resolve`、`history.query`、`period.metrics`、`quality.inspect`、`cost.impact`、`warning.explain`、`provenance.trace`。
- `toolVersion`：非空版本字符串；语义变更必须升级。
- `readOnly`：固定`true`。
- `input`：对应工具的显式SupplyMind input DTO。
- `output`：对应工具的显式SupplyMind output DTO；不得包含任意本地绝对路径或秘密。
- `evidenceRefs`：非空或经合同允许的空数组；每个ID必须存在于顶层`evidenceRefs`。
- `status`：`SUCCESS`、`NO_DATA`或`REJECTED`；异常不得伪装为SUCCESS。

Spring AI只可选择上述工具并形成调用请求。SupplyMind应用层必须在执行前校验名称、DTO、mode、日期范围和权限，并在执行后校验输出与引用。工具不得执行文件任意读取、数据库访问、crawler/HTTP、配置写、历史回填写、预警状态写或shell。

### 2.2 `facts[]`

每项固定包含：

- `factId`、`factType`。
- `itemId`、`businessDate`或`periodStart/periodEnd`。
- `value`：精确字符串；无值时为null，不得补0。
- `unit`、`currency`：可空但必须与来源记录一致。
- `qualityStatus`、`validationStatus`、`validationVersion`。
- `calculationVersion`、`calendarVersion`、`configVersions`（不适用时为null或空数组，禁止伪造）。
- `actualSourceName`、`sourceFingerprint`。
- `evidenceRefs`：至少一个可核验引用。

### 2.3 `evidenceRefs[]`

每项固定包含：

| 字段 | 规则 |
|---|---|
| `evidenceRefId` | EvidencePack内唯一。 |
| `refType` | `SOURCE`、`RAW`、`LIFECYCLE`、`DAILY`、`AGGREGATE`、`WARNING`、`CONFIG`之一。 |
| `ref` | dataRoot相对引用或既有sourceReference；禁止把任意绝对文件路径提供给模型。 |
| `sha256` | 对应文件有manifest/冻结hash时必填；否则为null并说明原因。 |
| `runId` / `rawRef` / `publishRef` | 适用时逐项填写；不适用为null。 |
| `businessDate` / `periodStart` / `periodEnd` | 与引用对象一致。 |
| `validationVersion` / `calculationVersion` / `calendarVersion` / `configVersions` | 适用时与正式记录一致。 |

## 3. `AgentReportV1`

固定顶层字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `schemaVersion` | string | 固定`AGENT-REPORT-V1`。 |
| `reportId` | string | 非空且在report目录内唯一。 |
| `requestId` | string | 必须等于EvidencePack.requestId。 |
| `evidencePack` | EvidencePackV1 | 完整嵌入；P0不创建第二EvidencePack业务目录。 |
| `generatedBy` | string | `LLM`或`JAVA_TEMPLATE`。 |
| `provider` / `model` | nullable string | Java模板时为null；不得含API Key或endpoint secret。 |
| `degraded` | boolean | 发生模板降级时固定true。 |
| `degradeReason` | nullable string | 缺Key、网络/timeout、429/5xx、畸形/空响应、非法工具请求等稳定原因码。 |
| `factsSummary` | array | 仅重述EvidencePack事实，不新增业务数值。 |
| `claims` | array | 每项含`claimId`、`text`、非空`evidenceRefs`；引用必须核验通过。 |
| `recommendations` | array | 非约束建议；不得自动改价、写配置或触发业务动作。 |
| `limitations` | array | 必须包含EvidencePack限制及模型/模板边界。 |
| `createdAt` | ISO-8601 offset datetime | 由应用Clock产生。 |

模型返回的任何额外数字、未知evidenceRef、无证据因果判断或越权动作必须被应用层拒绝；允许降级为只包含Java事实和限制的`JAVA_TEMPLATE`报告。

## 4. 持久化与完整性

- 唯一正式路径沿用`FILE-SCHEMA-V1`：`data/report/YYYY-MM/<reportId>.json`及相邻manifest；年月按report.createdAt的Asia/Shanghai日期路由。
- 使用既有`JsonV1Codec`、AtomicFileStore、DirtyMarker和manifest校验；不得重新实现第二套原子写或hash规则。
- EvidencePack作为AgentReport内嵌对象随报告一起原子持久化；manifest覆盖完整report JSON bytes。
- 同一reportId同字节重放幂等；异字节不得覆盖，按既有conflict/fail-closed规则处理。
- 重启后必须只依赖磁盘report JSON + manifest完成读取、schema校验和引用复核。

## 5. 版本与变更

任何字段删除、语义改变、工具输入/输出不兼容变化或证据所有权变化，都必须新增schema/tool version与Decision，不得静默改写V1。新增可空字段也必须保持旧文件可读并补充黄金字节与负向验收。
