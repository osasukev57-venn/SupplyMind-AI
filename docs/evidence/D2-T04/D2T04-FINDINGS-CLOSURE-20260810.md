# D2-T04 Findings Closure Finalize —— 2026-08-10 复核关闭记录

> 性质：D2-T04 Code Review（`1ac8233 feat: complete D2-T04 aggregate persistence`）返回 `CHANGES_REQUESTED` 后的 Findings 关闭复核记录。
> 本轮不是新开发；不修改生产计算语义；不进入 D2-T05。
> 最终状态：D2-T04 = `REVIEW_PENDING`（等待 Sol Review 最终裁决）；AT-SRC-002 = `NOT_RUN`；D2-T05 = `NOT_STARTED`。

## 1. Finding A —— 四级跨 Clock 文件级确定性（CLOSED）

执行：`AggregateProcessingServiceTest.fourGrainsAreDeterministicAcrossProcessingClocks`

- 相同 logical daily dataset（同一 fixture：2026-01 + 2026-02 两个 daily 文件，逐字节相同）
- `Clock A = 2026-08-10T02:00:00Z`、`Clock B = 2026-08-10T10:30:00Z`（Clock A ≠ Clock B）
- 两个独立 dataRoot（harness 修复：唯一路径，消除共享路径覆盖），各自 `processYear` 四级写盘

逐级比较结果（CSV bytes 逐字节 + SHA-256 + 行级 calculatedAt）：

| grain | CSV bytes | SHA-256 | calculatedAt |
|---|---|---|---|
| month | 一致 | 一致 | 一致 |
| quarter | 一致 | 一致 | 一致 |
| halfyear | 一致 | 一致 | 一致 |
| year | 一致 | 一致 | 一致 |

`manifest.generatedAt` 允许跨 Clock 不同（manifest 写入时间戳，不参与文件确定性契约）。

## 2. Finding B —— 真正 read-only Restart（CLOSED）

执行：`AggregateProcessingServiceTest.restartReaderOnlyReadsPersistedAggregatesWithoutAnyRebuild`

- Writer A：唯一 dataRoot，安装 fixture daily → `processYear` 写出 month/quarter/halfyear/year 四级 aggregate CSV + manifest → 丢弃
- 写入前记录四级 CSV SHA-256（hash-before）
- 全新 Reader B：同一物理 dataRoot 上构建全新 `AggregateReadService`（只读服务）
  - 仅执行：discover 固定路径 → 校验相邻 manifest（COMMITTED、fileSha256、byteLength、rowCount、min/max businessDate）→ 校验 CSV → decode 并断言独立期望
  - 不调用：`processYear` / `processMonth` / `processGrain` / `calculate` / rebuild / write（无写盘 API）
- 写入后再次计算四级 CSV SHA-256，与 hash-before 一致（Reader B 全程未触碰文件字节）

| grain | read-only restart |
|---|---|
| month | PASS |
| quarter | PASS |
| halfyear | PASS |
| year | PASS |

Reader B 调用 process/rebuild/write：**NO**。

新增只读组件：`backend/src/main/java/com/supplymind/processing/AggregateReadService.java`（discover/verify/decode；无计算、无重建、无写盘）。

## 3. Finding C —— 多 configVersion（CLOSED）

执行：`AggregateCalculatorTest.configVersionsAreUnionDeduplicatedSortedAcrossInputs`

- 输入 daily 行 configVersions：`[1]`、`[2]`、`[1,3]`
- 聚合结果：`configVersions = [1,2,3]`

| 检查 | 结果 |
|---|---|
| union（{1,2,3} 并集） | PASS |
| dedupe（重复 1/3 仅保留一次） | PASS |
| sort（数值升序 1,2,3） | PASS |
| input reorder invariance（反转输入顺序结果不变） | PASS |

## 4. 全套测试结果（2026-08-10，surefire）

`AggregateProcessingServiceTest`：6/6 PASS；`AggregateCalculatorTest`：15/15 PASS。

全套（`mvnw test`，Java 17.0.19）：**35 test classes / 164 tests / 0 failures / 0 errors / 6 skipped**（skipped = 门禁测试：4×RealRawEvidence + RealNetworkAttempt + RawClosedLoopSmokeGate，网络受限环境按设计跳过）。

## 5. backend/data 遗留污染说明（测试环境事实，非生产代码缺陷）

全套测试首次执行时 `FoundationStartupAcceptanceTest` 失败（断言 `user.dir/data` 不存在不满足）。根因：repo 工作目录遗留 `backend/data/` 运行时产品数据目录（含 2026-08-09 的 d1-t05-smoke 与 raw 快照）。该目录：

- 由历史运行产生，**不是**本轮代码修改产生；
- 已备份移动至 `D:\Dev\Temp\opencode\sm-data-backup-20260809`（证据保留，不进入 Git）；
- 移动后 `FoundationStartupAcceptanceTest` PASS，全套 PASS；
- 从未被 Git 跟踪（根 `.gitignore` 含 `backend/data/`），移动**不**产生待提交删除，`git status` 无相关变更。

## 6. 本轮 Git 变更清单

- `backend/src/main/java/com/supplymind/processing/AggregateReadService.java`（新增，只读服务）
- `backend/src/test/java/com/supplymind/processing/AggregateCalculatorTest.java`（+57：Finding C 多 configVersion 等）
- `backend/src/test/java/com/supplymind/processing/AggregateProcessingServiceTest.java`（Finding A 独立 dataRoot + Finding B 纯只读 restart）
- `backend/src/test/java/com/supplymind/processing/AggregateRealRawEvidenceTest.java`（Reader B 改只读 AggregateReadService）
- `docs/evidence/D2-T04/D2T04-FINDINGS-CLOSURE-20260810.md`（本文件）
- `docs/05-PROGRESS-LEDGER.md`（最小同步）

commit：`test: close D2-T04 review findings`（提交后工作区 CLEAN）。
