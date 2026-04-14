# jtroubleshoot

`jtroubleshoot` is an AI-assisted CLI for JVM troubleshooting from diagnostic artifacts.

It supports analysis of:

- GC logs
- JFR recordings
- thread dumps
- `hs_err` logs
- Native Memory Tracking (NMT)
- heap histograms
- `pmap` output
- container memory snapshots / cgroup data
- OOM / restart signals

## Requirements

- Java 25+
- Maven 3.8+
- AI provider configuration (default: Ollama + `llama3.2`)

## Build and run

```bash
mvn clean package
./bin/jtroubleshoot help
```

If `java` is older than 25, set `JAVA_HOME` to a Java 25 installation.

## AI setup

`config.json` stores default provider/model. `jtroubleshoot.env` stores provider credentials/settings.

Useful commands:

```bash
./bin/jtroubleshoot provider list
./bin/jtroubleshoot config show
./bin/jtroubleshoot config set provider ollama
./bin/jtroubleshoot config set model llama3.2
./bin/jtroubleshoot status
```

## Usage

Main command:

```bash
./bin/jtroubleshoot analyze <artifact-or-dir> [more inputs...]
```

`analyze` auto-routes to single analysis, compare, trend analysis, or correlation based on inputs.

Examples:

```bash
./bin/jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log
./bin/jtroubleshoot analyze samples/single_process_data
./bin/jtroubleshoot analyze baseline-gc.log current-gc.log
./bin/jtroubleshoot correlate gc.log nmt.txt pmap.txt
```

Use `--type <type>` when auto-detection needs help. Types: `GC_LOG`, `JFR`, `THREAD_DUMP`, `HS_ERR_LOG`, `NMT`, `HEAP_HISTOGRAM`, `PMAP`, `CONTAINER_MEMORY`, `OOM_SIGNAL`.

## Interactive shell

```bash
./bin/jtroubleshoot shell
open samples/single_process_data
ask What is the most likely cause?
```

Common shell commands: `open`, `analyze`, `ask`, `compare`, `correlate`, `provider use`, `config show`, `config set`, `clear`, `exit`.

## Reports

Generated reports are saved to:

- source checkout: `target/analysis-reports/`
- packaged bundle: `reports/`

Each report bundle includes `report.txt`, `report.json`, `report.md`, and `report.html`.
