# D1-T03 Review-Fix Independent Format Fixtures

These are hand-frozen local `TEST/CONTRACT FIXTURE ONLY` byte oracles for AT-FILE-000 Steps 8–9.

They are not real PBOC responses, raw production data, Provider evidence, AT-SRC-002 evidence, or Day 1/Day 2 acceptance evidence. The identifiers, source labels, hashes, dates, values, and payload are synthetic test values only.

The matching acceptance test constructs its SUT inputs manually, compares codec output directly with these fixed UTF-8 resources, and verifies literal SHA-256 vectors using JDK `MessageDigest`. It does not use `CsvV1Codec` headers, `JsonV1Codec`, `ManifestFactory`, or `CanonicalJsonV1` to build the expected bytes, headers, fingerprint, or hashes.
