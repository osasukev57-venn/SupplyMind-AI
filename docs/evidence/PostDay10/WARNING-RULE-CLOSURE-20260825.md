# Post-Day10 正式预警与展示一致性闭环证据

> 日期：2026-08-25
> Decision：DEC-064
> 状态：IMPLEMENTED / FULL_REGRESSION_PASS

## 1. 关闭的问题

1. LocalImport 批量行在正式处理后不再永久显示“待处理”；每行按 runId 使用后端最终 `PUBLISHED/REJECTED` 结果覆盖受理阶段展示。
2. Agent 对外 `answer` 只投影持久化、Java 已验证 `AgentReport.claims[0].text`；被拒绝的云模型 JSON envelope 不再作为用户答案输出。前端明确区分“Java 事实报告”与“云端智能分析（Java 事实约束）”。
3. 预警页不再要求用户先做 demo 试算；每个启用正式监测项自动拥有可用默认规则，可逐项版本化覆盖、恢复默认、立即评估并查看/确认正式 WarningRecord。

## 2. DEC-064 默认

- material：Asia/Shanghai 自然日回看 7 日，双向绝对变化率达到 5%（含等于）触发。
- 其他类型：回看 1 日，双向绝对变化率达到 1%（含等于）触发。
- 当前点/基准点均来自已发布、已校验 daily；缺失不补0，损坏/冲突/单位不一致 fail-closed。
- BigDecimal 独占正式数值计算；前端只显示后端 `thresholdPercent/changePercent` 字符串。
- override active/history/manifest 原子保存；WarningRecord 不可变；确认继续绑定 DEC-061 sidecar。
- EXT-07=`ACCEPTED_VERSIONED_DEFAULT`；EXT-08=`OPEN_EXTERNAL`。

## 3. 定向验证

- `FormalWarningEvaluationServiceTest`：4 PASS（等阈值、低阈值、缺失不补0、停用）。
- `WarningRuleManagementServiceTest`：1 PASS（全启用项默认、覆盖、重启、恢复默认、历史版本）。
- `WarningApiMvcContractTest`：7 PASS（含 5% 展示与用户 3%→BigDecimal 0.03 转换）。
- `AgentApiTest`：4 PASS（answer=verified claim、禁止 raw JSON envelope）。
- Frontend warning/agent/pages/governance：28 PASS。
- 最终定点：Agent API + warning 核心/API/legacy preservation 共 26 PASS；Agent 多条已验证 claim 按顺序拼接，禁止丢失后续结论或泄漏 raw JSON envelope。

## 4. 保持边界

- 未修改发布资格、daily/aggregate 算法、DEC-050/051/052/055、DEC-061 ack 绑定、Synthetic 隔离。
- 未增加数据库、RAG、Vector Store、写工具或自动调价。
- 未修改宿主机网络、代理、Hyper-V、Docker/WSL、注册表或系统时间。

## 5. 完整回归

- Backend：131 suites / 669 tests / 0 failures / 0 errors / 9 skipped；skip 均为既有真实网络、真实 Cloud 或物理时间门禁，本轮未新增。
- Desktop：31/31 PASS。
- Frontend：6 files / 38 tests / 0 failures；`vue-tsc --noEmit + vite build` PASS。
- 本轮未发起真实联网采集、真实 Cloud 计费请求，也未修改主机网络、代理、Hyper-V、Docker/WSL、注册表或系统时间。
- 最终制品 SHA-256 由提交后的 clean deterministic package 生成，并以 release 同名 sidecar 为唯一权威值。
