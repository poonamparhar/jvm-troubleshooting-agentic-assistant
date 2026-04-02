# JVM Troubleshooting Agentic Assistant

A JVM troubleshooting project aimed at a multi-agent architecture. The product is a team of specialist AI agents that analyze specific diagnostic artifacts, while deterministic parsers, assessors, structured evidence, and saved reports provide grounding, traceability, and reusable outputs. The current runtime now routes `analyze`, `compare`, and `correlate` through specialist-agent orchestration, and saved reports remain the reusable source of truth for follow-up questions and sharing.

## Features

- Interactive CLI with saved-report-driven commands:
  - `load <file-or-dir>` to load a single diagnostic file or a raw container-memory directory
  - `load --analysis-id <id>` to restore a saved analysis report as context
  - `analyze [<file-or-dir>]` to analyze the loaded file, a specific file, or a directory support bundle
  - `compare <file1> <file2>` to compare two files of the same supported comparison type
  - `correlate <file1> <file2> ...` to correlate multiple files across different supported types
  - `report [<analysis-id>] [--format <text|json|markdown|html>]` to read a saved report bundle
  - `catalog [--severity <level>] [--artifact-type <type>]` to list saved report bundles and filter them by summary metadata
  - `ask [--analysis-id <id>] <question>` to query a saved analysis report or the latest saved analysis
  - `config set provider <oci|ollama>` to switch AI model provider at runtime
  - `version` to show application version, runtime, provider, and report-bundle location
  - `status` to show current loaded file and active provider
  - `help` for command assistance
- Deterministic support for:
  - GC logs
  - JFR recordings (`.jfr`)
  - Thread dumps
  - JVM crash logs (`hs_err`)
  - Native Memory Tracking (NMT) output
  - Heap histograms
  - `pmap` output
  - Container memory snapshots (cgroup v2-style combined text snapshots)
  - Kernel OOM and restart-signal logs (kernel `oom-kill` excerpts and Kubernetes `OOMKilled` restart output)
- Saved report bundles in:
  - `text`
  - `json`
  - `markdown`
  - `html`
- Saved reports now retain the user narrative plus initial agent traceability and quality-gate summaries
- AI model-provider plumbing for specialist-agent analysis, synthesis, and follow-up Q&A:
  - OCI GenAI
  - Ollama

## Requirements

- Java 25+
- Maven 3.8+
- Optional for local AI: Ollama running with a compatible model (default model name: llama3.2)

## Java Setup

Maven must run on Java 25 or newer. Before building or testing, confirm:

- `mvn -version`

It must report Java 25.

If your shell defaults to an older JDK, point `JAVA_HOME` at your local Java 25 installation first. Example:

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -version
```

The build now fails fast with a clear message if Maven is launched with an older Java runtime.

Quick verification path:

```bash
bash scripts/verify-java25.sh
```

GitHub Actions runs the same script on pushes, pull requests, and manual workflow dispatches.

## Build

- Compile:
  - `mvn clean compile`
- Run tests:
  - `mvn test`
- Run the shared Java 25 verification path:
  - `bash scripts/verify-java25.sh`
- Package JAR:
  - `mvn package`
- The built JAR will be at:
  - target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar
- Runtime dependencies are copied to:
  - `target/lib/`

## Run

Recommended (Maven):
- `mvn exec:java`

Packaged runtime:
- `java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar`
- Keep `target/lib/` next to the JAR. The packaged runtime expects dependencies under `lib/`.

## Report Bundle Location

Default saved-report base directory:
- `target/analysis-reports`

Override options:
- Environment or `.env`: `ANALYSIS_REPORT_DIR=/path/to/report-bundles`
- JVM system property: `-Djvm.troubleshooter.reportDir=/path/to/report-bundles`

The active report-bundle directory is shown by the `version` and `status` commands.

## CLI Usage

Commands:
- `load <file-or-dir> [--type <type>]`    Load a diagnostic file or a raw container-memory directory with optional type override
- `load --analysis-id <id>`        Load a saved analysis report bundle as active context
- `analyze [<file-or-dir>]`        Analyze the loaded file, a specified file, or a directory support bundle
- `compare <file1> <file2>`        Compare two files of the same supported comparison type
- `correlate <file1> <file2> ...`  Correlate multiple files across supported types
- `report [<analysis-id>] [--format <text|json|markdown|html>]` Show a saved report bundle
- `catalog [--severity <level>] [--artifact-type <type>]` List saved report bundles with optional filters
- `ask [--analysis-id <id>] <question>` Ask questions about a saved or current saved analysis report
- `config set provider <oci|ollama>` Switch AI model provider at runtime
- `version`                        Show application version and runtime information
- `status`                         Show current context and latest saved analysis
- `help`                           Show command help
- `quit`                           Exit the application

Examples:
- `load sample-gc.log`
- `load recording.jfr`
- `load /sys/fs/cgroup --type CONTAINER_MEMORY`
- `load --analysis-id 20260328120000-sample.log`
- `load unknown-file.txt --type GC_LOG`
- `analyze`
- `analyze recording.jfr`
- `analyze hs_err_pid123.log`
- `analyze samples/thread_dump_deadlock.txt`
- `analyze samples/container_memory_pressure_snapshot.txt`
- `analyze samples/kernel_oom_kill.log`
- `analyze samples/pod_oomkilled_describe.txt`
- `analyze samples/single_process_data`
- `compare baseline.nmt current.nmt`
- `compare samples/thread_dump_baseline.txt samples/thread_dump_deadlock.txt`
- `correlate gc.log nmt.txt pmap.txt heap.histo`
- `correlate samples/g1_21_smallheap_fullgcs.log samples/container_memory_pressure_snapshot.txt samples/kernel_oom_kill.log`
- `report --format json`
- `report 20260328120000-sample.log --format markdown`
- `catalog`
- `catalog --severity HIGH --artifact-type THREAD_DUMP`
- `config set provider ollama`
- `version`
- `ask "What are the main memory issues?"`
- `ask --analysis-id 20260328120000-sample.log "What changed between snapshots?"`
- `status`

## User Walkthrough

1. Start the CLI:
   - `mvn exec:java`
   - or `java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar`
2. Confirm runtime details and the active report directory:
   - `version`
3. Analyze a single artifact or a support bundle directory:
   - `analyze /path/to/recording.jfr`
   - `analyze samples/thread_dump_deadlock.txt`
   - `analyze samples/container_memory_pressure_snapshot.txt`
   - `analyze samples/kernel_oom_kill.log`
   - `analyze samples/pod_oomkilled_describe.txt`
   - `analyze samples/single_process_data/java_nmt_summary_3391237.txt`
   - `analyze samples/single_process_data`
4. Review the saved bundle:
   - `report --format text`
   - `report --format markdown`
5. Review the saved incident catalog when you have multiple prior analyses:
   - `catalog`
   - `catalog --severity HIGH`
6. Ask follow-up questions against the saved report:
   - `ask "What are the highest-severity issues?"`
   - `ask "What should I capture next?"`

## Supported Data Types

Current Supported Data Types:
- GC logs (garbage collection analysis)
- JFR recordings (recording metadata plus deterministic runtime-event, allocation-field, old-object, hot-path, and typed-comparison analysis)
- Thread dumps (deadlock, contention, and stalled-thread analysis)
- Crash logs (hs_err files for JVM crashes)
- Native Memory Tracking (NMT) output (memory category analysis)
- Heap histograms (memory leak detection and usage analysis)
- PMAP output (process memory mapping analysis)
- Container memory snapshots (cgroup v2 memory.current, memory.max, memory.high, memory.events, memory.stat, and PSI pressure)
- Raw cgroup directories containing those files are synthesized into the same container-memory artifact automatically
- Kernel OOM and restart-signal logs (Linux kernel `oom-kill` excerpts and Kubernetes-style `OOMKilled` pod status output)


Current structured coverage:
- **GC**: collector detection, pause extraction, heap-pressure signals
- **JFR**: recording duration, event-family coverage, lock contention, GC pauses, thread parking, file or socket I/O latency, exception bursts, safepoint-style pauses, dominant sampled hot methods or stack paths, top allocating classes, allocation hot paths, old-object retention candidates, GC-root depth hints, and typed comparison for worsening runtime, allocation, or old-object signals from `.jfr` recordings
- **Thread dump**: deadlock detection, shared-lock contention hotspots, stalled executor-pool signals, and typed comparison for worsening stalls
- **`hs_err`**: signal extraction, problematic frame extraction, JVM crash findings
- **NMT**: summary and diff parsing, category analysis, metaspace and thread pressure findings
- **Heap histogram**: top consumers and comparison-based growth detection
- **`pmap`**: mapping totals, category breakdown, and growth comparison
- **Container memory**: cgroup limit headroom, `memory.high` breaches, OOM event counters, `memory.stat` breakdown, and PSI reclaim-pressure analysis
- **Kernel OOM / restart signals**: kernel OOM-kill context, killed-process extraction, `OOMKilled` pod restart summaries, CrashLoopBackOff-style restart detection, and correlation with container and JVM memory evidence
- **Typed comparison**: JFR recordings, thread dumps, NMT, heap histograms, and `pmap`
- **Correlation**: cross-artifact structured grounding plus AI synthesis narrative

Container memory snapshot capture:

```bash
for f in memory.current memory.max memory.high memory.events memory.stat memory.pressure; do
  printf '[%s]\n' "$f"
  cat /sys/fs/cgroup/$f
  printf '\n'
done > container-memory.snapshot.txt
```

The container-memory slice supports structured `analyze` and `correlate` on both the combined snapshot format above and raw cgroup directories that contain those files. Structured `compare` is not implemented for container memory yet.

The OOM-signal slice supports structured `analyze` and `correlate` on Linux kernel `oom-kill` excerpts and Kubernetes-style `kubectl describe pod` output with `OOMKilled` restart details. Structured `compare` is not implemented for OOM signals.

## Model Providers

Ollama (local AI, default startup provider):
- Required environment variables (can be set in `.env`):
  - `OLLAMA_BASE_URL` (default `http://localhost:11434`)
  - `OLLAMA_MODEL_NAME` (default `llama3.2`)
- Run Ollama with the specified model locally before launching the CLI.

OCI GenAI (cloud AI, switchable):
- Required environment variables (can be set in `.env`):
  - `OCI_COMPARTMENT_ID` (no default; must be provided)
  - `OCI_PROFILE` (no default; must be provided)
  - `OCI_MODEL_NAME` (no default; must be provided)
- Ensure your OCI CLI/SDK config (typically `~/.oci/config`) contains the profile referenced above.
- Switch providers at runtime via `config set provider oci`.

If no AI provider is available, deterministic parsing may still run internally as grounding, but the CLI should not present or save troubleshooting analysis until an AI specialist or synthesis agent is available.
