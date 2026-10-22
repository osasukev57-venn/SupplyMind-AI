# 本轮原始验证证据

Base f4c1cc9a74b5bc4e53a6854939d543762ec6c946；分支 codex/business-loop-fixes。
日期2026-10-22，Asia/Shanghai。后端结束时间以 backend-full.log / regression-summary.json 为准；各套件耗时见XML，XML未提供的时间不推算。前端起始14:17:03、时长26.88s；桌面随后执行，原始日志保留时长。

命令（仓库根目录PowerShell；JAVA_HOME仅在子进程选择已存在JDK）：

- JAVA_HOME=D:/Dev/SDK/Java/jdk-17
- java -version；mvn.cmd -version（版本原件见txt）
- mvn.cmd -o -f backend/pom.xml -Dtest=RequestBudgetTest test（budget-corrected.log，4/4）
- mvn.cmd -o -f backend/pom.xml test（backend-full.log，690/681/0fail/0error/9skip）
- npm.cmd --prefix frontend test（frontend.log，41通过）
- node --test desktop/test/*.test.js（desktop.log，34通过）
- git diff --check（通过；提交前再次检查索引）

surefire/是完整有效回归XML/txt原件，已复制出target，不受mvn clean影响。统计逐份解析XML并与Maven汇总行比对；9项跳过原因在backend-summary.json，均为既有真实源/真实云/原始数据或物理时间验收门禁，未新增skip。

tested-source-sha256.json是本次测试结束时源码/测试/构建清单，提交前核对无变化。protected-learning-check.json与上一候选用户学习材料哈希比对，全为true。

历史过程位于../business-loop-blocker-closure-20261021，引用但不改写：desktop.log为34通过；budget-test.log为最初3项通过；targeted.log为17项通过并首次复现历史报告；targeted-expanded.log为5项中1失败，expanded-failed-surefire为该次预算/链路原件。失败是item不匹配导致未进入慢模型分支，修正为fixture.ITEM并新增calls==1，保留超时及降级断言。
历史命令依次为node --test desktop/test/*.test.js；mvn.cmd -o -f backend/pom.xml -Dtest=RequestBudgetTest test；mvn.cmd -o -f backend/pom.xml -Dtest=BusinessLoopIntegrationTest,CurrentAcquisitionServiceTest,RequestBudgetTest,AgentFactFirstPipelineTest test；mvn.cmd -o -f backend/pom.xml -Dtest=RequestBudgetTest,BusinessLoopIntegrationTest test。后端使用同一Java17，原时间/总数见各日志。

没有归档运行数据、密钥或完整环境变量；XML归档前检查secret/password/api-key/token/env.*属性均无非空值。未进行新包、真实外部来源、计费云调用。自审与待确认合同见../../17-BLOCKER-CLOSURE-REVIEW.md。
