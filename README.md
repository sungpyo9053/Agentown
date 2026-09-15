# Agentown native runtime build snapshot

This isolated build branch contains only the runtime and a synthetic CSV package
exported by the production package renderer. It contains no service repository
history, user conversations, customer outputs, or authentication material.

The fixture is generated, not a separately maintained interpreter. Export it from
the service checkout using `AGENTOWN_EXPORT_NATIVE_FIXTURE=true ./gradlew
:backend:test --tests '*AgentPackageRuntimeTest.native build fixture*'`.
Copy `backend/build/native-fixture` and the tracked `core-runtime` sources together.
The build uses the same runner and pinned adapter as downloadable packages.

Source provenance is recorded in `source-manifest.json`.

Run the workflow on Windows x64, macOS Intel, and macOS Apple Silicon. Each target
checks startup with Python removed from PATH. That is not live AI, signed
distribution, clean consumer machine acceptance, or paid-readiness approval.
AI workflows still require a separately authenticated Codex CLI.
