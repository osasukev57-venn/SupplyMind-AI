# 本地验证证据索引

基线 `2a26ea9016f5014e9d0fe9be5e5d964f2dced88f`；代码/测试/合同提交 `e5b77c5b675b3bd1a05dc48ba97c0ba320a454cf`。本目录独立于 target，后续 clean 不会覆盖。本地自审不是独立审查 PASS，不是最终交付。

## 本次有效验证

| 验证 | 命令/文件 | 实际结果 |
|---|---|---|
| 后端 clean 全量 | `mvn -o -f backend/pom.xml clean test`；backend-full-final.log、backend-full-final-command/completion.json | 142 suites；739 total / 730 passed / 0 failures / 0 errors / 9 skipped；2026-10-26 14:26:13 完成 |
| Java | java17-final.log；使用进程级 JAVA_HOME 指向已有 JDK | Microsoft OpenJDK 17.0.19+10 LTS |
| 冻结输入 | final-inputs.json | 全量开始前 544 项构建/测试输入；全量结束后全部摘要相同 |
| 最终格式收口 | candidate-inputs.json、post-format-cross-config.log/summary.json | 全量后仅移除一个测试末尾空行、为黄金文件声明合法 CRLF；受影响跨配置测试再跑 1/1，无跳过 |
| 定向链路 | fixture-preactivated-focused.log/summary.json | 17/17，无跳过；跨配置、原问题、14 项行证据与报告攻击、动态业务闭环 |
| 前端 | `npm test`、`npm run build`；frontend-current.log、frontend-build-current.log | 46/46；vue-tsc 与 Vite 生产构建通过 |
| 桌面 | `node --test desktop/test/*.test.js`；desktop-current.log | 34/34，无跳过 |
| 默认慢模型 | 全量 RequestBudgetTest 的输出 | 75.599 秒返回已保存且可核验的本地报告；8 项预算/取消/资源测试通过 |

原始后端 XML/txt/dump 保存于 `backend-full-final-surefire/`。结构化汇总的每个原件有 SHA-256，可交叉核对；原件保存在本地独立目录，不必混入每次源码提交。明确区分：focused 运行可能保留 target 中先前的报告，汇总仅统计选定 suite；full-final 在 clean 后运行，全部 142 suites 均属于该次全量。

9 项既有跳过原因逐条保存在 backend-full-final-summary.json：真实外部采集总链1、真实云1、物理 Windows 改时1、真实 raw 的聚合/日线/发布/校验4、PBOC 真实网络与真实闭环2。没有为本批加 skip。当前没有新的真实来源、真实计费云或物理改时授权。

## 失败及复验

`run-ledger.json` 区分每次已执行的 Maven 运行；对应原始日志和先前 XML 目录保留，不覆盖失败原件。不能把迁移过程中的历史通过数或失败后的旧 XML 混成最终候选计数。

- 初期正向文案分组标签过严、旧协议测试提前拒绝、预警无单位却在假模型文案中加单位、提示词字段及基础预算原因码差异，都有独立失败日志。修复后才执行下一轮。
- 行级 FORMAL 隔离曾错误地要求旧夹具中不存在的逐日期连接；改为检查整个依赖日线文件的正式 raw 路径与非 DEMO 类型。精确聚合行、来源/版本、文件/manifest 和依赖闭包仍独立校验；旧 V1 标量严格性未放宽。
- focused-current：37 total / 34 passed / 3 failures。两项是新攻击用例名未构造攻击文案；另一项是首次发布仍含 SHFE 而超过 25 秒。原始线程栈位于 Windows CreateFile/RawAcquisitionStore，不足以宣称死锁或全部 I/O 根因已证明。
- 测试临时配置改为 Spring 启动前通过真实 ConfigActivationStore 原子创建，不依赖 Ready 监听器顺序。首发来源分阶段运行，混合重放/冲突隔离继续同跑；25 秒完成断言、异步执行、关闭线程等待和全部业务断言保留。此测试不承诺默认多来源首次发布 25 秒完成。
- 之后预先确定一次 17 项定向验证、一次 clean 全量、格式收口后一次受影响测试；三次实际通过。没有用无限重复、扩大 skip、延长断言或改期望值掩盖失败。

## 仍需包级和真实验收

下一步用该代码与最终本地候选构建独立 rc.3；包、原数据副本、生产 Spring AI 假模型、实际 EXE、重启/退出的结果另存本目录的最终交接报告。此索引不提前宣称通过。

中文复测手册：`demo-data/AGENT-V3-中文验收步骤.md`。真实云需按 REAL-CLOUD-ACCEPTANCE-PLAN.md 单独授权，最多6次正常调用、无自动修复重试；受控假模型不等于真实云通过。
