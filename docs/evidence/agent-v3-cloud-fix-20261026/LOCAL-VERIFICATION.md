# Agent V3 真实云首轮问题：本地验证索引

Base：c337e1f56d56fb3540d3f252800f05fdc865307f；分支codex/business-loop-fixes。本地验证不是独立审查PASS或最终交付。最终SHA与rc.5包摘要由构建manifest及本目录最终交接记录绑定。

| 运行 | 实际结果 | 说明 |
|---|---|---|
| focused-first | 31 total /30 passed /1 failure /0 errors /0 skipped | 新校验误拒绝合法“底层记录存在VERIFIED_WITH_NOTICE”；失败原件完整保留，修正存在性判断，未删除断言。该运行在后续短句/大小写用例加入前。 |
| focused-semantic-fixed | 21/21，0跳过 | 当时19项语义及2项生产提示词测试通过。后续新增简略表达与大小写用例，不能冒充最终全部用例数。 |
| 探索性全项目clean | 143 suites；759 total /749 passed /1 failure /0 errors /9 skipped | 旧趋势正向夹具返回OVERVIEW，被新范围规则拒绝。该次全量没有PASS，原日志backend-full.log、full-command/completion.json及backend-full-first-surefire/完整保留。 |
| 最终全部Agent＋业务闭环 | **37 suites；227 total /226 passed /0 failures /0 errors /1 skipped** | backend-related-final.log、related-command/completion.json、backend-related-final-summary.json及原XML。包含新增24项真实云文字回放/语义、生产提示词、跨配置原问题、预警/确认、报告/攻击、预算与业务闭环。 |
| 前端 | 47/47 | frontend.log，包含质量派生指标/底层样本状态显示区分；原页面和分段采用覆盖保留。 |
| 桌面 | 36/36，0跳过 | desktop.log。未修改桌面源码、8080约束、最多10次选择和启动宽限。 |

使用已有Microsoft OpenJDK **17.0.19+10 LTS**，仅进程级JAVA_HOME；版本原件java17.log。Maven均离线`-o`。最终37个测试类名单及实际命令在related-command.json；不是把多次报告拼成一次全项目零失败。最终没有重新执行全部无关模块。

探索性全量9项跳过均为既有真实来源/真实raw/物理改时/真实云门禁，原因逐项保存。最终相关运行只有既有真实计费云1项跳过（supplymind.llm.real未开启）；无新增skip，无真实计费调用。

final-inputs.json记录探索性全量开始时548个输入；candidate-inputs.json为最终相关回归的548个输入。两者只差SampleValidationSummary、RealCloudQualityRegressionTest、AgentPipelineIntegrationTest三个文件：补齐短句/大小写校验和真实夹具主题修正。最终全部Agent回归覆盖这些变化；完成后输入摘要全部不变。原全量的其他模块结果保留为历史覆盖，不冒充新候选clean全量。

每份汇总只统计明确选择的suite。focused及最终相关原件目录可能另含前次未选中报告，rawFileDigests绑定所有归档原件；选中的类和计数才属于对应运行。原始log/XML不规范化或覆盖，派生摘要使用LF便于审查。

backend-package.log记录相关回归通过后的离线JAR构建。打包时-DskipTests仅复用已执行回归，不是改测试或增加跳过；干净候选ZIP后续用既有package-clean流程、独立新staging和新版本名构建，不覆盖rc.4或用户数据。

真实云原件在../agent-real-cloud-20261026/，不改写、不重跑。不掌握被拒模型段原文，未把它们推测为误拦。新离线fixture保留已采用文字和真实证据包，附三份原件SHA，可逐一核对。

本轮真实云新增0，累计已用1/6，剩余最多5，安排见CLOUD-RETEST-PLAN.md。包级受控模型结果及实际产物另记最终交接；不把本地假模型或Java报告当真实云成功，不调用Computer Use。
