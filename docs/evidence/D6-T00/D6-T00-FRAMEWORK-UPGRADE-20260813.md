# D6-T00 Framework Upgrade Gate（2026-08-13）— Spring Boot 3.5.15 + Spring AI 1.1.8

> 性质：D6-T00 CORE_R2 升级 Gate 证据（FAST-R0 实施侧；CORE_R2 最终 Review 由技术负责人+独立第二方执行，本证据不替代）。
> 基线：`day5-complete` / `36dc178`（Java 17 + Spring Boot 3.3.6，Day1-Day5 全部 COMPLETE）。
> 分支：`feature/d6-t00-framework-upgrade`（从 `integration/day6` @ ce37b69 创建，隔离实施，未污染 main）。
> 冻结决策：DEC-060（Day6 Spring AI Migration Boundary：Java17 KEEP、Boot 3.3.6→3.5.15 仅经 D6-T00、Spring AI 精确 1.1.8、禁止 Boot4.x/Spring AI2.x/预发布、禁止数据库）。

## 1. 升级范围（最小化）

- `backend/pom.xml` 唯一修改：
  - parent：`spring-boot-starter-parent` `3.3.6` → `3.5.15`
  - `dependencyManagement`：新增 `org.springframework.ai:spring-ai-bom:1.1.8`（import）
  - 新增唯一 Spring AI 依赖：`spring-ai-client-chat:1.1.8`（ChatClient 抽象所在最小模块；BOM 中无 `spring-ai-core`，经 BOM artifactId 清单核实后选用）
  - 删除显式 `jackson.version=2.17.3` 属性（交由 Boot 3.5.15 BOM 管理，避免与 Boot 管理的 Jackson 冲突；Jackson 实际解析 2.21.4）
  - 未引入：RAG / Vector Store / MCP starter / Memory / Agentic / 数据库 / Embedding / 任何具体模型供应商 starter
- 生产代码：0 修改；测试代码：0 修改；`application.yml`：0 修改。

## 2. 最终解析依赖（mvn dependency:tree，2026-08-13）

| 关键依赖 | 版本 | 越界检查 |
|---|---|---|
| spring-boot | 3.5.15 | 无 Boot4.x |
| spring-core / spring-context / spring-web / spring-test | 6.2.19 | 无 Framework 7 |
| spring-ai-client-chat / spring-ai-model / spring-ai-commons / spring-ai-template-st | 1.1.8 | 无 Spring AI 2.x |
| jackson-core / jackson-databind / jackson-annotations / jackson-datatype-jsr310 等 | 2.21.4 | 无 Jackson 3 |
| junit-jupiter | 5.12.2 | — |
| mockito-core / mockito-junit-jupiter | 5.17.0 | — |
| spring-ai-client-chat 传递依赖 mcp-json-jackson2（io.modelcontextprotocol.sdk） | 0.18.3 | 协议 SDK 仅用于 JSON schema，非数据库/agent 框架 |

## 3. 机械兼容修复

- NONE（pom 版本属性调整外无任何代码/测试/配置修改；编译零 API 适配）。

## 4. 完整 Clean Regression（真实执行，`mvn clean test`，Java 17）

| 指标 | 升级前（Day5 基线） | 升级后 |
|---|---|---|
| suites (classes) | 83 | **83** |
| tests | 407 | **407** |
| failures | 0 | **0** |
| errors | 0 | **0** |
| skipped | 8 | **8** |

- Test Count Reconciliation：PASS。suites/tests/skipped 与 Day5 基线逐项一致，无新增、无丢失、无 test discovery 变化。
- Skipped 8 项逐项（与基线相同，均为既有门禁）：AtSrc002AcceptanceTest（真实联网门禁）、PbocOfficialWebRealNetworkAttemptTest（真实联网门禁）、PbocRawClosedLoopSmokeGateTest（真实联网门禁）、AggregateRealRawEvidenceTest / DailyRealRawEvidenceTest / PublishRealRawEvidenceTest / PbocValidationRealRawEvidenceTest（真实 raw 门禁）、Day5TimeContractHarnessTest（AT-TIME-003/004 D10 物理系统时间）。核心测试 0 无理由 skip。

## 5. 保护项（升级后全量回归真实执行，全部 PASS）

- **Scheduled Guard**：ScheduledGuardProductionPathTest 1/1（唯一正式 @Scheduled=受 guard 保护入口）、ScheduledEntryPostFixAttackTest 1/1（Unguarded Scheduled Entry=NONE）、TimeRotationServiceTest 11/11（high-water MONOTONIC、rollback SUPPRESSED）。
- **Provider / Backfill**：Day5R2RotationHistoryCapabilityAttackTest a1/a2/a3（capability fail-closed、conflict EXCLUDED_AND_REPORTED）、Day5R2BackfillAttackTest a4/a5、BackfillRangeCompletionRuleTest Case A-D（PARTIAL_SUCCESS/checkpoint/resume/gap protection）、BackfillHistoryRangeContractTest（supportsHistoryData gate、H08）、ProviderDefaultCapabilityFailClosedTest（默认 supports()=FAIL_CLOSED、AuthorizedApi explicit）、Day5FinalProviderCapabilityDeclarationTest（全 Provider 显式声明）、Day5ImplementationIntegrationTest（H05-H09）、Day5FutureAcceptanceIntegrationHarnessTest。
- **History / Warning**：HistoryQueryServiceTest 8/8（conflict EXCLUDED_AND_REPORTED、missing != zero）、Day5R2WarningAttackTest a6/a7（Warning Cross-Clock BYTE_IDENTICAL、demoRule=false REJECTED）。
- **Serialization Contract**：PbocRawFirstContractTest 4/4（raw 黄金字节/idempotency/conflict 字节不变）、Day4GoldenFixtureManifestTest、GoldenFixtureContractAcceptanceTest、ManifestDerivedFieldsVerifierTest、RawAndConfigStoreTest、JsonV1CodecTest 全部 PASS——Jackson 2.17.3→2.21.4 未造成任何持久化字节漂移（manifest/sha256 由 FileDigest 计算，不受 Jackson 版本影响；golden 断言未修改）。
- **Application Context**：FoundationStartupAcceptanceTest 2/2（真实 Spring Application Context 启动 PASS）。Spring AI baseline 无 API key 不影响 context 启动（仅引入 client-chat 抽象模块，无 autoconfigure starter、无 ChatModel bean 注册）。
- **No Secrets**：pom/配置无任何 API key；无 Authorization 日志路径新增；agent.llm 配置仅由环境变量占位（后续 D6-T01+ 使用）。
- **No Database**：无 MySQL/PostgreSQL/SQLite/H2/Redis/MongoDB；无新持久化栈。

## 6. Gate 结论

- **FRAMEWORK_UPGRADE = ACCEPTED**（D6-T00 = PASS）：
  - Spring Boot 3.5.15 resolved ✓；Spring AI 1.1.8 resolved ✓；compile PASS ✓；ApplicationContext PASS ✓；full regression 83/407/0/0/8 与基线逐项一致 ✓；serialization contracts 保留 ✓；无 unexplained skip/test loss ✓；Business Semantic Changes = NONE（0 行生产代码修改）✓；Mechanical Compatibility Fixes = NONE ✓。
- D6-T00 = `IMPLEMENTED_PENDING_REVIEW`（CORE_R2 最终 Review 未执行，不提前 DONE）。
- 禁止项均未执行：未实现 D6-T01/Tool/Orchestrator/EvidencePack/ChatClient 业务/CloudLLMService/Controller/fallback；未开始 Vue/Day7；未修改 Day1-Day5 业务口径；未修改冻结 Decision；未引入数据库；未改 JSON/CSV schema；未改 BigDecimal 规则；未降低旧测试断言。
- Rollback 预案（DEC-060）：若 CORE_R2 Review 拒绝，丢弃本增量并恢复 `day5-complete`/`36dc178` Boot 3.3.6 基线。
