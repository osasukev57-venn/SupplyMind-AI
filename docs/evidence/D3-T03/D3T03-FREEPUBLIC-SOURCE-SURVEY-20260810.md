# D3-T03 FreePublic 材料来源真实调查 —— NO_APPROVED_SOURCE（2026-08-10）

> 性质：D3-T03（FreePublicDataProvider 与真实来源追踪）公开来源调查证据。
> 方式：仅正常公开 HTTPS GET（JDK HttpClient，无凭证、无绕过、无反爬规避）。
> 结论：**NO_APPROVED_SOURCE**（冻结 DoD 路径 B：无获认可免费源 → URL/条款调查 + 转 Manual 路由，任务仍可 DONE；不得编造适配器）。
> 机器证据：`docs/evidence/D3-T03/free-public-source-survey-20260810.json`。

## 调查执行

- 时间：2026-08-10T18:50（Asia/Shanghai）；Java 17.0.19。
- 仅访问公开首页（正常公开访问），不访问会员区、不提交登录、不携带任何凭证、不做绕过。

## 逐源调查事实

| 来源 | 公开访问结果 | 需要登录/会员 | 公开结构化价格接口 | ADC12 语义匹配 | AZ91D 语义匹配 | 结论 |
|---|---|---|---|---|---|---|
| 上海有色网（SMM）`https://www.smm.cn/` | HTTP 200，公开首页 827,611 字节 | 是（价格数据会员制） | 否 | 否（公开页无结构化报价可核对） | 否 | NOT_APPROVED（MEMBER_ONLY） |
| 亚洲金属网（Asian Metal）`https://www.asianmetal.com.cn/` | 正常公开 HTTPS 握手被远端终止（SSL_HANDSHAKE_TERMINATED） | 无法取得事实 | 否 | 否 | 否 | NOT_APPROVED（NO_PUBLIC_INTERFACE） |
| 长江有色金属网（CCMN）`https://www.ccmn.cn/` | HTTP 200，公开首页 175,755 字节，可见铝/镁栏目 | 是（ADC12/AZ91D 具体报价会员制） | 否 | 否 | 否 | NOT_APPROVED（MEMBER_ONLY） |
| 生意社（100ppi）`https://www.100ppi.com/` | HTTP 200，仅约 660 字节引导壳页 | 否 | 否 | 否 | 否 | NOT_APPROVED（NO_PUBLIC_INTERFACE） |

## 标的结论

- **ADC12（MAT.ADC12.SMM）**：NO_APPROVED_SOURCE —— 未发现免费公开来源提供可按牌号/单位/交货条件/业务日期核对语义的结构化 ADC12 报价（SMM/CCMN 报价会员制，公开页无可核对结构）。
- **AZ91D（MAT.AZ91D.AM）**：NO_APPROVED_SOURCE —— 未发现任何免费公开来源呈现 AZ91D 报价。

## 路由结论

- FREE_PUBLIC 层不可用（无获认可来源）→ 三层路由显式降级 **MANUAL**（routeDecision=FALLBACK_MANUAL，fallbackReason 记录各候选原因）。
- 未实现任何 FreePublicDataProvider（NOT_IMPLEMENTED_WITH_REASON=NO_APPROVED_SOURCE）；Registry 中不存在材料 FreePublic Provider；路由配置引用不存在的 Provider 一律 fail-closed。
- SyntheticDemo 不进入正式 fallback。

## 边界遵守

- 未绕过登录/会员/验证码/反爬；未使用泄露 API、盗用 token、伪造接口。
- 未伪造任何材料价格/网页响应/授权状态；未把测试 fixture 当真实数据。
- 未修改 Validation/Publish Gate；FreePublic 公开 ≠ VERIFIED。
- EXT-10（免费公开源映射）与 EXT-11（Manual 数据复核）保持 `OPEN_EXTERNAL_NON_BLOCKING`（本调查为事实输入，不擅自 CLOSE）。
