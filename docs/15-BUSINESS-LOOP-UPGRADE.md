# 小步业务修复版本

批准日期：2026-10-20。基线 main / 14d1754；分支 codex/business-loop-fixes。

## DEC-067：执行口径

用户批准先连通动态采集、正式预警、确认记录与 Agent。启动、刷新、周期任务按活动配置处理正式自动来源，复用现有当前采集、校验发布与聚合。周期显式使用上海时区并保留回拨守护。聚合成功后以 DEC-064 现有规则评估启用项；仅增加触发时机，不改阈值、计算和文件格式。预警确认保持不可变 sidecar，FORMAL Agent 按验证后的预警身份隔离演示证据。

修复日期依赖攻击测试及重复模板 token。现有 v0.9.0、运行数据、学习证据和交付物保留，本轮不发布。历史报告快照、相对日期/会话、超时体验、日历、成本模型和大规模架构优化不在本批。自审依据 DEC-056 补齐 SHFE 非空 HTTP 响应在解析前留证；空实体/缺 Content-Type 的冻结模型限制仍为阻塞，详见 docs/16。

## 状态

2026-10-21 候选自审：下文 682 项为自审新增修改前的历史全量，不作为新增修改后的验证结论。自审补齐 SHFE 非空响应 raw-first，并增加启动、重放、冲突和预警故障注入断言。候选仍有明确阻塞；当前结果、原始证据及最终本地提交状态见 docs/16 和独立 evidence 目录。本轮不推送、不合并、不发布。

## 配置变更后的来源身份兼容

连续业务测试发现：同日新增 GBP 时，PBOC 来源 acquisitionId 只含 URL/原文摘要，旧来源文件绑定旧 configVersion，新 raw 绑定新版本，发布前身份检查拒绝。修复为配置版本大于 1 的新获取使用版本后缀；已有 raw/source 文件不重写，同业务键同 payload 继续复用旧 raw。JSON schema、报价与摘要不变。

## 验证范围与复现

- 后端：`mvn.cmd -o -f backend/pom.xml test`；全量结果见本记录后续摘要。
- 前端：`npm.cmd --prefix frontend test`（41/41），`npm.cmd --prefix frontend run build`（类型检查与构建通过）。
- 桌面：`node --test desktop/test/*.test.js`（31/31）。
- BusinessLoopIntegrationTest 使用正式 Spring 装配与实际 provider/service，替换 HTTP 传输、Clock 和模型端口，临时 dataRoot。覆盖首日采集、停用 EUR、新增 GBP、次日周期任务、USD/GBP/ADC12 日线及聚合、自动正式预警、不可变确认、Agent 事实解释、演示隔离、报告与确认文件重新读取、篡改拒绝。重新读取不等于完整应用重启验收；未执行真实网络、真实云模型、物理桌面和新版本打包发布。
- ActiveCurrentCollectionServiceTest 覆盖单项异常不阻塞其他自动项、人工项不伪装成功、上海时区；前端同时覆盖 FAILED/PARTIAL_SUCCESS 的未完成项展示。
- Agent 攻击测试固定业务日期并断言模型端口确实调用，覆盖无依据数值、趋势夹带、事实集合不一致和重复 token。

## 修复中发现的问题

当前采集原先把 raw 已保存当作成功，现要求发布及校验通过。动态配置测试原先使用晚于固定 Clock 的日期，按真实门禁修正 fixture。启动恢复原先绕过注入 Clock，导致固定业务时间的周期验证被误判回拨，现统一使用注入时钟，生产默认时区不变。构建发现前端状态联合类型缺少 PARTIAL_SUCCESS，已补齐并通过构建。

## 回退与交付边界

工作保存在 codex/business-loop-fixes；未修改 v0.9.0 标签或旧 ZIP。回退使用既有 v0.9.0 交付物，新生成的 raw/source、预警和确认文件均不就地重写。本批文件格式兼容，不执行数据迁移。正式发布需另行完成真实来源、目标桌面与新包验收，生成独立版本及独立交付物。

全量回归首轮 682 项（9 项显式跳过）发现两项旧报告月份断言和两项旧周期装配问题。更新测试后，字节快照断言进一步暴露重复采集重建 staging 的缺陷：BackfillOrchestrator 现在读取并校验既有 timeline 的 manifest、runId 和 rawRef，再复用状态，不重置历史。周期测试改用实际新链路及同步测试执行器，保留回拨抑制和业务文件字节不变断言；每个币种独立请求，HTTP 次数随之更新。报告路径按持久化 createdAt 的上海月份核对，不再把业务月份等同报告生成月份。

## 历史全量验证结果（2026-10-21 10:18，自审增补前）

后端离线全量命令退出码 0，BUILD SUCCESS：682 项，673 通过，9 跳过，0 失败、0 错误。日志：backend/target/business-loop-20261021-test.log；逐项报告：backend/target/surefire-reports（构建目录可能被后续构建替换）。2026-10-20 的第二轮全量运行中断，没有最终汇总，不作为成功证据；本次重新完整运行后才确认通过。

跳过项为现有条件控制：AtSrc002AcceptanceTest、CloudLlmRealApiAcceptanceTest、Day5TimeContractHarnessTest 中的物理时间测试、AggregateRealRawEvidenceTest、DailyRealRawEvidenceTest、PbocOfficialWebRealNetworkAttemptTest、PbocRawClosedLoopSmokeGateTest、PublishRealRawEvidenceTest、PbocValidationRealRawEvidenceTest。未将这些项目计为通过。

前端 41 项通过、类型检查与生产构建通过；桌面壳 31 项通过（2026-10-20 执行，此后相关源码未改）。git diff --check 通过。v0.9.0 仍指向 14d1754156a6c9bab5703cb21afc75d8405c17cd；旧交付物和 docs/learning-evidence 未改动。

## 候选自审更正

原有桌面31通过的对话记录未找到同次runner原件，本次实际重跑为30通过/1失败（8080端口未排除），不得以历史结果覆盖。前端当前41通过及构建成功。Java17早期专项两次超时和新增测试误用published文件路径的失败全量均原样留存；路径断言已改为核验真实raw对应timeline的PUBLISHED及验证状态，没有修改生产发布语义或放宽断言。候选最终结果见docs/16。

候选最终结果（2026-10-21 16:36）：Java17实际全量685/676通过/0失败/0错误/9跳过；前端41通过及构建通过；桌面30通过/1失败。历史失败原件保留，完整链路当前通过；剩余阻塞见docs/16，不作放行或最终交付结论。


## 2026-10-22 兼容合同收口（取代上述当前阻塞状态，不删除历史结果）

用户已批准 DEC-068 独立失败获取证据、DEC-069 新报告按需快照及 DEC-070 正常环境90秒响应目标。本轮实现和实际回归见 [兼容合同收口自审](18-COMPATIBLE-CONTRACTS-REVIEW.md) 与独立 evidence/compatible-contracts-20261022。旧 V1/raw 合同、v0.9.0、运行数据及学习材料保留；上述682/685等数字均为历史候选结果，不作为本轮回归计数。相对日期/会话等未批准扩展仍延期。本轮只形成供独立审查的本地候选，不代表发布或最终交付完成。
