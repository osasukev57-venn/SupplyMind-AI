# D1-T03 AT-FILE-000 Review Fix Mapping

> 状态：`D1-T03=REVIEW_PENDING`。本文件仅记录 Sol Code Review 指定的 AT-FILE-000 补证范围；不改变任何冻结业务契约、任务状态或 AT-SRC-002 状态。
>
> 边界：所有输入均为本地 `test/contract fixture` 或手工固定预期，绝不代表真实 PBOC 响应、真实 raw 或 Day 1/Day 2 验收通过。

| AT-FILE-000 步骤 | 冻结验收焦点 | 已有证据 | Review Fix 补充证据 | 状态 |
|---|---|---|---|---|
| 1 | 唯一绝对 dataRoot、中文/空格路径、失败快退、完整 config/history | `FoundationStartupAcceptanceTest.startsWithOneUnicodeDataRootAndActivatesTheFormalPbocHistoryPair`、`DataRootAndPathsTest`、`SupplyMindDataPropertiesTest` | `AtFile000RecoveryManifestRootAcceptanceTest.readOnlyImageAndAtomicMoveUnsupportedFileSystemFailFastWithoutFallbackRoot`；启动无第二默认 root/数据库文件及完整冻结配置字段断言 | PASS |
| 2 | 同一双币 fixture 的共享 acquisitionId、独立 raw/run/timeline | `DualCurrencyRawLifecycleAcceptanceTest` | `AtFile000DualArtifactImmutabilityAcceptanceTest.storesOneExplicitSyntheticResponseAsTwoIndependentRawAndTimelineArtifactsAcrossRestart`：双 raw、双 staging/timeline、各自 manifest、重启重读 | PASS |
| 3 | 4×5 状态、迁移、Candidate、重启 | `D1T03AtFile000SupplementalAcceptanceTest.lifecycleMatrixAllAdjacentEdgesAndCandidateImmutabilityAreEnforced` | 上述双币测试固定 v1→v4 成功链、落盘与重启读取；非法组合/迁移由状态矩阵拒绝 | PASS |
| 4 | 三个失败终态 quarantine、非终态不投影、日期路由 | `QuarantineAndPrecisionV1Test`、黄金 fixture | `AtFile000DualArtifactImmutabilityAcceptanceTest.persistsAllThreeTerminalQuarantinesWithoutProjectingPendingOrPublishEligibleTimelines`：三终态、非投影、每项 CREATE_NEW | PASS |
| 5 | 路径拒绝、raw 同 hash 幂等、异 hash 冲突、冻结冲突路径 | `DualCurrencyRawLifecycleAcceptanceTest`、`RawAndConfigStoreTest` | `AtFile000DualArtifactImmutabilityAcceptanceTest.keepsRawAndActualRawConflictEvidenceCreateNewWithBothDataAndManifestBytesUntouched`，以及其 quarantine 覆盖断言 | PASS |
| 6 | raw/timeline/manifest 与 DirtyMarker canonical/tmp/bak、CONFIG_ACTIVATION 崩溃窗口 | `AtomicFileRecoveryTest`、`DirtyMarkerRecoveryTest`、`D1T03AtFile000SupplementalAcceptanceTest.configActivationRecoveryCompletesHistoryThenActiveTwoTargetFourFileWindow` | `AtFile000RecoveryManifestRootAcceptanceTest.markerProvenNewRawRebuildsOnlyItsMissingManifestFromFixedRawContract` 与 `markerFieldDriftAndAllInvalidCandidatesFailClosedWithEvidencePreserved`；`AtFile000ConfigAndRawWindowAcceptanceTest.completesMarkerProvenRawTemporaryFileBeforeDataCommitWithoutChangingFixtureBytes`及`completesEveryConfigActivationFourPhysicalFileCrashWindow`覆盖raw tmp和v1→v2配置history/active四个物理文件窗口；既有 canonical/tmp/bak最高revision、同revision异字节、跳号/回退也通过。该测试揭示的history已提交、active未开始恢复缺口已最小修复。 | PASS |
| 7 | Manifest 正常、缺失、陈旧、篡改与派生字段 | `AtomicFileRecoveryTest`、`ManifestDerivedFieldsVerifierTest` | `AtFile000RecoveryManifestRootAcceptanceTest.fixedManifestTamperMatrixFailsClosedAgainstFrozenRawBytes`：固定 raw bytes 下手工 hash、rowCount、日期范围、sourceRunIds 篡改 | PASS |
| 8 | BigDecimal 与 daily/aggregate 固定 CSV 合同 | `CsvV1CodecTest`、`GoldenFixtureContractAcceptanceTest` | `IndependentFormatContractAcceptanceTest.unorderedDailyAndAggregateInputsMatchHandFrozenCsvBytesAndHashes`：手工乱序多行输入、固定 header/字段顺序/inputRefs/sourceFingerprint/SHA、大小数/尾零/无科学计数法 | PASS |
| 9 | JSON/CSV 黄金、非法反例、字典与无数据库依赖 | `GoldenFixtureContractAcceptanceTest`、`FoundationStartupAcceptanceTest` | `IndependentFormatContractAcceptanceTest.manualRawAndDirtyMarkerObjectsMatchHandFrozenJsonBytesAndHashes` 与 `handFrozenJsonCounterexamplesRejectTamperedContractBytes`；实际 Maven `dependency:tree` 无数据库/Docker命中 | PASS |
## 独立预期规则

1. 格式合同测试不得从 `JsonV1Codec`、`CsvV1Codec`、`ManifestFactory` 或 `CanonicalJsonV1` 生成 expected bytes、header、hash 或 sourceFingerprint。
2. 新增 golden fixture 以仓库内冻结 UTF-8 bytes 保存；测试用手工对象作为 SUT 输入，并使用字面 header、字面 SHA-256、JDK `MessageDigest` 或手工固定字段验证结果。
3. Factory/codec 自洽单元测试仍可保留为低层回归，但不单独作为本 Review Fix 的独立验收证据。

## 通过判定

每个步骤必须有可追踪的测试类与方法，并在本轮定向验收中通过。通过本轮任务自测不改变 `D1-T03=REVIEW_PENDING`，也不改变 `AT-SRC-002=NOT_RUN`。
