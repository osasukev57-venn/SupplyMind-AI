# 原始验证证据

运行日期2026-10-22（Asia/Shanghai），归档收口日期2026-10-25。Base 1adae0f3e497d75d2d9c1dbe749f46b292700af5，branch codex/business-loop-fixes。没有覆盖旧候选证据；所有测试仅临时数据与假HTTP/模型。

## 有效最终结果

后端命令：子进程JAVA_HOME=D:/Dev/SDK/Java/jdk-17，mvn.cmd -o -f backend/pom.xml test。backend-final-full.log于15:42:16结束，用时4:59；backend-final-surefire原始XML实际汇总711 total / 702 passed / 0 failures / 0 errors / 9 skipped，Java17.0.19。逐项跳过原因在verified-counts.json，均为既有真实网络、真实云、物理时间及真实raw门禁，未增加skip。

前端：npm.cmd --prefix frontend test，frontend-corrected.log，15:26:20开始，42 passed。frontend/node_modules/.bin/vue-tsc.cmd --noEmit -p frontend/tsconfig.json，退出0，frontend-typecheck.log为空即无诊断；不打新包。

桌面：node --test desktop/test/*.test.js，desktop-tests.log，34 passed / 0 failures；保留端口禁止8080、最多10次及实际bind失败处理，正常启动、第二实例、退出/残留相关自动化包含在本次运行。

生产默认慢模型实测75568ms，诚实降级JAVA_TEMPLATE，持久化后可验证读取；慢工具缩短预算测试164ms，取消后拒绝迟到事实。见最终日志BUDGET_*和RequestBudgetTest原始XML。首次全量对应75530ms/153ms，早期定向161ms，不能混成同次结果。取消和HTTP剩余预算亦包含在8项预算测试；无真实付费云。

## 有限稳定性复验

预先限定两次，测试选择BusinessLoopIntegrationTest,CurrentAcquisitionServiceTest,ScheduledEntryPostFixAttackTest,ScheduledGuardProductionPathTest。

1. mvn.cmd -o -f backend/pom.xml -Dtest=<上述四类> test；stability-1.log，15:17:25结束，37.696s；7/7，原始XML在stability-1-surefire。
2. 同命令加-Dsurefire.runOrder=reversealphabetical；stability-2.log，15:26:19结束，24.612s；7/7，原始XML在stability-2-surefire。

原25秒断言未延长；finally中shutdownNow+awaitTermination保留。此前历史超时未重现，不声称全部根因已证明。两次全量中的业务链测试为回归验证，未冒充额外专项重跑。

## 全部中间结果（包括失败）

- failure-existing-tests.log：旧SHFE4通过；failure-contract-tests.log：新增8+旧4共12通过，failure-surefire原件。
- snapshot-first-tests.log：14项、2错误，缺发布timeline夹具；snapshot-first-failed保留XML。
- snapshot-fixture-tests.log：19项、3失败，预警夹具不存在的聚合引用及报告计数误包含blob；保留日志，该次XML未单独留存，不冒称有原件。
- snapshot-attack-tests.log：16项、6错误，新夹具无显式日期未捕获日线；snapshot-attack-fixture-failed保留快照类7项原件。
- snapshot-verified-tests.log：26通过；budget-targeted.log：18通过。两次日志保留，XML已由后续运行覆盖，不作为最终源码全量原件。
- frontend-tests.log：40通过/1失败，新token/signal尚未纳入精确请求参数断言；修复后42通过，新增取消测试，原90000ms断言保留。
- backend-full.log：首次全量710项、698通过、0失败、3错误、9跳过；backend-first-full-surefire及first-full-source-sha256.json保留。生产修复不可信路径的降级claim引用，原攻击测试不变。
- closure-corrected-targeted.log：40项、2失败。新攻击夹具只含raw引用，未进入可变快照分支；closure-fixture-failed原件。
- closure-verified-targeted.log：40项、1失败。混合版本测试已通过；中断测试误观察临时父目录，改为夹具真实dataRoot；closure-path-fixture-failed保留该类8项XML。
- snapshot-final-targeted.log：8通过（15:36:50，17.900s），snapshot-final-surefire原件，增加入口断言并证明实际blob已写出；之后最终全量711项通过702、跳过9。

以上后端专项均mvn.cmd -o -f backend/pom.xml -Dtest=<日志中Running所列测试类> test，使用同一现有Java17。没有失败后盲目重跑；每次失败明确修复代码或夹具后验证，原失败日志保留。

## 复核方法与边界

summarize-evidence.ps1从各目录TEST-*.xml生成verified-counts.json，含逐项skip原因及java.version；java17.txt保存实际运行时版本。final-source-sha256.json保存最终源码/测试/桌面文件字节摘要。2026-10-25核验后端、前端源码mtime均未晚于最终回归结束，Git范围与中断前一致；没有借用旧候选测试声明。

证据不位于target，后续clean不会覆盖。未保存运行仓库数据、完整环境变量、真实密钥或完整HTTP headers；XML包含标准JVM测试属性和临时测试路径。SHA256SUMS.json记录本目录文件摘要（自身除外）。本报告不替代独立审查，不包括真实来源、真实云、EXE或新包验收。
