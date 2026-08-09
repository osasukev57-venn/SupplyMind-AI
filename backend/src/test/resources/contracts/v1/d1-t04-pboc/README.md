# D1-T04 synthetic HTML test fixtures

Every file in this directory is a **synthetic test fixture only**.

- It is not a live PBOC response or a captured HTTP entity.
- It is not a RawReceiptV1, raw business data, D1-T02 evidence, or a source of market values.
- It cannot be used to claim a successful Java HTTPS request, D1-T04 completion, Day 1/Day 2 exit, or `AT-SRC-002=PASS`.
- The fixture URLs, article identifiers, dates, timestamps, and values are invented exclusively for deterministic parser and failure-path tests.

| File | Test intent |
|---|---|
| `announcement-list-normal.html` | A normal announcement-list page with one discoverable relative detail link. |
| `announcement-detail-normal.html` | A normal dual-currency detail page: title/body/signature dates agree and both required field anchors occur exactly once. |
| `announcement-detail-missing-usd.html` | Detail page missing the USD field anchor/value; parsing must fail without creating USD raw data. |
| `announcement-detail-missing-eur.html` | Detail page missing the EUR field anchor/value; parsing must fail without creating EUR raw data. |
| `announcement-detail-structure-changed.html` | Deliberately changed markup/labels; a PBOC-specific parser must reject it rather than infer or fabricate values. |

The normal detail fixture uses the contract field labels `1美元对人民币` and `1欧元对人民币`, with units `CNY/1 USD` and `CNY/1 EUR`.  Production D1-T04 evidence must come only from a legal, real anonymous HTTPS PBOC list-to-detail acquisition.
