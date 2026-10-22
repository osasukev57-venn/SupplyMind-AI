# 固定候选阻塞收口（2026-10-22）

状态：自审与定向修复记录，不是独立审查 PASS，不是最终交付。合同项仅提出方案，未实施模型/决议变更。
Base：f4c1cc9a74b5bc4e53a6854939d543762ec6c946；Branch：codex/business-loop-fixes。

## 实际修改范围

- desktop/src/port.js、desktop/test/port.test.js：复用 backend.requireDynamicPort；至多 10 次端口分配；新增 3 个确定性测试。
- backend/.../agent/llm/RequestBudget.java（新增）、agent/orchestration/AgentOrchestrator.java、agent/infrastructure/springai/CloudLlmConfiguration.java：请求起点计时、限制云等待、停止自动重试、报告落盘前预算检查。
- backend/.../scheduling/CurrentAcquisitionService.java：完成状态与采集准入锁一起更新；finally 保留异常释放。
- backend/.../agent/llm/RequestBudgetTest.java（新增）、scheduling/CurrentAcquisitionServiceTest.java、scheduling/BusinessLoopIntegrationTest.java：预算、失败后再次触发、历史报告复现和线程清理。
- frontend/src/api/agent.ts：仅修正过时的“两阶段云调用”注释；客户端仍为 90 秒。
- 本文及 docs/evidence/business-loop-blocker-closure-20261021、20261022：独立证据，不覆盖前候选失败原件。

## 桌面端口

上个候选 desktop-current.log 的 8080 来自真实 node:net（测试没有替换 net）。port 0 是操作系统分配，不保证满足应用的额外禁止规则。原选择器仅要求 >0；backendArgs 的 requireDynamicPort 则要求整数、1024..65535 且不等于 8080，形成契约不一致。

现在每次先关闭临时监听，再用同一个 requireDynamicPort 校验；禁止端口最多重试 10 次，耗尽明确抛错；listen/close 错误直接失败。保留原来所有断言，新增确定性的 8080/80/越界→合法端口、全部禁止→10次失败、实际 listen 错误→不重试三种测试。没有放宽 backendArgs。

该操作不是端口预留，关闭探测监听后仍有竞争。main.js 仍处理子进程 error/exit、健康检查失败及 stopBackend；没有用提前探测替代真实 Spring bind 失败处理，也不宣称彻底消除竞争。已有健康检查、第二实例、进程退出/残留清理自动化随桌面全套执行；没有运行真实 EXE/新包验收。

## 请求预算：兼容性防护与尚未收口的边界

原实现：前端 90s；云配置默认 90s、允许 1..120s，连接及读取分别配置；OpenAiChatModel 未显式限制重试。生产编排现已是 Java 查询/校验→唯一云解释→事实模板校验→报告落盘，不再是两次云阶段。

本批防护：answer 入口创建 monotonic 请求时钟；前置 Java 工作消耗同一预算。云等待最多到请求第 75 秒，后续调用也不能重置起点；超时 cancel(true)，返回显式 REQUEST_BUDGET_EXHAUSTED，沿原降级流程生成 JAVA_TEMPLATE。线程池最多 2 个云工作线程、无排队，饱和明确返回 CLOUD_CAPACITY_EXHAUSTED；模型不响应中断也不会无限增生线程。工作线程只返回云结果，不持久化报告；迟到结果不能再生成报告。生产 OpenAiChatModel 明确 maxAttempts(1)，不在超时后重试。第 75..85 秒为本地降级/校验预留，第 85 秒后拒绝开始写报告；前端仍为 90 秒，为返回预留余量。事实与证据校验没有取消。

**未解决：这不是严格的 90s 端到端上限。** 当前同步工具读取、SHA/lineage 校验和 AtomicFileStore 提交不接受截止期限；85s 检查只是落盘准入，已经开始的慢 I/O 不会自动停止。客户端断开也未传播完整取消令牌。底层 HTTP 超时仍是静态配置，中断能否迅速结束每种传输实现尚未全部证明。不能把本批云等待上限称为全请求硬期限。

下一步推荐继续同步架构：将同一 deadline/cancellation 贯穿只读工具、验证循环、传输和事务提交准入；缩短每次 HTTP 超时到剩余云预算；对提交中的不可中断段明确最大耗时与恢复语义，并测试客户端取消/落盘边界。若要求任何磁盘阻塞下都在90秒内返回，需要请求层返回期限与事务恢复状态的明确协议，影响范围大于本批小修。备选是保留本批有界云防护、明确为响应目标而非硬保证；这会降低原承诺，不能未经用户确认自行采用。此项严格端到端验收仍为 BLOCKER，暂停更广的取消/提交语义改动。

## SHFE 空响应留证：待确认设计

已核实 RawAcquisitionV1 构造器要求 contentType 和 payloadBase64 非空，外部HTTP必须有 httpStatus，摘要必须对应解码后的字节；RawAcquisitionStore 原子写入+manifest、已有有效文件不覆盖。JdkShfeHttpTransport 缺少 Content-Type 时传空字符串，连接失败/超时直接抛异常；没有伪造元数据。Provider 已在 requireJson/解析前 retainResponse，但模型拦截空实体/缺类型。

DEC-056 要求收到完整 HTTP entity 后先保存 immutable source evidence，再解析；DEC-063 要求保存真实 status/type/URL/time/bytes/SHA 且失败关闭。因此当前空实体/缺类型留证缺口是真实契约阻塞，不能只标记“后续修复”消除。

推荐最小兼容方案：新增独立 AcquisitionFailureEvidenceV1，仅接收失败获取证据，保持现有 RawAcquisitionV1 与 RawReceiptV1 不变；新专用持久目录、原子写入、相邻manifest和诊断读取器。需要批准新的失败证据schema及DEC-056/063对异常响应留证形式的补充，不能将其冒充现有RawAcquisitionV1已满足原合同。

| 情形 | 真实记录 | 业务准入 |
| --- | --- | --- |
| HTTP响应完整、实体0字节 | responseReceived=true、真实status/URL/receivedAt；entityLength=0、payloadBase64为空字符串、SHA256为空字节摘要 | 失败证据，无RawReceipt |
| HTTP响应缺Content-Type | contentTypePresent=false、contentType=null；保存真实实体/长度/摘要；若头存在但为空则present=true且值为空 | 不猜测application/json |
| 非成功状态 | 真实HTTP状态，完整返回实体及摘要；非空且类型已知时现有RawAcquisitionV1仍可原样留存，失败记录可引用它 | 不解析为业务成功 |
| 连接失败/超时且未收到响应 | attemptId、请求URL、startedAt/failedAt、受控failureCode；responseReceived=false，status/contentType/receivedAt/payload/length/SHA为null | 不能伪造零字节HTTP响应 |

空字节SHA应由实际byte[0]计算；manifest的文件长度与entityLength是不同概念。仅收到部分响应时必须标为不完整，不能声明取得完整实体。错误详情采用允许列表，不存密钥/完整headers/环境变量。

旧文件继续由V1原读取器读取，不迁移、不覆写。新增模型只有新诊断读取器理解；失败证据的完整性验证不等同业务 VERIFIED，不提供业务validationStatus，不进入timeline/PUBLISHED，RawReceipt链接验证器及Agent业务证据白名单必须拒绝此类型。测试需覆盖零实体、缺类型、503实体、无响应、证据损坏、错误绑定与业务不可见。

备选：RawAcquisitionV2统一表示已收到响应，允许真实空实体和nullable类型，新增entityLength/元数据存在标志；无响应仍用独立AttemptFailure。优点是响应证据统一，代价是codec版本分派、DataPaths、source reader/link verifier、PBOC/SHFE写端及Agent来源校验都要兼容V1/V2。禁止直接放宽V1而不版本化。待用户选择推荐的并列失败证据，或统一V2；本轮不新增决议、不改schema。

## 历史报告：已复现，未修复

BusinessLoopIntegrationTest 使用 @TempDir：8月11日基于GBP历史/统计生成报告并成功读取；临时篡改日线后读失败，恢复完全相同字节后读成功；通过生产采集链追加8月12日合法数据，日线新manifest仍有效，报告自身字节完全不变，再读旧报告返回 EVIDENCE_BINDING_MISMATCH。原确认损坏隔离断言保留。

根因是ReportStore按当前文件重新推导身份并比较报告中冻结的SHA/lineage；daily月文件和aggregate年文件会正常更新。现在它诚实地拒绝不再匹配的证据，但不能保留旧报告的可读取性。正常更新和篡改都可能使绑定失败，不能凭这个错误码认定篡改。

推荐报告V2采用生成时证据快照映射：只保存报告实际引用的可变文件及其可变lineage依赖闭包的原始字节和manifest；不可变raw继续引用原ref+SHA。快照按内容摘要去重，记录originalRef、生成时SHA、snapshotRef、版本、生成时间；从快照字节重新解析并验证原身份与lineage，不能只信报告自述。所有原SHA、manifest、发布和来源校验仍保留，不用最新文件替代旧依据。

生成时先验证再复制并复核摘要，一致性锁/有限重试确保引用闭包来自同一有效状态，受请求预算约束；若无法形成一致快照就失败，不写似是而非的报告。报告与快照引用原子提交，孤立快照可在确认策略后回收；不得删除仍被报告引用的版本。

旧V1继续原严格读取：当前证据匹配则可读；已漂移则明确不可验证，不静默替换为最新数据。只有仍具备原字节并能完整验证时才能显式迁移；不存在的旧版本不能重建为“已验证”。无需复制整个仓库，增长约为每个被引用且变化过的文件版本+manifest大小；同内容跨报告复用，原raw不复制。需要批准报告模型版本、快照目录/映射合同与旧报告呈现策略。

备选是在daily/aggregate每次更新时存储不可变版本、报告持久化版本ref：查询统一，但会保存无人引用的版本，影响加工写端与保留策略更广。本轮推荐按报告需要保存快照，保持加工规则不变；未实施。

## 旧链路超时与测试变更说明

20261021/current-jdk17/backend-targeted.log 显示启动96.768s、DAY12周期约27s，超过原25s等待，最终仍打印PARTIAL_SUCCESS。日志证明工作较慢且测试可能在worker结束前关闭context；不能证明唯一原因是负载，也未证明有顺序依赖。另查到“完成状态先发布，inFlight后释放”的真实竞态，它可能吞掉紧接着的触发，但不能据此解释全部RUNNING超时。

本批让完成状态与准入释放互斥更新，并保留finally异常释放；新增失败后可再次执行测试。集成测试原25s周期断言未延长，新增finally shutdownNow+25s awaitTermination检查，避免跨测试遗留worker。静态假源开关仍由测试串行改变，在观察完成状态后再修改；没有靠反复运行直到通过来接受旧失败。

测试新增/修改的意图：端口三项测试守住原禁止端口合同；预算测试用假时钟与可中断慢模型检验共享期限、迟到结果丢弃、诚实降级及仅一份报告；降级测试最初误用了不存在的itemId，改成Day6R2Fixture.ITEM并新增calls==1，超时原因断言保留；历史测试新增正常更新与篡改对比，没有放宽ReportStore；原重放不改历史、冲突失败关闭、单项隔离、预警失败不撤销发布、确认不是风险消失、DEMO/损坏确认拒绝等断言保持。

## 剩余阻塞与后续授权

- BLOCKER：严格90秒端到端期限尚未闭合（同步I/O/取消/提交边界）；不能用云超时防护宣称已解决。
- BLOCKER，待合同确认：SHFE空实体/缺元数据证据模型；历史报告快照/版本绑定。按用户要求仅设计。
- MAJOR：此前25秒链路超时的全部成因未被证明；已修复可确认竞态、增加清理，但不能抹去历史失败。
- 已定向修复：端口契约不一致；共享云等待与重试限制；采集完成状态竞态。
- 未执行真实来源、新包/EXE、真实计费云、物理断网/改时验收。下一阶段须先确认两项合同方案及端到端剩余边界，再安排授权范围内的实现与独立候选审查；真实来源/打包/云计费验收须另行授权。

本轮没有改动BigDecimal规则、业务阈值、原始raw身份算法、运行数据、旧交付物或main/v0.9.0；未推送、合并、发布。当前不能宣布全部阻塞已收口。最终测试汇总与固定提交身份以同目录证据及本轮最终报告为准。

## 本轮有效回归

2026-10-22 Java 17.0.19：Surefire原始XML核验690 total / 681 passed / 0 failures / 0 errors / 9 skipped；前端41 passed，桌面34 passed。新增5项后端测试（预算4项、异常释放1项），原跳过门禁未扩大。全套集成测试11.84s，原25s断言保留。日志、XML/txt、逐项跳过原因及源码SHA清单见 docs/evidence/business-loop-blocker-closure-20261022。前一日失败夹具日志和原件另存20261021目录，未覆盖。
