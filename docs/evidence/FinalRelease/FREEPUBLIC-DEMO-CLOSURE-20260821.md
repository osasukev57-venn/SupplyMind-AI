# Final Release FreePublic / DEMO Closure Evidence

- Date: 2026-08-21 (Asia/Shanghai)
- Implementation commit: `ee48994`
- Decision: `DEC-063`
- Safety boundary: repository-only changes; no host network/VPN/proxy/DNS/routes/firewall/Hyper-V/Docker/WSL/services/system-clock modification.

## 1. Real public-source startup verification

A clean temporary dataRoot was launched on loopback with the production Spring Boot JAR and startup current-acquisition enabled.

- PBOC acquisition: `SUCCESS`, businessDate=`2026-08-21`, payloadSha256=`54d1d2f13bcc5cc8b10cc9d272c3d8be1886a53548c93b44d85f4305570d8315`.
- Both PBOC item chains existed independently: `FX.USD.CNY.PBOC_MID` and `FX.EUR.CNY.PBOC_MID`, each with official raw, lifecycle, daily CSV and month/quarter/halfyear/year aggregate CSV.
- SHFE acquisition used official `www.shfe.com.cn` current-trading-day and daily-market JSON. The last completed trading day was `2026-08-20`; selected `ad_f` main contract=`2610`, settlement=`22970`, volume=`6730`, unit=`元/吨`.
- Both ADC12 source-intent chains existed independently: `MAT.ADC12.SMM` and `MAT.ADC12.AM`, each with FreePublic raw, lifecycle, daily CSV and month/quarter/halfyear/year aggregate CSV.
- The two ADC12 items preserved truthful `actualSourceName=上海期货交易所铸造铝合金期货主力合约结算价（公开基准）`; they were not labelled as SMM/Asian Metal quotes.
- The first attack run exposed concurrent startup writers: PBOC and SHFE used different executors and one PBOC daily projection could be omitted. Production was changed so both acquisition services share the existing single-thread `currentAcquisitionExecutor`. A second clean startup produced all four formal daily chains and all four aggregate grains.
- The verification process exited normally; PID and loopback port were both absent afterwards.

## 2. Synthetic DEMO verification

`DemoShowcaseServiceTest` and frontend acceptance prove the one-click flow executes:

`RAW_CAPTURED → PARSED → VALIDATED → DEMO_PROJECTED → DAILY_CALCULATED → MONTH_QUARTER_HALFYEAR_YEAR_CALCULATED → WARNING_EVALUATED → COMPLETE`.

- DEMO raw and adjacent manifest are immutable and stored only below `data/raw/demo/`.
- Lifecycle stops at `VALIDATED`; no `PUBLISHED` snapshot exists.
- Deterministic daily/month/quarter/halfyear/year demo values and warning outcome are stored in `data/demo/showcase/supplymind-demo-showcase-v1.json` with an adjacent manifest.
- Re-run bytes and SHA-256 are identical.
- No formal `processed`, `warning`, `report`, or `quarantine` output is created by the demo flow.

## 3. Final regression

- Backend: 126 suites / 654 tests / 0 failures / 0 errors / 9 skipped; BUILD SUCCESS.
- Frontend: 5 files / 34 tests PASS; `vue-tsc --noEmit` and Vite production build PASS.
- Desktop: 31 / 31 PASS.
- Existing PBOC, file-schema, validation, publish, daily, aggregate, rotation, history, warning, Agent, desktop-process and security tests remained green.

## 4. Scope and known boundary

- ADC12 uses an approved SHFE cast-aluminium-alloy futures settlement benchmark. It is not an ADC12 spot price or a licensed SMM/Asian Metal quote.
- AZ91D remains Manual/LocalImport until a separate legal and semantically comparable source is approved.
- No database, write tool, hidden dataRoot, local model, RAG, vector store, MCP, Docker runtime or host service was added.