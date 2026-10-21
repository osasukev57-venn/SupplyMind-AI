# 候选验证原始证据

本目录不会被backend/target clean覆盖。原始runner XML、txt和日志未经人工改写；.gitattributes禁止换行转换，保留字节摘要。计数由实际XML/输出提取。不是独立审查PASS。

- historical-jdk21：2026-10-21 10:18:40完成的历史全量，682 total / 673 passed / 0 failures / 0 errors / 9 skipped，Java21.0.11。命令mvn.cmd -o -f backend/pom.xml test。对应自审增补前，不代表最终候选。
- initial-self-review：Java17.0.19首次受影响专项，5 total / 4 passed / 1 failure / 0 errors / 0 skipped；异步等待超时。与前端并行运行，保留失败原件。
- current-jdk17：Java17.0.19串行受影响专项，2026-10-21 10:55:36结束，5 total / 4 passed / 1 failure / 0 errors / 0 skipped。故障注入阶段25秒等待失败。命令见summary.json。不是全量。
- frontend-current.log：npm.cmd --prefix frontend test，2026-10-21 10:35:39开始，41 passed，Duration231.17s。
- frontend-build-current.log：接上述测试运行npm.cmd --prefix frontend run build，vue-tsc及Vite构建成功。
- desktop-current.log：接构建运行node --test desktop/test/*.test.js，31 total / 30 passed / 1 failed / 0 skipped；8080未排除。Node版本及计数见frontend-desktop-summary.json。此前对话31通过未找到同次原始日志，不作当前证据。

Java17为已安装Microsoft17.0.19（D:/Dev/SDK/Java/jdk-17），仅测试进程设置JAVA_HOME；未修改系统环境。历史Java21为Temurin21.0.11。跳过原因见historical-jdk21/summary.json的逐suite原始skip消息，未新增skip。

implementation-scope.json为实现/测试/已有文档明确清单；本目录与docs/16另属证据文档。tested-source-sha256.json绑定最后受影响专项及后续不改源码的验证。protected-learning-inventory.json仅记录原有学习文件哈希。desktop-base-comparison.json核对端口代码与基线一致。SHA256SUMS.txt在收口时生成，不含自身。

未归档真实运行dataRoot、密钥、完整环境变量；归档前检查敏感属性名及常见密钥字面量，未输出其值。真实来源和计费模型gate未开启。后续全量若执行，必须另存并标明时间，不能累加混合target目录冒充当次运行。

## 最终有效候选验证

final-jdk17 为最终权威后端运行：685 total / 676 passed / 0 failures / 0 errors / 9 skipped，Microsoft OpenJDK 17.0.19，完成时间 2026-10-21T16:36:12+08:00。命令：仅进程JAVA_HOME=D:/Dev/SDK/Java/jdk-17；mvn.cmd -o -f backend/pom.xml test。原件、自动计数、逐suite skip原因均在该子目录。相比历史682新增3项SHFE失败留证参数场景；未新增skip。tested-source-sha256.json与final-input-source-sha256.json一致，验证期间源码未变。

full-jdk17-test-path-error 保留前一轮685项中的1个错误：新测试错用了不存在的published文件引用；修正为真实raw对应timeline的PUBLISHED及验证状态核验后全量通过。没有修改生产发布逻辑或延长25秒断言。两次更早专项超时保留，不以最终成功否认发生过失败；未确认其环境根因。

当前前端41通过及构建通过。当前桌面30/31，端口8080缺陷尚未修复；最终后端通过不覆盖桌面失败或剩余raw-first合同阻塞。原始XML/log/txt按binary diff保留字节，JSON摘要可直接审查。
