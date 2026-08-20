# Day 9 — Electron 便携封装与 Windows 运行闭环（D9-T01～D9-T05）

> 性质：Day9 最终实施与 Final Attack Closure 证据。
> Base：`6a2965a`（Day8 COMPLETE merge）+ Cloud LLM Closure `b6e3334`。
> 原实施：`d377a12`（T01）、`4269ab8`（T02）、`bf55e54`（T03）、`d61135b`（T04）、`f0bd4de`（T05）。
> Final Attack 修复链：`7ce619b` → `cefb6fb` → `f78baf1` → `dae2607` → `3c5ddd1` → `5a2be22` → `bcf333e` → `635b0b6` → `9c59cce`。
> Final Technical Candidate：`9c59cce`；Final Attack Review=`PASS`；D9-T01～D9-T05=`DONE`；Day9=`COMPLETE`。
> `5b313a5` 的提前关闭仍标记为 `HISTORICAL_PREMATURE_STATUS_CLOSURE`，不作为本次结论依据。

## 1. 最终运行边界

```
SupplyMindAI.exe
  ├─ 单实例锁；第二次真实 EXE 启动只激活并聚焦首窗口
  ├─ 预检 runtime/jre、backend JAR、Vue index、data/logs 写权限
  ├─ 127.0.0.1 动态端口；健康检查通过后才显示窗口
  ├─ runtime/jre/bin/java.exe 启动 Spring Boot；不依赖系统 JAVA_HOME/PATH
  ├─ renderer bridge 仅 backendUrl/isDesktop；contextIsolation + sandbox
  └─ 正常退出、Electron 强杀、Java 强杀均无孤儿进程；writer lock 可恢复
```

后端只新增 `com.supplymind.desktop.*` 的健康检查、父进程 watchdog 与便携初始配置导出能力；未修改 Day1～Day8 的 Validation、Publish、Daily、Aggregate、Backfill、Warning、Agent/EvidencePack 业务语义。

## 2. 最终便携目录

```
SupplyMindAI/
  SupplyMindAI.exe
  resources/app/
  runtime/jre/                         # Temurin 17.0.19，含 java.net.http / jdk.crypto.ec
  app/supplymind-backend.jar
  app/web/
  data/config/monitor-series.json
  data/config/monitor-series.json.manifest.json
  data/config/history/1.json
  data/config/history/1.json.manifest.json
  logs/                                # 初始为空
  licenses/THIRD-PARTY-NOTICES.txt
  README.txt
```

ZIP 不包含 runtime 状态、writer lock、raw/staging/processed/warning/report、业务日志、测试文件、开发依赖或真实密钥。初始 config v1 由项目冻结默认配置和正式 codec/store 生成，两个 data 文件均有 manifest。

## 3. Final Attack 结论

| 项 | 最终结果 |
|---|---|
| 干净全新 staging、entry 白名单、递归秘密扫描 | PASS（321 entries，0 violation，真实环境 Key exact scan=false） |
| 两次同 commit 完整 clean package | PASS，ZIP SHA-256 逐字节一致 |
| 随包 JRE | Java 17.0.19；`java.net.http=true`；`jdk.crypto.ec=true`；后端启动 PASS |
| Bailian 非计费 TLS | PASS，`https://dashscope.aliyuncs.com/compatible-mode/v1` 握手完成，HTTP 404 |
| 真实双 EXE | PASS；第二实例自退、生产 activation/focus 事件存在、无第二后端 |
| 生命周期 | PASS；正常退出、强杀 Electron、强杀 Java、端口释放、writer lock 恢复；竞态修复后连续三轮 PASS |
| 数据持久化 | PASS；真实 Manual HTTP 200/PENDING；raw、timeline、config/history 与 manifest 在重启和整目录移动后逐字节不变 |
| 便携路径 | PASS；普通/空格/中文路径均只使用 bundled JRE；真实只读 ACL 在后端创建前 fail-closed |
| 无 Key 桌面 | PASS；Agent=`JAVA_TEMPLATE`、`degraded=true`，不阻塞启动 |
| Keyed portable Cloud | `READY_FOR_USER_AUTHORIZATION`；本次未得到新的可能计费请求授权，未发送请求、未伪报 PASS |
| 真实 Cloud 历史 Gate | PASS（2026-08-18，Alibaba Bailian 北京区，`qwen-plus`，1/1）；与本次便携 Gate 状态分开记录 |

## 4. 最终回归

- Backend：`mvnw.cmd clean test` = **122 suites / 648 tests / 0 failures / 0 errors / 9 skipped**。
- Desktop：`node --test test/*.test.js` = **31/31 PASS**。
- Frontend：`npm test -- --run` = **5 files / 33 tests PASS**；`npm run build` = **PASS**。
- 9 个 skipped 均为既有显式外部/物理时间/真实数据 Gate；无新增 skip、无测试断言弱化。
- 最终真实 EXE smoke：dashboard/history/config/warning 均 HTTP 200；Agent 无 Key 确定性降级；两次退出无残留；重启保持 9 个 data 文件。

## 5. 最终制品

- ZIP：`release/SupplyMindAI-0.9.0-win32-x64.zip`
- ZIP SHA-256：`2561FB77FB16720ADFCAFE39B3E7ECA7AF80B6D91C7E2D893BA5815D86BEC82A`
- ZIP manifest SHA-256：`A1170AB0D6C86192EAD2E0FF3E7536606900629DDB9D98E179ED2EE721445BEE`
- EXE SHA-256：`1925F358E7F0E9675A5AC4198FB076613F0DB318DA56D388799A97BE74A5B19C`
- Backend JAR SHA-256：`EDCB99D3BEE470C696FB5DCB41BD9BB9D08114A368EB003D76868DB858CDBF0D`
- Bundled `java.exe` SHA-256：`CF2C808F596C4DB2EC4135637E9F5B677553816D71FFF87FEB4E3203B171E403`
- Web index SHA-256：`E4B11BCF7C462C01E4068C765EF1AC1D2D179E5FD9A7D932608B18087C0D50DA`
- Machine evidence：`docs/evidence/Day9/final-attack/`；`index.json` 对每个证据文件绑定 size 与 SHA-256。

## 6. 最终状态

- Final Attack Review：`PASS`；BLOCKER=`无`；MAJOR=`无`。
- D9-T01～D9-T05：`TaskExecutionStatus=DONE`。
- Day9：`COMPLETE`。
- Day10：`NOT_STARTED`；D10-T01=`readyState=READY`。
- 本次未 merge main、未 tag、未开始 Day10；Day10 最终发布验收仍须独立执行。
