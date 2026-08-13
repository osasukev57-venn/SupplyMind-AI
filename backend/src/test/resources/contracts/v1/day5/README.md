# Day 5 FAST-R0 test harness

This directory and `com.supplymind.day5.foundation` are **test-only** contract harnesses for D5-T01 through D5-T05.

- Passing reference-oracle tests establish frozen input, boundary, state, and fault expectations only.
- They do **not** certify any Day-5 production service or H05-H09 acceptance scenario.
- Physical Windows time tests (AT-TIME-003/004) and the H05-H09 production integration entry points are explicitly `PENDING_IMPLEMENTATION` / disabled until their corresponding production tasks exist.
- No synthetic fixture in this directory is a real source response, a PBOC result, or an acceptance PASS.
