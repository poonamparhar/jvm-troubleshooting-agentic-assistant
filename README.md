# jtroubleshoot

`jtroubleshoot` is an AI-first CLI for troubleshooting JVM and Java application issues from diagnostic artifacts.

It uses specialist AI agents to analyze artifacts such as GC logs, JFR recordings, thread dumps, `hs_err` files, Native Memory Tracking output, heap histograms, `pmap` output, container-memory snapshots, and OOM or restart signals. Deterministic parsing exists to prepare useful context for the agents; the user-facing troubleshooting analysis comes from the AI agents.

## Requirements

- Java 25 or newer
- Maven 3.8 or newer
- an AI provider and model configured
- for local use, Ollama is the built-in default provider

## Build

```bash
mvn clean package
```

This produces:
- `target/jtroubleshoot-1.0.0-SNAPSHOT.jar`
- `target/lib/`
- `target/jtroubleshoot-1.0.0-SNAPSHOT-dist.zip`

## Run

If your default `java` is older than Java 25, set `JAVA_HOME` first:

```bash
export JAVA_HOME=/path/to/jdk-25
```

Typical commands:

```bash
./bin/jtroubleshoot help
./bin/jtroubleshoot status
./bin/jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log
./bin/jtroubleshoot analyze samples/single_process_data
./bin/jtroubleshoot shell
```

Use:
- `./bin/jtroubleshoot help` for one-shot commands
- `./bin/jtroubleshoot help shell` for interactive shell commands

## Configure AI

`config.json` selects the provider and model. `jtroubleshoot.env` holds any provider-specific secrets or connection settings that are not already in your shell environment.

Start with:

```bash
./bin/jtroubleshoot provider list
./bin/jtroubleshoot config show
./bin/jtroubleshoot config set provider ollama
./bin/jtroubleshoot config set model llama3.2
./bin/jtroubleshoot status
```

Example `config.json`:

```json
{
  "schemaVersion": 1,
  "provider": "ollama",
  "model": "llama3.2"
}
```

Templates:
- `config.example.json`
- `jtroubleshoot.env.example`

The preferred env file name is `jtroubleshoot.env`. Legacy names such as `jtroubleshoot-ai.env` and `.env` are still accepted.

Supported providers include:
- Ollama
- OpenAI
- Anthropic
- Google Gemini
- Mistral AI
- Azure OpenAI
- xAI
- Groq
- OpenRouter
- Together AI
- Fireworks AI
- OCI Generative AI
- generic OpenAI-compatible endpoints

Use `./bin/jtroubleshoot provider list` for the current provider catalog and required environment keys.

You can temporarily override the saved provider or model for a single command or shell session with `--provider` and `--model`.

## Basic Usage

Most users can start with `analyze`:

```bash
./bin/jtroubleshoot analyze <artifact-or-dir>
```

`analyze` automatically chooses the analysis mode:
- one supported artifact: single-artifact analysis
- two same-type comparable artifacts: comparison
- three or more same-type comparable artifacts: sequence or trend analysis
- mixed supported artifacts or a directory containing mixed artifacts: correlation

Explicit commands are also available when you want to force the mode:

```bash
./bin/jtroubleshoot compare <baseline> <current>
./bin/jtroubleshoot correlate <artifact1> <artifact2> [artifact3 ...]
```

Examples:

```bash
./bin/jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log
./bin/jtroubleshoot analyze samples/thread_dump_deadlock.txt
./bin/jtroubleshoot analyze samples/single_process_data
./bin/jtroubleshoot --provider openai --model gpt-4o-mini analyze samples/thread_dump_deadlock.txt
./bin/jtroubleshoot compare baseline.nmt current.nmt
./bin/jtroubleshoot correlate gc.log nmt.txt pmap.txt
```

## Interactive Shell

Use the shell when you want follow-up questions against the active diagnostic context:

```bash
./bin/jtroubleshoot shell
open samples/single_process_data
ask What is the most likely cause?
```

Common shell commands:
- `open <artifact> [--type <type>]`
- `analyze [<artifact-or-dir> ...]`
- `ask <question>`
- `compare <baseline> <current>`
- `correlate <artifact1> <artifact2> ...`
- `provider use <provider-id>`
- `config show`
- `config set provider <provider-id>`
- `config set model <name>`
- `clear`
- `exit`

## Supported Diagnostic Inputs

- GC logs, including legacy JDK 8 formats and JDK 9+ unified logging for supported collectors
- Java Flight Recorder (`.jfr`) recordings
- thread dumps
- `hs_err` JVM crash logs
- Native Memory Tracking summaries and diffs
- heap histograms
- `pmap` output
- container-memory snapshots and raw cgroup directories
- kernel OOM-kill excerpts and Kubernetes `OOMKilled` or restart output

## Output

Successful AI-backed analyses are shown in a concise CLI format and saved under:

- `target/analysis-reports/`

Each saved bundle contains:
- `report.txt`
- `report.json`
- `report.md`
- `report.html`

The CLI output is for active troubleshooting. The saved bundle is for later review or sharing.

## More Detail

`DESIGN.md` contains the current architecture and control-flow diagrams.
