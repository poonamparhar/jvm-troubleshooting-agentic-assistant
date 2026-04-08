# jtroubleshoot

`jtroubleshoot` is an AI-first CLI for troubleshooting JVM and Java application issues from diagnostic artifacts.

## Requirements

- Java 25 or newer
- an AI provider and model configured

## Bundle Layout

- `./jtroubleshoot` runs the CLI
- `conf/config.json` stores the default provider and model
- `conf/jtroubleshoot.env` stores provider-specific secrets or connection settings when needed
- `reports/` is created automatically when analyses are saved

## Quick Start

1. Set `JAVA_HOME` if your default `java` is older than Java 25.
2. Review `conf/config.json`.
3. If your provider needs credentials, update `conf/jtroubleshoot.env` or export them in your shell.
4. Run `./jtroubleshoot status`.
5. Run `./jtroubleshoot analyze <artifact-or-dir>`.

## Common Commands

```bash
./jtroubleshoot status
./jtroubleshoot provider list
./jtroubleshoot config show
./jtroubleshoot analyze <artifact-or-dir>
./jtroubleshoot shell
```
