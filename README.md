# JVM Troubleshooting Agentic Assistant

An interactive CLI tool that analyzes JVM diagnostic data using a multi-agent AI architecture built with LangChain4j. It supports multiple input files and routes them to specialized agents.

## Features

- Interactive CLI with comprehensive commands:
  - `load <file>` to load a single diagnostic file
  - `analyze [<file>]` to analyze the loaded file or a specified single file
  - `compare <file1> <file2>` to compare two files of the same type
  - `correlate <file1> <file2> ...` to correlate multiple files across different types
  - `ask <question>` to query about loaded data with conversation history
  - `config set provider <oci|ollama>` to switch AI model provider at runtime
  - `status` to show current loaded file and active provider
  - `help` for command assistance
- Specialized AI agents for different diagnostic data:
  - GCLogAgent for garbage collection log analysis
  - HSErrLogAgent for JVM crash log analysis
  - NMTAgent for Native Memory Tracking output
  - HeapHistogramAgent for heap histogram analysis (single or comparison)
  - PmapAgent for process memory map analysis
  - CorrelationAgent for cross-file correlation
- Runtime AI provider switching:
  - Ollama (default, local AI)
  - OCI GenAI (cloud AI)
- Intelligent data type detection and agent routing

## Requirements

- Java 25+
- Maven 3.8+
- Optional for local AI: Ollama running with a compatible model (default model name: llama3.2)

## Build

- Compile:
  - mvn clean compile
- Package shaded JAR:
  - mvn clean package
- The shaded JAR will be at:
  - target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar

## Run

Recommended (Maven):
- mvn exec:java

This automatically wires the classpath for all dependencies. If you prefer to run the packaged jar directly, be sure to include the dependency classpath yourself, otherwise use:
- java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar

## CLI Usage

Commands:
- `load <file>` [--type <type>]    Load a single diagnostic file with optional type override
- `analyze [<file>]`               Analyze the loaded file or a specified single file
- `compare <file1> <file2>`        Compare two files of the same type (e.g., baseline vs current)
- `correlate <file1> <file2> ...`  Correlate multiple files across different types
- `ask <question>`                 Ask questions about loaded data with conversation history
- `config set provider <oci|ollama>` Switch AI model provider at runtime
- `status`                         Show current loaded file and active AI provider
- `help`                           Show command help
- `quit`                           Exit the application

Notes:
- `load` supports automatic data type detection or manual override with --type
- `analyze` without args analyzes the loaded file; with a file arg analyzes that single file
- `compare` requires files of the same type and performs differential analysis
- `correlate` analyzes relationships between different diagnostic data types
- `config set provider` allows switching between Ollama (local) and OCI (cloud) AI
- Only one file can be loaded at a time, but compare/correlate work with multiple files

Examples:
- `load sample-gc.log`
- `load unknown-file.txt --type GC_LOG`
- `analyze`
- `analyze hs_err_pid123.log`
- `compare baseline.nmt current.nmt`
- `correlate gc.log nmt.txt pmap.txt heap.histo`
- `config set provider ollama`
- `ask "What are the main memory issues?"`
- `ask "Based on the previous response, what tuning parameters should I adjust?"`
- `status`

## Supported Data Types

Current Supported Data Types:
- GC logs (garbage collection analysis)
- Crash logs (hs_err files for JVM crashes)
- Native Memory Tracking (NMT) output (memory category analysis)
- Heap histograms (memory leak detection and usage analysis)
- PMAP output (process memory mapping analysis)

Planned for future support:
- JFR Files

Current agent coverage:
- **GCLogAgent**: Garbage collection log analysis with throughput and pause time calculations
- **HSErrLogAgent**: JVM crash log analysis for root cause identification
- **NMTAgent**: Native Memory Tracking output analysis for memory pressure detection
- **HeapHistogramAgent**: Heap histogram analysis (single files or comparisons for leak detection)
- **PmapAgent**: Process memory map analysis for memory distribution insights
- **CorrelationAgent**: Cross-file correlation for integrated troubleshooting

## Model Providers

Ollama (default, local AI):
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

## Repository Hygiene

- .gitignore excludes:
  - target/
  - dependency-reduced-pom.xml
  - build artifacts (*.class, *.jar)
  - IDE files (.idea/, .vscode/, etc.)
  - OS files (.DS_Store, Thumbs.db)

## Project Structure

- **src/main/java/com/example/JVMTroubleshooter.java**
  - Main CLI application with command parsing (load, analyze, compare, correlate, config, etc.)
- **src/main/java/com/example/agents/**
  - **GCLogAgent.java** - Garbage collection log analysis agent
  - **HSErrLogAgent.java** - JVM crash log analysis agent
  - **NMTAgent.java** - Native Memory Tracking output analysis agent
  - **HeapHistogramAgent.java** - Heap histogram analysis agent (single/comparison)
  - **PmapAgent.java** - Process memory map analysis agent
  - **CorrelationAgent.java** - Cross-file correlation analysis agent
  - **GCTools.java** - Utility tools for parsing GC logs and calculating metrics
  - **NMTTools.java** - Utility tools for parsing NMT memory output
  - **PmapTools.java** - Utility tools for parsing PMAP output
  - **CorrelationTools.java** - Utility tools for cross-file correlation
  - **HeapHistogramTools.java** - Utility tools for parsing heap histograms
- **src/main/java/com/example/data/**
  - Data models and enums (DataType, DiagnosticData, AnalysisResult, etc.)
- **src/main/java/com/example/modelproviders/**
  - **OCIChatModelProvider.java** - Oracle Cloud AI integration
  - **OllamaChatModelProvider.java** - Local Ollama AI integration

## Roadmap

- Add agents for thread dumps and jstat performance metrics
- JFR file analysis support
- Advanced SupervisorAgent orchestration for complex multi-file workflows
