# Contributing

## Toolchain

- Java 25 is required for compile and test verification.
- Maven must run on Java 25 or newer. `mvn -version` should report Java 25.

## Local Verification

Run the shared verification path from the repo root:

```bash
bash scripts/verify-java25.sh
```

That script is the same path used by the GitHub Actions verification workflow.

If you change packaging or user-facing runtime behavior, also verify the packaged layout:

```bash
mvn package
printf 'version\nquit\n' | java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar
```

The packaged runtime now expects the app JAR and dependency directory to stay together under `target/`:
- `target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar`
- `target/lib/`

Saved report bundles default to `target/analysis-reports`, but contributors can override that location with:
- `ANALYSIS_REPORT_DIR`
- `-Djvm.troubleshooter.reportDir=/path`

## Pull Requests

- Keep structured-analysis behavior deterministic for supported artifact types.
- Add or update regression coverage when changing canonical JSON, renderers, parsers, rules, comparison, or correlation behavior.
- Prefer changes that preserve the report-first trust model over expanding legacy prompt-first flows.
