# 小步业务修复候选自审（2026-10-21）

这是实现者自审，不是独立审查 PASS，也不是最终交付完成。仅形成供用户及独立审查窗口复核的本地固定候选；不推送、不合并 main、不发布、不生成新包。

## 基线与范围

Base：14d1754156a6c9bab5703cb21afc75d8405c17cd（v0.9.0）。Branch：codex/business-loop-fixes。唯一 Candidate 完整 SHA 由最后本地提交产生并在会话报告给出，避免文档自引用 SHA。文件级清单见 evidence/business-loop-candidate-20261021/implementation-scope.json；证据目录与本文件也属于候选。用户原有 docs/learning-evidence 不提交；学习文件哈希单独核对。未读取/归档密钥、完整环境变量或真实运行数据。

## 生产调用链及 A 项

ApplicationReady → CurrentAcquisitionService.trigger；POST 当前刷新与上海时区周期守护均进入同一入口。AtomicBoolean inFlight 拦截重入，单线程 executor 执行 ActiveCurrentCollectionService；每周期读取有效配置，逐个启用的外部自动来源 → BackfillOrchestrator.collectCurrent → provider CURRENT → raw/source、raw、timeline → validation → publish → daily → aggregate → 正式规则评估。人工/导入项不进入自动成功列表，非 FORMAL 配置不自动采集。单项异常转为失败项，其他项继续，汇总 FAILED/PARTIAL_SUCCESS。动态新增配置本身仍复用既有工作流入口，未扩大为全局任务调度重构。

EUR 停用/USD 保持、GBP 新增后次日周期、SHFE 两个材料意图、启动事件、周期上海时区与回拨保护，由当前测试和源码分别核对。CurrentAcquisitionServiceTest 保留重入与异步状态断言；周期守护测试保留回拨前后字节快照断言。SHFE 重复独立启动监听已移除，统一当前采集队列。范围不是全局事务锁：配置写入/历史补采等既有并发语义未改。

## B 项：身份、幂等与历史保护

PBOC source acquisitionId 在 configVersion > 1 时增加版本后缀，防止新配置 raw 绑定旧配置 source。已存在同业务键+同 payload 时复用旧 raw（包括其旧 configVersion/acquisitionRef）；不把新的观测时间强写到旧 raw。不同 payload 保留冲突证据并失败关闭，不能算采集成功。

BackfillOrchestrator 不再无条件 createInitial：存在 timeline 时核验 manifest、runId/rawRef 后复用；缺失才创建。后续校验/发布沿用已发布幂等保护。原始 source/item 均维持不可变存储。当前采集成功必须实际达到 PUBLISHED 且 VERIFIED/VERIFIED_WITH_NOTICE，不再仅凭 raw 存在判成功。

验证依据：PbocRawFirstContractTest 的同 payload 字节不变/不同 payload 冲突；ScheduledEntryPostFixAttackTest 的恢复、回拨及多次执行快照；扩展 BusinessLoopIntegrationTest 的配置变更、周期后重放、冲突保留、业务树哈希相等。它核对 raw/formal、staging、published、processed、warning；新增 raw/source 观察和 runtime/conflicts 属于允许的新证据。完整应用重启未验收，不能把新建 Store 读取称为物理重启。

SHFE 自审修复：日历与行情 HTTP 实体先以 RawAcquisitionV1 保存，再校验 Content-Type/状态及解析；行情 source 身份同样按配置版本隔离。保留原 JSON schema、ADC12 基准语义、主力合约算法和单位。失败测试检查 manifest 有效且解码后字节等于出错响应，并确认没有 item raw、staging、published、processed。零字节/缺 Content-Type 的冻结模型限制见下文阻塞项。

## C 项：预警、确认与 Agent

聚合服务完成相关统计后同步调用现有正式评估；失败向当前采集传播为未完成状态，不回滚已成功发布与统计。规则版本、输入 fingerprint 参与已有预警身份，重复处理不能生成同规则/同输入新预警。集成测试增设评估故障注入，要求失败可见、当天 published 仍在且日线可查；原先字节快照覆盖重复警告不改写。

确认文件保持不可变 sidecar：原预警、manifest、warningId/ref、原预警 SHA 绑定全部验证；确认只记录人工处置，不改原风险。WarningAckStoreTest 保留重写 manifest 后的错 ID/ref/SHA 攻击。Agent 不再无条件排除 warning/，只接收经过身份与 FORMAL 规则核验的预警/确认；DEMO 和损坏绑定仍拒绝。坏确认不伪装 PENDING 或 ACKNOWLEDGED。确认前后原预警字节相等；Agent 风险投影与确认事实同时保留。

Java 计算并产生标准事实，云端只返回模板和 factId；Java 回填业务值及证据并核验。重复 token 测试以固定业务时间获取有效事实，断言模型端口调用一次，随后断言 INVALID_FACT_TEMPLATE，避免空事实提前降级造成假通过。未执行真实计费云调用。

## D 项及分级

| 项目 | 是否触及 / 契约依据 | 结论与阻塞性 |
|---|---|---|
| SHFE 解析失败留证 | 本批周期路径触及；DEC-056 要求未来 Provider raw-first，DEC-063 要求保存 HTTP 实体 | 不能整体延期；已补非空响应在解析前留证并测失败。**BLOCKER：空 payload / 空 Content-Type 仍不满足全覆盖**：RawAcquisitionV1 的非空字段约束拒绝该类响应。不得伪造字节/内容类型消除阻塞。需决定扩展兼容模型以准确表达空实体/缺元数据，或增加正式失败获取证据合同；本批未擅改冻结字段，暂缓该部分与发布结论。 |
| 历史报告绑定可变统计文件 | 本批未改变读取策略；AGENT-EVIDENCE-SCHEMA-V1 §4（持久化与完整性） 要求落盘报告及引用复核，DEC-030 fail-closed | 统计文件更新后旧 SHA 不匹配，旧报告会拒绝读取；保留安全门禁，不把变动数据冒充旧证据。**MAJOR 已有可用性限制**，可延期于本地候选审查，不能宣称长期历史报告可读/完整重启验收通过。不可简单取消 hash 校验；快照或版本化存储需另行决策。 |
| 超时预算 | 本批未改；前端 90s，后端单次 HTTP 默认 90s、配置允许到120s，另有工具/重试成本；DEC-031/060 要求失败降级 | **MAJOR 已有端到端预算缺口**：浏览器可能先超时，Java 后续降级/报告不可立即交付前端。保留既有超时提示，不声称已满足总时限。可延期于此代码候选审查，发布前须单独明确总预算/重试/取消或异步查询方案。 |
| 相对日期/会话 | 本批仅固定攻击测试日期，未做自然语言日期解析；受控 DTO 日期/显式 scope 的既有合同未改变 | **MINOR 范围限制**：不支持跨轮记忆或保证“最近一周”自然语言理解。可延期，本批不以此作为成功承诺；用户必须核对显式日期范围。 |
| 桌面端口 | 本批未改 desktop；证据中 base/working 哈希相等 | **MAJOR 既有启动缺陷 / 发布阻塞**：当前实际测试返回8080，pickFreePort 未排除而 backendArgs 拒绝。保留失败，不修改无关桌面业务；需单独授权修复并复测，不能宣称当前31/31。 |

零字节响应的两种处理选项均需用户/独立审查确认合同方向；本轮停留在可复核现状，不改 schema、不迁移数据。这里“可延期于候选审查”只表示可以提交给审查人，不等于生产验收放行。

## 测试与证据

目录：docs/evidence/business-loop-candidate-20261021。历史 Java21 runner 原件、原始日志与自动计数保存在 historical-jdk21；新回归与失败记录分开，不用旧通过数覆盖新失败。具体最新结果在 evidence README/summary 中收口。前端当前 41 通过，类型检查及构建通过；桌面当前 31 total / 30 passed / 1 failed。Java17 路径 D:/Dev/SDK/Java/jdk-17，运行时 Microsoft 17.0.19；仅测试子进程设置 JAVA_HOME，未修改系统环境。历史682/673/0/0/9来自实际旧报告汇总，不直接作为新修改后结果。

## 未执行及后续授权

未做真实外部来源验收、真实计费模型、物理断网/改时、完整 EXE 重启、新包或发布。下一阶段需先处理阻塞并独立审查固定 SHA；然后另行授权真实公开来源的隔离数据验收、使用用户配置密钥的有预算模型测试、目标桌面/新包与重启验收，最后独立版本发布。未经授权不启用这些动作。

## 候选结论

以最终 commit 固定本批范围供独立审查；存在明确阻塞，不作放行或最终交付结论。自审发现、失败原件和延期依据均保留，不削弱断言、不增加 skip。最终验证结果及完整 SHA 在收尾时核对。

## 候选最终核验（2026-10-21 16:36）

最终后端：Microsoft Java17.0.19+10-LTS，685 total / 676 passed / 0 failures / 0 errors / 9 skipped，BUILD SUCCESS。命令仅在子进程设置JAVA_HOME=D:/Dev/SDK/Java/jdk-17，运行mvn.cmd -o -f backend/pom.xml test。全部原件、自动计数和9项skip原始原因在专用证据目录final-jdk17。相比历史682项新增3项SHFE留证场景，未增加skip。源码指纹在验证前后相同。

早期Java17两次专项5项中4通过/1超时失败均保留；首次Java17全量685项有1个测试错误：错误使用不存在的published文件路径。改为核验真实raw对应timeline的PUBLISHED及验证状态后，全量通过。没有修改生产发布规则、延长25秒断言或增加skip。早期超时根因未定，不以最终成功否认历史失败。完整链路现已通过启动、动态配置、后续周期、重放/冲突保护、确认/Agent和预警故障时发布保留的断言。

前端41通过、类型检查及构建通过；桌面当前31 total / 30 passed / 1 failed / 0 skipped（8080端口缺陷），此前对话31通过没有同次原始日志可归档，不作当前候选证据。后端Java17自动化兼容已验证，不代表桌面/新包验收。

可进入独立固定候选审查，但空响应留证合同及桌面启动缺陷仍阻塞放行；历史报告绑定、超时预算、相对日期/会话的分级与依据见上文。不是独立审查PASS，不是最终交付完成。只做本地提交，不推送、不合并、不发布。

## 文件级清单

- backend/src/main/java/com/supplymind/agent/evidence/EvidenceRefVerifier.java
- backend/src/main/java/com/supplymind/agent/infrastructure/springai/WarningExplainToolAdapter.java
- backend/src/main/java/com/supplymind/agent/orchestration/AgentOrchestrator.java
- backend/src/main/java/com/supplymind/backfill/BackfillOrchestrator.java
- backend/src/main/java/com/supplymind/processing/AggregateProcessingService.java
- backend/src/main/java/com/supplymind/provider/pboc/PbocOfficialWebDataProvider.java
- backend/src/main/java/com/supplymind/provider/shfe/ShfeAdFreePublicDataProvider.java
- backend/src/main/java/com/supplymind/provider/shfe/ShfeFreePublicConfiguration.java
- backend/src/main/java/com/supplymind/rotation/TimeRotationConfiguration.java
- backend/src/main/java/com/supplymind/rotation/TimeRotationService.java
- backend/src/main/java/com/supplymind/scheduling/ActiveCurrentCollectionService.java
- backend/src/main/java/com/supplymind/scheduling/CurrentAcquisitionConfiguration.java
- backend/src/main/java/com/supplymind/scheduling/CurrentAcquisitionService.java
- backend/src/main/java/com/supplymind/scheduling/Day2SchedulingConfiguration.java
- backend/src/main/java/com/supplymind/scheduling/RotationGuardConfiguration.java
- backend/src/main/java/com/supplymind/warning/WarningAckStore.java
- backend/src/test/java/com/supplymind/agent/AgentApiTest.java
- backend/src/test/java/com/supplymind/agent/AgentFactFirstPipelineTest.java
- backend/src/test/java/com/supplymind/agent/AgentPipelineIntegrationTest.java
- backend/src/test/java/com/supplymind/config/DynamicConfigWorkflowServiceTest.java
- backend/src/test/java/com/supplymind/provider/shfe/ShfeAdFreePublicDataProviderTest.java
- backend/src/test/java/com/supplymind/scheduling/ActiveCurrentCollectionServiceTest.java
- backend/src/test/java/com/supplymind/scheduling/BusinessLoopIntegrationTest.java
- backend/src/test/java/com/supplymind/scheduling/CurrentAcquisitionServiceTest.java
- backend/src/test/java/com/supplymind/scheduling/ScheduledEntryPostFixAttackTest.java
- backend/src/test/java/com/supplymind/scheduling/ScheduledGuardProductionPathTest.java
- docs/04-DEVELOPMENT-TASKS.md
- docs/05-PROGRESS-LEDGER.md
- docs/06-DECISION-LOG.md
- docs/15-BUSINESS-LOOP-UPGRADE.md
- frontend/src/types/dashboard.ts
- frontend/src/views/DashboardView.vue
- frontend/src/views/__tests__/pages.spec.ts

另纳入本自审报告及专用证据目录，逐证据文件见SHA256SUMS.txt。
