# Agent Fact-First Pipeline Closure

Date: 2026-08-24
Base: 4f7423c
Scope: Agent query orchestration, standardized fact projection, structured response verification.

## Problem

The earlier production path asked the cloud model to choose tools and produce an analysis before
Java could prove every requested conclusion. Valid language could therefore be rejected with
`UNSUPPORTED_CLAIM_REFERENCE` when aggregate averages or quality statistics were not represented
as first-class deterministic facts.

## Implemented production flow

1. Java derives the bounded item/date/grain query.
2. Java invokes the frozen read-only ToolExecutor before any cloud request.
3. Java verifies lineage and creates the formal EvidencePack.
4. Java projects standardized facts, including:
   - latest published value;
   - persisted aggregate average;
   - published/expected/missing/complete counts;
   - Java-computed completeness percentage;
   - verified warning current value.
5. If the facts required by the question are missing, the cloud call is skipped and the controlled
   Java template is returned.
6. The cloud receives only the standardized fact catalog, selects exact factIds and organizes
   language. Tool calling is disabled.
7. Java derives evidenceRefs from the selected factIds, verifies every claim and persists only the
   verified report.

No RAG, vector store, database, write tool or new Agent tool was introduced.

## Precision and verification

- Aggregate averages are read from persisted aggregate rows; the cloud does not calculate them.
- Completeness is computed in Java with BigDecimal and emitted as a standard percentage fact.
- Claim numbers must equal values from the claim's cited facts.
- Exact cited metadata such as `AZ91D` and `CNY/1 USD` may contain digits without being treated
  as newly calculated values; the same digits elsewhere still require value-fact support.
- Unknown facts/evidence, fabricated values, malformed envelopes and secret-bearing output remain
  fail-closed and fall back to `JAVA_TEMPLATE`.

## Verification

- Agent/Day6 targeted suite:
  - tests: 137
  - failures: 0
  - errors: 0
  - skipped: 0
- Full backend clean regression:
  - tests: 660
  - failures: 0
