# Agent 90 秒与结构化引用兼容性收口（2026-08-21）

## 结论

- Agent 前端请求使用独立 `90000 ms` 超时；普通 API 继续使用全局 `15000 ms`，避免扩大非 Agent 请求等待窗口。
- 云 LLM HTTP 默认超时、配置脚本默认值和部署示例统一为 `90s`；允许范围仍为 1–120 秒。
- Phase B 向模型提供完整且可复制的 `factId/itemId/value/unit/currency/businessDate/validationStatus/actualSourceName/evidenceRef`，并传入明确 allowed 列表。
- qwen-plus Phase B 使用 OpenAI-compatible `response_format=json_object`；模型输出仍必须经过原有 Java 严格解析与 `AgentResponseVerifier`，未知引用、跨事实数值、伪造日期/来源和秘密注入均继续 fail-closed 到 `JAVA_TEMPLATE`。
- 未改动 7 个只读 Tool 边界、EvidencePack 权威性、前端零业务计算规则或其他 Day1–Day10 业务合同。

## 根因与修复

1. 旧前端所有请求共用 15 秒 timeout；Agent 包含 Phase A 工具选择、Java 工具执行和 Phase B 解释，正常请求可能晚于 15 秒返回，页面先显示“服务暂时不可用”。
2. 旧 `LlmFact` 未携带生产 `factId`，但结构化响应又要求模型引用 factId，兼容模型只能猜测，导致 `UNKNOWN_FACT_REFERENCE` / `UNSUPPORTED_CLAIM_REFERENCE`。
3. 修复后页面在等待期间禁用重复提交，超时显示专用中文提示；后端向模型公开精确 opaque ID 与业务元数据，但不放宽任何验证规则。

## 验证

- Backend full regression：127 suites / 656 tests / 0 failures / 0 errors / 9 gated skips，BUILD SUCCESS。
- Structured targeted：9/9 PASS（含固定 prompt bytes/allowed IDs 与既有结构化攻击）。
- Previously failing security paths：12/12 PASS（secret injection 与 FORMAL/DEMO evidence isolation）。
- Frontend：6 files / 37 tests / 0 failures；`vue-tsc --noEmit + vite build` PASS。
- Desktop：31/31 PASS。
- Alibaba Bailian real cloud gate：`CloudLlmRealApiAcceptanceTest` 1/1 PASS；provider=`openai-compatible`，model=`qwen-plus`，timeout=`90s`。API key 仅从用户环境变量读入测试进程，未打印、未写入仓库、日志或证据。

## 安全说明

本次未修改主机网络、代理、DNS、Hyper-V、Docker、WSL、系统时间或系统服务。真实云端 Gate 使用用户已明确授权的现有环境变量，并只记录非敏感结果。
