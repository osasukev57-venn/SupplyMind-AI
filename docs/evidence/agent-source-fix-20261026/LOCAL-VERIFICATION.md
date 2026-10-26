# 来源断言修复：本地回归索引

Base为5a160e531450d3a2153f83724dd4e6dbd900607e；分支codex/business-loop-fixes。实际候选和rc.6由构建manifest及最终交接绑定。本地验证不是独立审查PASS或真实云通过。

完整读取了rc.5真实云四份指定证据。HTTP200、5240ms、NONE/UNSUPPORTED_SOURCE是原真实调用事实；本轮未保存到其被拒正文，所有新来源文案都是离线构造，不推断原模型具体句式。

使用现有Microsoft OpenJDK17.0.19+10-LTS（java17.log），仅进程级JAVA_HOME/UTF-8参数；Maven离线。RUN-INDEX.json记录每次实际计数，不拼接运行。

| 运行 | total / passed / failures / errors / skipped | 对应输入与用途 |
|---|---|---|
| red | 19 /9 /10 /0 /0 | 原rc.5实际evidencePack、生产旧校验，来源句式误拦及否定断言缺口，失败原件保留。 |
| focused-first | 67 /67 /0 /0 /0 | 初次修复，当时19项来源测试＋既有质量/来源攻击，不冒充后续完整用例数。 |
| focused-final | 76 /76 /0 /0 /0 | 当时25项来源、质量、提示词、原问题生产跨配置链及来源攻击。其名称保留原件，但不是后来补齐边界后的最终输入。 |
| backend-full-final | 789 /780 /0 /0 /9 | 边界补齐前的全项目clean，日志名称保留；对应final-inputs.json，不能作为最后源码的全量结果。 |
| focused-boundary | 60 /60 /0 /0 /0 | 最终34项来源＋24项混合状态＋2项生产提示词。 |
| **final-full** | **798 /789 /0 /0 /9** | **最终144 suites，全项目Java17 clean package，candidate-inputs-full.json的551输入；生产源码此后未变。** |
| fixture-lf-validation | 34 /34 /0 /0 /0 | 提交前仅将新回放JSON夹具CRLF改LF，解码内容相同；补跑其全部来源测试。candidate-inputs.json为格式统一后的最终551输入。 |
| 前端 | 48通过 | frontend.log；新增同段通用类别＋脱敏原因码展示测试。 |
| 桌面 | 36通过、0跳过 | desktop.log；未改桌面源码及端口限制。 |

两次全量分开保存：前一轮启动后审查发现“来源于”及裸来源标签须纳入扫描，避免新提取器漏检；只修改SourceAssertionCheck与SourceAssertionRegressionTest。补上合法/攻击及映射绑定测试后，最终60项专项通过，再完成一次最终全量798项。不是失败后无界重试，也没有削弱断言、增加skip或修改旧验收数据。

提交前diff --check发现新回放夹具使用CRLF，合同目录-text规则不自动转换。只将这份新增JSON转LF；原件source-fixture-before-lf.json、前后SHA及解码等价证明fixture-line-ending-change.json、首次空白检查失败日志均保留。未更改原报告、原始XML/log或生产Java；无需因无语义的JSON换行变化重复全项目，但受影响34项已重跑通过。最终审计同时验证两份输入快照仅此夹具不同。

最终9项跳过均为既有真实来源、真实raw、真实计费云和物理改时门禁；原因逐项在final-full-summary.json。未启用收费云或物理主机操作。本轮没有修改任何旧测试断言；原10项红失败、旧候选失败证据、两次全量原XML/log全部保留。

原日志/报告独立存储于本目录，clean不覆盖；各*-surefire内可有定向运行之前的残留报告，定向汇总只统计明确选中的类。最终final-full始于clean，所有144个XML属于该轮，rawFileDigests可核对原件。原始log/XML未规范化；派生索引可使用LF。原控制台中文编码不清晰时以UTF-8 XML为准，不重写原日志。

没有新增占位符或精确句子白名单，没有改模型提示词强迫固定答案；生产差异仅来源断言检查、原因码接入与前端诊断键。模拟描述仅由明确非真实模拟来源事实映射，精确事实源名不变。

最终全量直接生成新JAR。随后打包复用此JAR，重新构建前端与JRE，使用全新staging和rc.6目录；同包受控19项结果、314数据复制和保护摘要在最终交接，不以受控模型替代真实云。原额度累计2/6，本轮0，剩余4；计划见CLOUD-RETEST-PLAN.md。
