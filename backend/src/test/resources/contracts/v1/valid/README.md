# Valid fixture metadata

Every file listed below is a **test/contract fixture**, not product data or acceptance evidence.

| Fixture | Purpose | Non-evidence statement |
|---|---|---|
| `monitor-series-v1.json` and `monitor-series-history-1.json` | frozen PBOC-shaped production-default *configuration* contract | Contains no HTTP entity, market value, raw receipt, acquisition, or PASS claim; it is not a live PBOC payload/raw or AT-SRC-002/Day 1/Day 2 evidence. |
| `monitor-series-contract-fixture-v2.json` and `monitor-series-contract-fixture-history-v2.json` | byte-identical synthetic dual-currency GD-01 config/history | Explicitly routes both items to `synthetic_demo`; not PBOC. |
| `raw-receipt-v1.json`, `raw-receipt-rejected-v1.json` | RawReceiptV1 schema contract | Payload text explicitly says `NOT REAL PBOC`; both use test/synthetic_demo. |
| `dual-currency-response-entity.txt`, `raw-receipt-dual-usd-v1.json`, `raw-receipt-dual-eur-v1.json` | shared-payload dual-currency RawReceipt contract | Synthetic test entity only (SHA-256 `3f42e7ed…fc4f34c`); explicitly not live PBOC/raw evidence or any acceptance PASS. |
| `pboc-dual-currency-response-test-fixture.html` | parser-shape dual-currency response fixture | Its visible HTML declaration says it is not a PBOC response, raw receipt, evidence artifact, or acceptance PASS. |
| `lifecycle-published-v1.json`, `lifecycle-rejected-v1.json` | timeline / Candidate / terminal contract | Synthetic run IDs only; no published market datum. |
| `quarantine-received-rejected-v1.json` | terminal quarantine projection contract | Synthetic rejected fixture only. |
| `manifest-raw-receipt-v1.json`, `manifest-daily-v1.json` | ManifestV1 derivation contract | Hashes apply only to these fixture bytes. |
| `dirty-marker-config-activation-v1.json` | DirtyMarkerV1 two-target schema contract | No live transaction or product data. |
| `daily-v1.csv`, `aggregate-v1.csv` | CSV codec and calculation-context contract | Synthetic fixture values; no D2 calculation or acceptance PASS assertion. |

