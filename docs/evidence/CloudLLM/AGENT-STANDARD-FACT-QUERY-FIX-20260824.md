# Agent 标准事实查询闭环修复证据（2026-08-24）

## 问题

已有已发布/已校验的本地导入数据，但 Agent 输入只包含 itemId、未显式提供日期时，
Java 编排仅执行 `series.resolve`，不会执行 `history.query`，因此错误返回
`STANDARD_FACTS_NOT_READY`。

## 修复

- 普通分析请求未提供日期、period、month、businessDate 时，以 Asia/Shanghai 当日为基准，
  使用“当月第一自然日到当日”的确定性查询窗口。
- itemId 输入框为空时，仅允许从问题中解析唯一启用监测项；完整 itemId、完整显示名称优先于
  sourceFieldKey/externalCode。
- 多个同分候选保持 fail-closed，要求用户明确 itemId，不跨来源意图猜选。
- Java 在工具执行后将解析出的 itemId 回填 EvidencePack scope。
- warning/period/businessDate 专用请求不启用默认历史窗口，保持既有证据隔离。
- VERIFIED、lineage、FORMAL/DEMO、云端输出校验 Gate 均未放宽。

## 验证

- `AgentFactFirstPipelineTest`：4/4 PASS。
- Agent/Day6/Spring AI 定向回归：139/139 PASS。
- 多候选标的边界测试：8/8 PASS（同 externalCode 多来源拒绝猜选，完整显示名可唯一解析）。
- 后端 `mvnw clean test`：663 tests，0 failures，0 errors，9 skipped。
- 独立预警隔离攻击测试曾在初版默认窗口上检出回归；收紧触发条件后复测 PASS，未修改旧攻击断言。

