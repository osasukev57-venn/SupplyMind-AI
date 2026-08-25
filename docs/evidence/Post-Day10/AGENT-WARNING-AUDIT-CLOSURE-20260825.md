# Agent事实优先与预警确认审计闭环证据（2026-08-25）

- Implementation commit：`63cc9a8`
- Decision：`DEC-065 EFFECTIVE`
- 范围：Agent StandardFactPack/云端润色/Java逐事实验证与证据回绑；预警确认记录API/UI/Agent可查询审计。

## 冻结调用链

`已发布/已校验文件与有效预警证据 → Java受控工具 → StandardFactPack（含Java计算的趋势、变化量、阈值、风险、确认状态） → qwen-plus仅返回answer+usedFactIds → Java校验数字/日期/单位/来源/趋势/风险/确认语义 → Java按factId恢复evidenceRefs → AgentReport`

无可引用事实不允许云端形成业务结论；未知/重复factId、伪造数字/日期/来源、相反趋势或确认状态、模型泄漏factId/evidence路径均fail-closed并展示Java事实报告。云端不接收本地证据路径或SHA。

## 预警确认闭环

- 确认继续使用DEC-061不可变`.ack.json + manifest` sidecar，不改写WarningRecord。
- API/UI可再次查询确认时间、处置备注、warningRef、warningFileSha256与ackRef。
- warning.explain只接纳经manifest、warningId/ref和原warning SHA验证的确认sidecar；篡改/损坏确认永不显示为ACKNOWLEDGED，也不能成为Agent事实。

## 验证结果

- Backend完整回归：`131 suites / 671 tests / 0 failures / 0 errors / 9 skipped`。
- Agent/Day6/Spring AI定向回归：`140 / 0 / 0 / 0`（加入反向趋势语义攻击后完整后端仍全绿）。
- 预警确认定向：`WarningAckStoreTest 15/15`，含确认进入Agent证据与篡改fail-closed。
- Frontend：`6 files / 39 tests / 0 failures`，`npm run build PASS`。
- Desktop：`31/31 PASS`。
- Alibaba Bailian `qwen-plus`真实连通性：`CloudLlmRealApiAcceptanceTest 1/1 PASS`；API Key仅从用户环境传入进程，未输出或持久化。

## 安全边界

未修改宿主机网络、代理/VPN、DNS、路由、防火墙、Hyper-V、Docker/WSL、系统服务、注册表或系统时间；未触碰用户的`测试运行/`目录。