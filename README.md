# jtroubleshoot

`jtroubleshoot` is a JVM troubleshooting project aimed at a multi-agent architecture. The product is a team of specialist AI agents that analyze specific diagnostic artifacts, while deterministic parsers, assessors, structured evidence, and saved reports provide grounding, traceability, and reusable outputs. The current runtime now routes `analyze`, `compare`, and `correlate` through specialist-agent orchestration, and saved reports remain the reusable source of truth for follow-up questions and sharing.

## Features

- One-shot CLI for the main troubleshooting workflows:
  - `analyze <artifact-or-dir> [more-artifacts-or-dirs ...]` to analyze one artifact, auto-compare two matching snapshots, auto-trend same-type snapshot sequences, or auto-correlate mixed diagnostic inputs
  - `compare <baseline> <current>` to compare two artifacts of the same supported comparison type
  - `correlate <artifact1> <artifact2> ...` to correlate multiple supported artifacts
  - `ask --analysis-id <id> <question>` to ask a follow-up question against a saved AI analysis
  - `reports show <analysis-id>` and `reports list` to review saved analysis bundles
- Optional interactive shell for stateful sessions:
  - `shell` to enter the shell
  - `open <artifact>` and `open-report <analysis-id>` to set the active context
  - `analyze`, `show`, `ask`, `context`, and `clear` for iterative troubleshooting
- Basic runtime commands:
  - `version` to show application version, runtime, provider, and report-bundle location
  - `status` to show current status
  - `config show` and `config set` to manage saved AI defaults
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
  - Ollama
  - OpenAI
  - Anthropic / Claude
  - Google Gemini
  - Mistral AI
  - Azure OpenAI
  - xAI
  - Groq
  - OpenRouter
  - Together AI
  - Fireworks AI
  - OCI GenAI
  - generic OpenAI-compatible endpoints

## Requirements

- Java 25+
- Maven 3.8+
- One AI provider selected in `config.json` or on the CLI, plus any provider credentials or connection settings needed at runtime
- Optional for local AI: Ollama running with a compatible model (default model name: `llama3.2`)

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
  - target/jtroubleshoot-1.0.0-SNAPSHOT.jar
- Runtime dependencies are copied to:
  - `target/lib/`
- A packaged distribution is also created at:
  - `target/jtroubleshoot-1.0.0-SNAPSHOT-dist.zip`
  - `target/jtroubleshoot-1.0.0-SNAPSHOT-dist/jtroubleshoot-1.0.0-SNAPSHOT/`

## Run

Recommended launcher after packaging:
- `./bin/jtroubleshoot help`
- `./bin/jtroubleshoot provider list`
- `./bin/jtroubleshoot config show`
- `./bin/jtroubleshoot --provider ollama --model llama3.2 help`
- `./bin/jtroubleshoot --provider openai --model gpt-4o-mini help`
- `./bin/jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log`
- The launcher looks for a packaged jar under `target/` and will ask you to run `mvn -q -DskipTests package` if it is missing.
- The launcher requires Java 25 or newer and uses `JAVA_HOME/bin/java` when `JAVA_HOME` is set.
- `./bin/jtroubleshoot` treats the launcher root as the application home and keeps `config.json`, `jtroubleshoot.env`, and saved reports there by default.
- The packaged distribution under `target/jtroubleshoot-1.0.0-SNAPSHOT-dist/` includes `bin/jtroubleshoot`, `config.json`, `jtroubleshoot.env`, the main JAR, and runtime dependencies under `lib/jtroubleshoot/lib/`.
- The repo launcher therefore uses the project root. The packaged dist launcher uses the unpacked distribution root. If you run the jar directly, defaults still come from the current working directory unless you override them.

Maven entrypoint:
- `mvn exec:java -Dexec.args="help"`
- `mvn exec:java -Dexec.args="shell"`

Packaged runtime:
- `java -jar target/jtroubleshoot-1.0.0-SNAPSHOT.jar help`
- `java -jar target/jtroubleshoot-1.0.0-SNAPSHOT.jar shell`
- Keep `target/lib/` next to the JAR. The packaged runtime expects dependencies under `lib/`.

## Report Bundle Location

Default saved-report base directory:
- `target/analysis-reports`

Override options:
- Environment or `jtroubleshoot.env` with legacy `jtroubleshoot-ai.env` / `.env` fallback: `ANALYSIS_REPORT_DIR=/path/to/report-bundles`
- JVM system property: `-Djtroubleshoot.reportDir=/path/to/report-bundles`
- Legacy system property still accepted for compatibility: `-Djvm.troubleshooter.reportDir=/path/to/report-bundles`

The active report-bundle directory is shown by the `version` and `status` commands.

## AI Configuration

Persistent AI defaults are stored in:
- `./config.json` at the project or launcher root when you use `./bin/jtroubleshoot`
- If you run the jar directly, the default remains `config.json` in the current working directory unless you override it
- The runtime first looks for `jtroubleshoot.env`, then falls back to legacy `jtroubleshoot-ai.env` and `.env`, unless `ENV_FILE` points at another file
- The source tree includes [jtroubleshoot.env.example](/Users/poonam/ws/JVM-troubleshooting-AI-assistant/jvm-troubleshooting-agentic-assistant/jtroubleshoot.env.example) as the minimal provider-specific template, and the packaged bundle already includes the ready-to-edit runtime file as `jtroubleshoot.env`

Example file:

```json
{
  "schemaVersion": 1,
  "provider": "ollama",
  "model": "llama3.2"
}
```

Manage it with:
- `./bin/jtroubleshoot provider list`
- `./bin/jtroubleshoot config show`
- `./bin/jtroubleshoot config set provider openai`
- `./bin/jtroubleshoot config set model gpt-4o-mini`
- `./bin/jtroubleshoot status`

Selection precedence:
- command-line flags such as `--provider` and `--model`
- shell-session changes such as `provider use openai`
- saved `config.json` defaults
- built-in defaults (`ollama` and its default model)

The intended split is:
- `config.json` or CLI flags choose the provider and model
- `jtroubleshoot.env` is optional and carries only the minimum secrets or connection settings needed when they are not already in the shell environment
- legacy provider-specific model env vars are still honored as compatibility fallbacks, but they are no longer the recommended setup

Recommended setup flow:
1. Review the bundled `config.json` and change the provider or model if needed.
2. Run `./bin/jtroubleshoot status`.
3. Only edit `jtroubleshoot.env` if `status` shows the selected provider still needs required environment values.

Config file path overrides:
- JVM system property: `-Djtroubleshoot.configFile=/path/to/config.json`
- Legacy system property: `-Djvm.troubleshooter.configFile=/path/to/config.json`
- Environment or `jtroubleshoot.env` with legacy `jtroubleshoot-ai.env` / `.env` fallback: `JTROUBLESHOOT_CONFIG_FILE=/path/to/config.json`

Supported provider IDs:
- dedicated providers: `ollama`, `openai`, `anthropic`, `google`, `mistral`, `azure-openai`, `xai`, `groq`, `openrouter`, `together`, `fireworks`, `oci`
- generic fallback: `openai-compatible`
- aliases: `claude -> anthropic`, `gemini -> google`, `grok -> xai`, `compatible -> openai-compatible`
- run `./bin/jtroubleshoot provider list` to see the current catalog, defaults, and required environment keys

### OpenAI Quick Start

1. Open `jtroubleshoot.env` in the packaged bundle, or copy the source template:
   - `cp jtroubleshoot.env.example jtroubleshoot.env`
2. Set your OpenAI credentials in `jtroubleshoot.env`:

```env
OPENAI_API_KEY=your_api_key_here
```

3. Save OpenAI as the default provider:
   - `./bin/jtroubleshoot config set provider openai`
   - `./bin/jtroubleshoot config set model gpt-4o-mini`
4. Verify the setup:
   - `./bin/jtroubleshoot status`
   - `./bin/jtroubleshoot analyze samples/thread_dump_deadlock.txt`

## CLI Usage

One-shot commands:
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] analyze <artifact-or-dir> [more-artifacts-or-dirs ...] [--type <type>]`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] compare <baseline-file> <current-file>`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] correlate <artifact1> <artifact2> [artifact3 ...]`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] ask --analysis-id <id> <question>`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] reports show <analysis-id> [--format <text|json|markdown|html>]`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] reports list [--severity <level>] [--artifact-type <type>]`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] version`
- `./bin/jtroubleshoot [--provider <provider-id>] [--model <name>] status`
- `./bin/jtroubleshoot provider list`
- `./bin/jtroubleshoot config show`
- `./bin/jtroubleshoot config set provider <provider-id>`
- `./bin/jtroubleshoot config set model <name>`
- `./bin/jtroubleshoot help`

Global CLI options:
- `--provider <provider-id>` temporarily overrides the saved or default provider for that command or shell session
- `--model <name>` temporarily overrides the selected model for that command or shell session
- Put these options before the command, for example `./bin/jtroubleshoot --provider anthropic --model claude-sonnet-4-6 analyze samples/thread_dump_deadlock.txt`
- `analyze` routes internally based on the supplied inputs: one supported artifact runs single-artifact analysis, two same-type comparable artifacts run comparison, three or more same-type comparable artifacts run trend/sequence analysis, and mixed supported artifact types run correlation.
- When `analyze` auto-compares or auto-sequences same-type snapshots, it keeps the supplied order unless it can safely infer an older-to-newer order from parsed capture times, filename hints, timestamped filenames, or filesystem modified times.

Interactive shell:
- `./bin/jtroubleshoot shell`
- `./bin/jtroubleshoot --provider openai shell`
- `open <artifact> [--type <type>]`
- `open-report <analysis-id>`
- `analyze [<artifact-or-dir> ...]`
- `show [<analysis-id>] [--format <text|json|markdown|html>]`
- `ask <question>`
- `reports list [--severity <level>] [--artifact-type <type>]`
- `provider use <provider-id>`
- `config show`
- `config set provider <provider-id>`
- `config set model <name>`
- `context`
- `clear`
- `exit`

Examples:
- `./bin/jtroubleshoot provider list`
- `./bin/jtroubleshoot config show`
- `./bin/jtroubleshoot config set provider openai`
- `./bin/jtroubleshoot config set model gpt-4o-mini`
- `./bin/jtroubleshoot status`
- `./bin/jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log`
- `./bin/jtroubleshoot --provider ollama --model llama3.2 analyze samples/g1_21_smallheap_fullgcs.log`
- `./bin/jtroubleshoot --provider anthropic --model claude-sonnet-4-6 analyze samples/thread_dump_deadlock.txt`
- `./bin/jtroubleshoot --provider groq analyze samples/thread_dump_deadlock.txt`
- `./bin/jtroubleshoot --provider xai --model grok-4-1-fast-reasoning analyze samples/thread_dump_deadlock.txt`
- `./bin/jtroubleshoot analyze samples/single_process_data`
- `./bin/jtroubleshoot analyze /sys/fs/cgroup --type CONTAINER_MEMORY`
- `./bin/jtroubleshoot compare baseline.nmt current.nmt`
- `./bin/jtroubleshoot correlate gc.log nmt.txt pmap.txt heap.histo`
- `./bin/jtroubleshoot reports list --severity HIGH --artifact-type THREAD_DUMP`
- `./bin/jtroubleshoot reports show 20260328120000-sample.log --format markdown`
- `./bin/jtroubleshoot ask --analysis-id 20260328120000-sample.log "What changed between snapshots?"`
- `./bin/jtroubleshoot shell`

## User Walkthrough

1. Build and package the app:
   - `mvn package`
2. Inspect the provider catalog and save your preferred AI setup once:
   - `./bin/jtroubleshoot provider list`
   - `./bin/jtroubleshoot config set provider openai`
   - `./bin/jtroubleshoot config set model gpt-4o-mini`
   - `./bin/jtroubleshoot status`
3. Check the runtime and report directory:
   - `./bin/jtroubleshoot version`
   - `./bin/jtroubleshoot --provider anthropic --model claude-sonnet-4-6 version`
4. Analyze a single artifact or a support-bundle directory:
   - `./bin/jtroubleshoot analyze /path/to/recording.jfr`
   - `./bin/jtroubleshoot --provider ollama --model llama3.2 analyze /path/to/recording.jfr`
   - `./bin/jtroubleshoot --provider openai --model gpt-4o-mini analyze /path/to/recording.jfr`
   - `./bin/jtroubleshoot analyze samples/thread_dump_deadlock.txt`
   - `./bin/jtroubleshoot analyze samples/container_memory_pressure_snapshot.txt`
   - `./bin/jtroubleshoot analyze samples/kernel_oom_kill.log`
   - `./bin/jtroubleshoot analyze samples/pod_oomkilled_describe.txt`
   - `./bin/jtroubleshoot analyze samples/single_process_data`
5. Review saved results:
   - `./bin/jtroubleshoot reports list`
   - `./bin/jtroubleshoot reports show <analysis-id>`
   - `./bin/jtroubleshoot reports show <analysis-id> --format markdown`
6. Ask follow-up questions against a saved report:
   - `./bin/jtroubleshoot ask --analysis-id <analysis-id> "What are the highest-severity issues?"`
   - `./bin/jtroubleshoot ask --analysis-id <analysis-id> "What should I capture next?"`
7. If you want an iterative session, open the shell:
   - `./bin/jtroubleshoot shell`
   - `open samples/g1_21_smallheap_fullgcs.log`
   - `analyze`
   - `show`
   - `ask What should I capture next?`

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

The runtime now has two provider families:
- dedicated integrations: `ollama`, `openai`, `anthropic`, `google`, `mistral`, `azure-openai`, `xai`, `groq`, `openrouter`, `together`, `fireworks`, and `oci`
- generic OpenAI-style integration: `openai-compatible` for other endpoints such as self-hosted gateways or local inference servers

Quick reference:

| Provider ID | Typical env | Notes |
| --- | --- | --- |
| `ollama` | `(none)` | Local default. Built-in base URL is `http://localhost:11434`, built-in default model is `llama3.2`, and no env file is normally needed. |
| `openai` | `OPENAI_API_KEY` | Default model is `gpt-4o-mini`. Use `config set model` or `--model` to override it. |
| `anthropic` | `ANTHROPIC_API_KEY` | Alias: `claude`. Default model is `claude-sonnet-4-6`. |
| `google` | `GOOGLE_AI_GEMINI_API_KEY` | Aliases: `gemini`, `google-ai-gemini`. Also accepts `GOOGLE_API_KEY` and `GEMINI_API_KEY`. Default model is `gemini-2.5-flash`. |
| `mistral` | `MISTRAL_AI_API_KEY` | Default model is `mistral-small-latest`. |
| `azure-openai` | `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY` | `config set model` or `--model` maps to the Azure deployment name. |
| `xai` | `XAI_API_KEY` | Alias: `grok`. Set an explicit model in `config.json` or with `--model`. |
| `groq` | `GROQ_API_KEY` | Default model is `llama-3.1-8b-instant`. |
| `openrouter` | `OPENROUTER_API_KEY` | Set an explicit routed model in `config.json` or with `--model`. |
| `together` | `TOGETHER_API_KEY` | Set an explicit model in `config.json` or with `--model`. |
| `fireworks` | `FIREWORKS_API_KEY` | Set an explicit model or router in `config.json` or with `--model`. |
| `oci` | `OCI_COMPARTMENT_ID` | Built-in default model is `xai.grok-4`. OCI auth and profile details are read from the OCI CLI or SDK config such as `~/.oci/config`. |
| `openai-compatible` | `OPENAI_COMPATIBLE_BASE_URL` | Generic fallback for other OpenAI-style endpoints. Set the model in `config.json` or with `--model`. Some gateways also require `OPENAI_COMPATIBLE_API_KEY`. |

Examples:
- local Ollama:
  - `./bin/jtroubleshoot --provider ollama --model llama3.2 analyze samples/g1_21_smallheap_fullgcs.log`
- hosted OpenAI:
  - `./bin/jtroubleshoot --provider openai --model gpt-4o-mini analyze samples/thread_dump_deadlock.txt`
- hosted Anthropic:
  - `./bin/jtroubleshoot --provider anthropic --model claude-sonnet-4-6 analyze samples/thread_dump_deadlock.txt`
- hosted Groq with its provider default model:
  - `./bin/jtroubleshoot --provider groq analyze samples/thread_dump_deadlock.txt`
- OpenRouter with an explicit routed model:
  - `./bin/jtroubleshoot --provider openrouter --model anthropic/claude-sonnet-4-6 analyze samples/thread_dump_deadlock.txt`
- generic OpenAI-compatible endpoint:
  - `./bin/jtroubleshoot --provider openai-compatible --model local-model analyze samples/thread_dump_deadlock.txt`

`version`, `status`, and `provider list` show the selected provider and resolved model without forcing an AI model initialization.

If no AI provider is available, deterministic parsing may still run internally as grounding, but the CLI should not present or save troubleshooting analysis until an AI specialist or synthesis agent is available.
