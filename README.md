# JVM Troubleshooting Agentic Assistant

An interactive CLI tool that analyzes JVM diagnostic data using a multi-agent AI architecture built with LangChain4j. It supports multiple input files and routes them to specialized agents (starting with GC logs).

## Features

- Interactive CLI with support for multiple files:
  - use <file...> to set the working set
  - analyze [<file>] to analyze the first file in working set or a specified single file
  - ask <question> to query about loaded data
- GC log analysis via GCLogAgent
- HS_ERR log analysis via HSErrLogAgent
- OCI GenAI model (default)
- Optional local AI via Ollama

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
- analyze [<file>]    Analyze the current working set (first file) or a specified single file
- ask <question>      Ask a question about the loaded data
- status              Show current working set and summary
- help                Show command help
- quit                Exit the application

Notes:
- analyze without args analyzes the first file in the working set.
- analyze with a file arg analyzes that single file without changing the working set.
- Working set can hold multiple files, but analyze processes one at a time.

Examples:
- use sample-gc.log hs_err_pid123.log
- analyze
- analyze sample-gc.log
- ask What are the main issues in this log?

## Supported Data Types

Detection is now content-based (using patterns in file contents)

- GC logs: Contains "gc(", "[gc", or "full gc"
- Thread dumps: Contains "full thread dump" or "java stack information"
- Crash logs: Contains "a fatal error has been detected"
- Performance metrics: Contains "cpu time", "heap size", or "metrics"
- Heap dumps: Contains "java profile" or "heap dump"

Current agent coverage:
- GC logs analyzed via GCLogAgent
- Crash logs (hs_err) analyzed via HSErrLogAgent

## Model Providers

OCI GenAI (default):
- Code in src/main/java/com/example/modelproviders/OCIChatModelProvider.java
- Requires OCI CLI/SDK configuration
- Uncomment OllamaChatModelProvider in JVMTroubleshooter.java to switch to local model

Ollama (optional, local AI):
- Environment variables:
  - OLLAMA_BASE_URL (default http://localhost:11434)
  - OLLAMA_MODEL_NAME (default llama3.2)
- Run Ollama with a compatible model

## Repository Hygiene

- .gitignore excludes:
  - target/
  - dependency-reduced-pom.xml
  - build artifacts (*.class, *.jar)
  - IDE files (.idea/, .vscode/, etc.)
  - OS files (.DS_Store, Thumbs.db)

## Project Structure

- src/main/java/com/example/JVMTroubleshooter.java
  - Interactive CLI and command parser (use, analyze, ask)
- src/main/java/com/example/agents/GCLogAgent.java
  - GC log analysis agent definition
- src/main/java/com/example/agents/HSErrLogAgent.java
  - HS_ERR log analysis agent definition
- src/main/java/com/example/agents/GCTools.java
  - Utility tools for parsing GC logs (available to integrate)
- src/main/java/com/example/data/*
  - Data models (DataType, DiagnosticData, AnalysisResult, Issue, Recommendation, etc.)
- src/main/java/com/example/modelproviders/*
  - OCIChatModelProvider (default), OllamaChatModelProvider (optional)
- DESIGN.md
  - Full architecture and design document

## Roadmap

- Add agents for thread dumps, performance metrics, and heap dumps
- JSON output and file reports for analyze
- Batch and parallel execution options (analyze multiple files)
- Preview/validate commands and more CLI ergonomics
- Integrate SupervisorAgent for multi-file analysis

## License

Add your chosen license here (e.g., Apache-2.0, MIT).
