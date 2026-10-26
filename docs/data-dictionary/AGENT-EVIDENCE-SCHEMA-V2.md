# AGENT-EVIDENCE-SCHEMA-V2 / AGENT-REPORT-V3

2026-10-26，用户批准的最小聚合行级证据及 Agent 成品化合同。生效记录见 DEC-071/072。CSV、计算、发布规则保持原版本，不迁移或重写已有文件。

## 分派和兼容

新生产报告的外层版本为 `AGENT-REPORT-V3`，包含原 Java 事实报告投影、`AGENT-EVIDENCE-SCHEMA-V2`、原快照 `evidenceBindings` 和独立的 `ANALYSIS-NARRATIVE-V1`。
V1/V2 报告继续原严格读取，只接受 V1 证据。V3 才启用行级校验和分段智能解读；未知版本拒绝。旧程序不支持 V3，不得将 V3 静默降成 V2。现有 `EvidencePackV1` Java 类型充当明确版本分派的兼容载体，不改变已存 V1 字节。

## 聚合行身份和摘要

原文件引用保持不变。新 `EvidenceRefEntry.refType=AGGREGATE_ROWS_V1` 表示文件完整性和每行身份已核验；文件级标量 lineage 留空，不采用首行或把不同 configVersions 合并。`rowEvidence` 中每个元素为：

- schemaVersion=`AGGREGATE-ROW-EVIDENCE-V1`；originalRef、完整文件 SHA-256、manifest 文件 SHA-256；
- rowEvidenceId、rowSha256；从原文件解码得到的完整 AggregateRecordV1 行，保留原数值字符串和精度；
- 每个均值 Fact 必须携带自己的 rowEvidenceId、唯一原文件引用和自身 configVersions。

分组身份是以下固定有序 JSON 数组，UTF-8、紧凑格式、无 BOM、无末尾换行；字符串转义由既有 JsonV1Codec 完成，空 spec 为 JSON null：

`["AGGREGATE-GROUP-IDENTITY-V1", itemId, providerType.wireValue, actualSourceName, accessMethod.wireValue, sourceFingerprint, canonicalSpecCode, grain.wireValue, periodStart, periodEnd, validationStatus.wireValue, validationVersion, calculationVersion, calculationScale, displayScale, roundingMode.name, calendarVersion, currency, unit]`

rowEvidenceId=`aggregate-row-` + 上述 UTF-8 数组的 SHA-256 小写 hex。configVersions 不属于既有分组键，是该行自己的 lineage，必须完整保留，不并入身份键以掩盖重复组。

rowSha256 固定为 `SHA256(CsvV1Codec.encodeAggregate(List.of(decodedRow)))`：既有固定表头和一行规范解码内容，UTF-8、CRLF、无 BOM，完整 inputRefs 按原模型规则排序，avg/min/max 保留 calculationScale。不是原文件行号，也不是对工具 Map 或报告自述 JSON 求摘要。完整原字节始终由文件 SHA 和 manifest 绑定；旧 CSV 表头经解码后的规范行摘要不冒充原字节摘要。

## 校验与快照

1. 先验证完整文件及相邻 manifest，解码原 CSV；逐行检查 item/grain/year 与 originalRef 一致，同完整分组身份重复即拒绝。
2. 从真实解码行生成全部身份及摘要，再核对 Fact 的行 ID、原引用、值、周期、校验结论/版本、计算/日历版本、configVersions、来源、指纹、单位币种。不同组可以合法分别使用 `[5,12]`、`[5]`。
3. 复用 DEC-069 快照，保持原文件和 manifest 的准确字节、originalRef 映射及必要依赖闭包；512 文件/32 MiB 限制不变。原 immutable raw 仅引用，不复制整个仓库。
4. V3 重读先验证快照/映射/manifest/闭包，再从快照视图重新生成行级证据和核对 Fact，不信报告提供的 row、行摘要或 lineage；正常追加不能把最新文件替换为旧报告依据。
5. 不完整快照不产生成功报告。无自动回收，不删除被引用证据。旧 V1 文件级异构拒绝测试继续有效。

## 云端与 Java 分离

唯一生产云输出为 `{sections:[{kind,text,factIds}], recommendations:[{text,factIds}]}`。kind 为 OVERVIEW/TREND/METRICS/WARNING/QUALITY；1～10 段，每段 1～1000 字、1～8 个不重复引用，建议最多 4 条。Spring AI 1.1.8 BeanOutputConverter 提供 schema，JSON_OBJECT 为兼容基线；不升级框架、不改地域/模型/endpoint。禁止占位符作文作为第二生产协议。

上下文按 scope/latest/trend/aggregates/quality/currentWarning 组织，历史日线仅有限样本；聚合按真实 source/spec/周期/状态/计算及配置版本保留分组。无本地路径、hash、manifest 内容或秘密。Java 独立显示模拟性质和不完整周期，模型不能移除。

先做 JSON/长度/引用检查，再按段检查角色：均值段只引用一个组；最新值与变化量、当前值与基准、阈值及百分号、日期、单位、来源、趋势/风险/确认分别绑定。数字支持等价小数和千分位；全局碰巧存在同一数字不构成依据。错误段不保存原文；严重秘密/注入、不可解析或无合格段落整体 NONE。

FULL 表示本次返回段落全部被采用，PARTIAL 表示部分独立段落采用，NONE 表示只用 Java 事实。每段独立，不接受“上述/上一段”等不可校验依赖；建议仅依赖已采用段的事实。不将通过结构和关键主张检查说成任意自然语言的语义证明。

云段和模型建议单独存于 narrative，Java claims 始终为 Java 事实句。V3 重读重新检查被采用段的引用、关键主张和 evidenceRefs。 rejected text、密钥、原始模型正文不写报告或诊断。

本批每次请求最多一次模型端口调用，不做内容修复重试；`attempts` 是模型端口尝试次数，不是费用账单或 HTTP 往返证明。未配置可能不发 HTTP，实际云验收另按脱敏请求记录计数。单次模型最大输出 2200 token；共享既有 30/75/85/90 秒工具/云/提交准入/客户端预算，取消和有限线程池边界保持不变。

## 查询计划与界面

QueryPlanner 统一同义词、主题和就绪检查。显式主题优先；明确日期与所选日期冲突时拒绝并提示核对。没有日期时按最新可用业务日期所在月起至该日查询；没有可用数据则使用当前上海日期形成可理解的无数据结果。默认发现范围最多回看十年，工具仍有原范围限制；跨年均值按实际起止年查询，并过滤到请求周期。

页面呈现生成时范围与截至日期、Java 事实卡片、各均值分组自己的配置版本、适用预警及确认、分段智能解读、模型观察建议、折叠依据/步骤和完整 Java 事实报告。前端不计算业务值，不输出原始 JSON，不把 PARTIAL 标作完整云成功。确认不改变风险事实。


云上下文上限：问题 2000 字符、最多 8 个持久化均值分组、AnalysisContext 紧凑 JSON 20000 字符；超过时不发云请求，保留可核验 Java 事实并提示缩小范围。历史日线最多向云发送前两个样本，其余通过最新值/趋势角色表达；未发送历史样本的 factId 不可被模型猜测引用。上限只约束云端解释，不改变 CSV 或统计结果；快照仍有既有文件数/字节上限。


V2 聚合 Fact.businessDate 明确为该行 inputRefs 中最后一个实际业务日期（Java 比较 ISO 日期，不是周期起点或文件修改时间），每组分别展示样本截至日。重读时必须与真实行的 inputRefs 一致；periodStart/periodEnd 仍是持久化统计周期，不把查询子区间冒充整月统计。
