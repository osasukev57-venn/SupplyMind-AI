# Day 4 Golden Contract Fixtures

These files are frozen test-contract inputs derived only from `docs/03-ACCEPTANCE-TEST-PLAN.md` section 5. They are not production data, source evidence, a PBOC response, or an acceptance PASS.

`GD-05`, `GD-06`, and the material-source execution portion of `GD-07` are explicitly `PENDING_IMPLEMENTATION`; no fixture in this directory makes an unimplemented material route pass.

`SHA256SUMS` is deterministic: it contains exactly the seven `GD-*.json` files, sorted by filename, with lowercase SHA-256 digests. It deliberately does not hash itself or this README.
