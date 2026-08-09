# D1-T02 PBOC 双币解析契约记录

> 性质：D1-T02 调查产物；文件名中的DRAFT为历史命名，不代表字段仍待设计。正式运行schema以总计划8.4为唯一依据；本文件不创建backend或业务data目录。  
> 依据：2026-08-07 PBOC 官方公告及本目录来源能力记录。  
> 数值规则：所有 value 均以原始十进制字符串保存；后续只能从该字符串构造 BigDecimal。

## 1. Series 冻结映射

| 字段 | USD/CNY | EUR/CNY |
|---|---|---|
| 正式 itemId | FX.USD.CNY.PBOC_MID | FX.EUR.CNY.PBOC_MID |
| 显示名 | 美元/人民币中间价 | 欧元/人民币中间价 |
| baseCurrency | USD | EUR |
| quoteCurrency | CNY | CNY |
| monitor-series.currency | CNY | CNY |
| RawReceipt.rawCurrency / Candidate.currency / daily.currency | CNY | CNY |
| externalCode | USD | EUR |
| sourceFieldKey | 1美元对人民币 | 1欧元对人民币 |
| 报价方向 | 1 USD = value CNY | 1 EUR = value CNY |
| 单位 | CNY/1 USD | CNY/1 EUR |
| rateKind | 人民币汇率中间价 | 人民币汇率中间价 |
| 样例 value | 6.7904 | 7.8067 |
| sourceBusinessDate | 2026-08-07 | 2026-08-07 |
| sourcePublishedAtRaw | 2026-08-07 09:25:38 | 2026-08-07 09:25:38 |
| 解释后的 sourcePublishedAt | 2026-08-07T09:25:38+08:00 | 2026-08-07T09:25:38+08:00 |
| sourceUrl | 同一份官方公告 | 同一份官方公告 |

“USD/CNY”“EUR/CNY”在本项目中分别表示每1 USD、每1 EUR的人民币数额；不得取倒数，不得套用“100日元对人民币”的不同基数，也不得以展示精度重写原始值。`currency`与`rawCurrency`始终表示值的计价币种即quoteCurrency=`CNY`；baseCurrency仅由monitor-series的显式`baseCurrency`承载，不得从displayName猜测。

## 2. HTML 字段定位与解析规则

| 契约字段 | 来源定位 | 规则 |
|---|---|---|
| sourceBusinessDate | 标题、正文首句、公告落款日期 | 三处日期必须一致；不一致即拒绝进入后续标准化。 |
| sourcePublishedAtRaw | 页面“文章来源”字段 | 原样保存；按项目固定时区 Asia/Shanghai 解释时另存 ISO-8601 值。 |
| USD 原始值 | 正文锚点“1美元对人民币”后的十进制数和“元” | 只接受唯一、正数、十进制字符串匹配；样例为 6.7904。 |
| EUR 原始值 | 正文锚点“1欧元对人民币”后的十进制数和“元” | 只接受唯一、正数、十进制字符串匹配；样例为 7.8067。 |
| actualSourceName | 站点、正文授权表述 | 固定记录为“中国人民银行官网（授权中国外汇交易中心公布）”，不得只显示“CFETS”而失去 PBOC 官方页面来源。 |
| providerType / accessMethod | 来源能力记录 | 分别为 official_web / public_official_html。 |

解析器必须保存完整原始响应引用与原始值字符串；若标题日期、正文日期、落款日期、币种锚点、单位或数值任一缺失、重复或歧义，则记录失败，不生成替代值，也不得用上一日数值冒充当日数据。

## 3. ProcessingStage 与 ValidationStatus 分离

这两个字段必须独立保存，绝不复用为同一个状态字段：

| ProcessingStage | 含义 | 同时允许的 ValidationStatus |
|---|---|---|
| RECEIVED | 已收到原始响应，或已确认解析/基础格式失败 | PENDING、REJECTED |
| PARSED | 已按上述锚点生成候选字段 | PENDING |
| VALIDATED | 已执行 D2 校验规则 | VERIFIED、VERIFIED_WITH_NOTICE、REJECTED 或 CONFLICT |
| PUBLISHED | 已通过发布门禁并可供下游读取 | 仅 VERIFIED 或 VERIFIED_WITH_NOTICE |

允许组合的完整白名单为：`RECEIVED+PENDING`、`PARSED+PENDING`、`RECEIVED+REJECTED`、`VALIDATED+VERIFIED`、`VALIDATED+VERIFIED_WITH_NOTICE`、`VALIDATED+REJECTED`、`VALIDATED+CONFLICT`、`PUBLISHED+VERIFIED`、`PUBLISHED+VERIFIED_WITH_NOTICE`；其他组合全部非法。唯一初态、迁移边、CandidateV1及条件必填完全遵循总计划8.4.3，不得仅依据本表跳级。本次样例的记录状态仅为`RECEIVED+PENDING`。该短语表示“不可变RawReceipt + 通过rawRef关联的独立初始LifecycleRecord”，raw本身不保存或更新生命周期状态。D1-T02不执行解析、验证或发布；PUBLISHED绝不能仅因获取成功而赋值。

## 4. 数据目录对齐（编码前基线已冻结，未创建）

> 本节保留 D1-T02 的目录映射证据，但当前结论以 C29、DEC-043 和总计划第 8.3 节为准：只有下表列出的物理路径；“normalized”“published”等仅是逻辑术语，绝不能创建同名业务目录。

| 逻辑名称/阶段 | 冻结物理位置 | 编码前结论 |
|---|---|---|
| config | data/config/monitor-series.json；data/config/history/<configVersion>.json | 前者是唯一活动配置，后者是每个已生效版本的不可变审计快照，不是第二活动配置。 |
| raw | data/raw/<mode>/<providerType>/<itemId>/YYYY/MM/<runId>.json | 不可变原始收据；YYYY/MM取receivedAt。 |
| normalized | 无独立目录；完整生命周期时间线位于data/staging/<runId>.json | 仅表示PARSED逻辑结果；文件保存有序版本数组，不能覆盖历史版本。 |
| published | 无独立目录；发布状态写入LifecycleRecord，daily结果位于data/processed/daily/<itemId>/YYYY-MM.csv | 仅表示PUBLISHED逻辑阶段，不以获取成功直接赋值。 |
| daily | data/processed/daily/<itemId>/YYYY-MM.csv | 仅接收PUBLISHED+VERIFIED类记录。 |
| monthly/quarterly/half-year/yearly | data/processed/aggregate/<itemId>/{month,quarter,halfyear,year}/YYYY.csv | 这些仅是验收逻辑别名，不能创建monthly等竞争目录。 |
| alerts/state | data/warning/... / data/runtime/{jobs/active,jobs/history,dirty,conflicts/raw}/... | 仅映射warning/runtime物理路径；conflicts/raw不是正常数据链。 |

本任务未创建任何业务数据目录；目录创建留给 D1-T03，并必须通过唯一目录树测试。

路径日期口径唯一解释：raw 的 `YYYY/MM` 和异常证据 quarantine 的 `YYYY-MM` 均取 `receivedAt` 的 Asia/Shanghai 年月；processed/daily 与 processed/aggregate 只能按已验证的 `businessDate` 路由。业务格式固定为：config（含history快照）/raw/lifecycle/quarantine/warning/report/runtime/manifest 使用 JSON，processed daily 与四级 aggregate 使用 CSV。
