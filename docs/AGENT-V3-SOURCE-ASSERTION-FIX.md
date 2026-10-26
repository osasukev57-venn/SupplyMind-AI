# Agent V3 来源断言定向修复

基线：5a160e531450d3a2153f83724dd4e6dbd900607e，codex/business-loop-fixes。唯一最终候选与 rc.6 包由本轮构建 manifest 和 evidence/agent-source-fix-20261026/FINAL-HANDOFF.md 绑定。

## 证据与问题

完整读取 agent-real-cloud-rc5-20261026 的 RESULT.md、execution-ledger.json、offline-source-probe.json、SourceValidationProbe.java。真实 qwen-plus 调用 HTTP200、5240ms，内容采用 NONE/UNSUPPORTED_SOURCE。被拒原文未保存，无法断言真实模型用了哪种句式。离线探针使用真实 evidencePack，已证明精确来源下“来自”通过而“来源为/来源是”误拦。

旧逻辑要求全段包含完整来源，删除该字面值，再在全段残余中匹配来源；连接词回溯被误当作新来源。也无法表达与事实明确映射的模拟来源描述。新的回放夹具 source-assertion-replay.json 只保存本次真实 evidencePack、源报告 SHA 和出处；所有测试文案均为离线构造，不冒充真实被拒内容。

## 最小实现

SourceAssertionCheck 只扫描长度上限内的明确来源标签／动词，逐个提取标点界定的断言实体，与本段引用 facts 的 actualSourceName 比较。正常“来自／来源为／来源是／来源于／源自”等连接词、来源标签、引号、中英文括号及句末标点得到结构化处理，不再删除字面来源后扫描全段余文。没有来源断言时不强迫模型编造来源。

名称匹配只规范化排版空白、引号和括号，保留实际来源文字，不接收任意商业别名、名称前缀后附伪造分支、同一断言拼接的第二来源。多次明确来源逐个验证；引用事实有多个不同来源且文案未能明确逐事实分配时失败关闭，不猜测。

模拟描述是由被引用事实的来源身份投影出来：只有 actualSourceName 明确为模拟演示/模拟数据这一身份，且带“非真实市场数据”限定时，才可使用相应模拟描述；分离的免责声明须为明确肯定的非真实声明。不能凭 itemId 含 SIMULATION、DEMO 模式、模型自述或带“模拟”字样的商业公司取得映射。免责声明不能洗白虚构商业来源。精确事实卡仍展示 Java 的原 actualSourceName，不改写数据或事实。

否定的外部来源不被当作肯定来源；否认引用事实的实际来源则冲突。此实现是有界中文断言校验，不声称任意语言的来源语义均已证明；不确定实体、缺失来源、歧义绑定或超过8个来源断言时失败关闭。

## 脱敏诊断与兼容

保留原 UNSUPPORTED_SOURCE 类别，再附一个有界枚举原因：SOURCE_FACT_MISSING、SOURCE_CONFLICT、SOURCE_EXTRACTION_UNCERTAIN、SOURCE_BINDING_AMBIGUOUS 或 SOURCE_CLAIM_LIMIT。不输出提取实体、被拒模型原文、系统提示词、密钥或 headers。最多14个段/建议、每项最多2条诊断；不新增自由文本诊断，不改变存储 schema。前端以 sectionId+code 作为列表键，正常文字展示原因码，不展示原始 JSON。

V1/V2/V3 文件、原报告及严格 SHA、manifest、lineage、行级身份与快照闭包校验保持不变。旧 V3 仍严格重校验已采用段；旧 NONE 报告不被重写或虚构为云端成功。原错误混合状态文字仍被拒绝，正确正常中文和独立合法段仍可采用。

## 验证边界

先保存红测试，再定向验证；全量原件、每次命令/时间、JDK17、输入摘要和跳过原因独立归档在 evidence/agent-source-fix-20261026。不得复用旧失败全量冒充本轮通过。边界审查补齐“来源于”和裸来源标签后，最终完成的全量必须对应最终冻结输入；之前运行单列历史，不与新结果拼接。

同包受控模型覆盖合法表达、真实模拟映射、否定免责声明、虚构和混入第二来源、部分采用、原混合状态4+4、月均24800.00/[5,12]与23200.00/[5]、当前custom-r4已确认但风险不消失。原314数据完整复制到新目录，不重导入或覆盖原目录。

本轮收费调用0，累计2/6，剩余最多4。真实云通过前不能宣布成品交付；下轮明确计划见同 evidence 目录 CLOUD-RETEST-PLAN.md。
