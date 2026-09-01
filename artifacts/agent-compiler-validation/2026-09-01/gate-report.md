# Release gate report

Release base: production revision `ef8b090d53c4f1f5c13df7626213e997bfe9611c`.

| Gate | Status | Evidence |
|---|---|---|
| Tesslate code-path analysis | PASS | `reference-analysis.md` |
| Failing Golden evidence before correction | PASS | `golden-evidence.md` |
| Golden tests after correction | PASS | focused Gradle result |
| Backend full test/build | PASS | full Gradle result |
| Frontend type/lint/test/build | PASS | Node 20 typecheck, lint (0 errors), production build; Playwright 14/14 |
| DB migration validation | PASS | restored original V21 and added idempotent V24 forward repair; a fresh production-shaped DB applied V1-V20, skipped V21, then applied V22-V24 and exposed all 8 columns, 2 constraints and the index |
| Different real graphs by input | PASS | `actual-graph-results.json` |
| Follow-up creates new version | PASS | version Golden |
| Agent Package structure | PASS | version Golden and package assertions |
| No production hardcoded success/fallback | PASS | exact Golden inputs absent from production sources; default mode is `real`; schema/parse/model failures throw errors; explicit `mock` mode is test-only |

Backend full result: 110 tests, 0 failures, 0 errors, 3 environment/feature-gated skips. The separate real-Codex Golden passed and is not counted as skipped evidence.

Source audit found no added private key, AWS access key, OpenAI-style secret, literal API-key assignment or password assignment. `git diff --check` passed.
