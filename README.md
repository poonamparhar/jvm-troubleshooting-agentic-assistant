# JVM Troubleshooting Agentic Assistant

An interactive CLI tool that analyzes JVM diagnostic data using a multi-agent AI architecture built with LangChain4j. It supports multiple input files and routes them to specialized agents (starting with GC logs).

## Features

- Interactive CLI with support for multiple files:
  - use <file...> to set the working set
  - add <file...> to append files to the working set
  - analyze [file...] to analyze the working set or specific files
- GC log analysis via GCLogAgent
- Local AI model via Ollama (default)
- Optional OCI GenAI model provider available in the codebase

## Requirements

- Java 21+
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

Using Maven:
- mvn exec:java

Using the packaged JAR:
- java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar

## CLI Usage

Commands:
- use <file...>       Set the working set to these diagnostic files
- add <file...>       Add files to the current working set (deduplicated)
- analyze [file...]   Analyze the current working set, or analyze the given files if provided
- status              Show current working set and summary
- help                Show command help
- quit                Exit the application

Notes:
- analyze without args uses the working set (previously set via use/add).
- analyze with file args does not change the working set; it analyzes just those files.

Examples:
- use sample-gc.log thread1.txt hs_err_pid123.log
- add more-gc.log
- analyze
- analyze sample-gc.log more-gc.log

## Supported Data Types

- GC logs (detected when filename contains gc or garbage)
- Thread dumps (detected when filename contains thread or dump)
- Crash logs (detected when filename contains hs_err or error)
- Performance metrics (detected when filename contains metrics or perf)
- Heap dumps (detected when filename contains heap)

Current agent coverage:
- GC logs are analyzed via GCLogAgent.
- Other types are detected and acknowledged, with analysis to be added in future iterations.

## Model Providers

Ollama (default):
- Environment variables:
  - OLLAMA_BASE_URL (default http://localhost:11434)
  - OLLAMA_MODEL_NAME (default llama3.2)

OCI GenAI (optional, not default):
- Code scaffold present in src/main/java/com/example/modelproviders/OCIChatModelProvider.java
- To enable OCI:
  - Ensure OCI CLI/SDK configuration is available on the machine
  - Adapt JVMTroubleshooter to use OCIChatModelProvider.createChatModel()
  - Update authentication details as needed

## Repository Hygiene

- .gitignore excludes:
  - target/
  - dependency-reduced-pom.xml
  - build artifacts (*.class, *.jar)
  - IDE files (.idea/, .vscode/, etc.)
  - OS files (.DS_Store, Thumbs.db)

## Project Structure

- src/main/java/com/example/JVMTroubleshooter.java
  - Interactive CLI and command parser (use, add, analyze)
- src/main/java/com/example/agents/GCLogAgent.java
  - GC log analysis agent definition
- src/main/java/com/example/agents/GCTools.java
  - Utility tools for parsing GC logs (available to integrate)
- src/main/java/com/example/data/*
  - Data models (DataType, DiagnosticData, AnalysisResult, Issue, Recommendation, etc.)
- src/main/java/com/example/modelproviders/*
  - OllamaChatModelProvider (default), OCIChatModelProvider (optional)
- DESIGN.md
  - Full architecture and design document

## Roadmap

- Add agents for thread dumps, hs_err logs, performance metrics, and heap dumps
- JSON output and file reports for analyze
- Batch and parallel execution options
- Preview/validate commands and more CLI ergonomics

## License

Add your chosen license here (e.g., Apache-2.0, MIT).
