# SupplyMind AI 计算与日历规则登记册

> **追加式登记册。** 本文件是 `docs/01-PROJECT-MASTER-PLAN.md` 第 9 节的可追溯索引。已使用的 calculationVersion/calendarVersion 不得改写；新增口径必须使用新版本条目和新配置版本。本文件不表示 D2 计算已实现或已验收。

## 通用精度约束

1. 业务数值只允许从原始字符串以 `new BigDecimal(String)` 构造；float/double 不得进入链路。
2. 原始词法值原样留在 RawReceipt；持久化精确数值使用 `toPlainString()`，不得科学计数法、`stripTrailingZeros()` 或按 displayScale 提前舍入。
3. 每条 daily/aggregate CSV 必须记录 calculationVersion、calculationScale、displayScale、roundingMode、calendarVersion、currency、unit 与 configVersions。
4. displayScale 仅用于 API/UI 输出边界的 `setScale(displayScale, roundingMode).toPlainString()`；显示值不得回写持久化文件或参与计算。

## calculationVersion：arithmetic-mean-v1

| 属性 | 冻结定义 |
|---|---|
| 状态 | EXT-03 已正式接受版本化默认（DEC-053）：当前可上线、可验收的正式 daily 计算口径 |
| 输入 | 同一完整分组中的 `PUBLISHED + VERIFIED` 或 `PUBLISHED + VERIFIED_WITH_NOTICE` Candidate.value；输入按冻结 inputRefs 顺序 |
| daily sum | 精确 BigDecimal 相加，不舍入，持久化为 toPlainString() |
| daily avg | `sum.divide(validCount, calculationScale, roundingMode)`；持久化时恰好保留 calculationScale 位；只在最终除法执行舍入 |
| 单日默认 | 单一已发布官方日值是唯一合法样本；同日/同源/同口径多条观测采用算术平均 |
| daily 计数 | expectedCount=1；missingCount=`max(expectedCount-validCount,0)`；complete=`validCount>=expectedCount`；missing 不进入 validCount、不进入 sum、不得补 0 |
| displayScale | 仅用于 API/UI 展示边界，不得回写正式业务计算值 |
| aggregate | 直接由有效 daily `avg` 字符串重算，不读取月均值或 displayScale 值；sum 为 daily avg 精确和，validCount 为 daily 行数，avg 以相同 scale/rounding 除法，min/max 为输入 daily avg 精确最小/最大 |
| 隔离 | 来源、单位、币种、validationVersion 或计算上下文不同必须分行，绝不混算 |
| 变更规则 | 未来改为收盘价、加权均价或其他 daily 计算规则时，必须新增 calculationVersion 及相应 configVersion（DEC-053），不得静默修改本版本或改写已有历史语义 |

## calendarVersion：weekday-asia-shanghai-v1

| 属性 | 冻结定义 |
|---|---|
| 状态 | EXT-06 已正式接受版本化默认（DEC-054）：当前可上线、可验收的 expected-count/completeness 版本化规则 |
| 时区 | Asia/Shanghai |
| expected business dates | 周一至周五 |
| daily 计数 | expectedCount 为预期业务日数（单日固定 1）；missingCount=`max(expectedCount-validCount,0)`；complete=`validCount>=expectedCount`；缺失或无效日不得补零 |
| 能力边界 | 不代表完整中国法定节假日日历、完整调休规则、完整停报日规则或完整特殊交易日日历（DEC-054） |
| 变更规则 | 未来提升日历精度（法定节假日/调休/停报/特殊交易日）必须新增 calendarVersion 及相应 configVersion（DEC-054）；历史正式结果必须保留其实际使用的 calendarVersion |

## calendarVersion：golden-calendar-v1

| 属性 | 冻结定义 |
|---|---|
| 使用范围 | 仅 GD-01 test/contract fixture |
| expected business dates | 每月 10 日和 20 日 |
| fixture 精度覆盖 | calculationScale=12、displayScale=9、roundingMode=HALF_UP |
| 声明 | 该 fixture 覆盖不得冒充 PBOC 生产默认或真实验收通过 |

## 版本演进规则

- 业务确认收盘价、加权均价或其他口径时，新增 calculationVersion 与 configVersion，禁止改写 `arithmetic-mean-v1` 的历史含义。
- EXT-06 形成正式节假日规则时，新增 calendarVersion，禁止改写 `weekday-asia-shanghai-v1` 和 `golden-calendar-v1`。
- D1-T03 仅冻结字段、codec、引用和版本定义；daily/aggregate 业务计算属于后续 D2 工作。
