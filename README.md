# JVM Troubleshooting Agentic Assistant

An interactive CLI tool that analyzes JVM diagnostic data using a multi-agent AI architecture built with LangChain4j. It supports multiple input files and routes them to specialized agents.

## Features

- Interactive CLI:
  - `load <file>` to load a single diagnostic file
  - `analyze [<file>]` to analyze the loaded file or a specified single file
  - `ask <question>` to query about loaded data
- GC log analysis via GCLogAgent
- HS_ERR log analysis via HSErrLogAgent
- NMT memory analysis via NMTAgent
- Heap histogram comparison via HeapHistogramAgent
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
- `load <file>`         Load a single diagnostic file
- `analyze [<file>]`    Analyze the loaded file or a specified single file
- `ask <question>`      Ask a question about the loaded data (supports follow-up questions with conversation history)
- `status`              Show current loaded file
- `help`                Show command help
- `quit`                Exit the application

Notes:
- analyze without args analyzes the loaded file.
- analyze with a file arg analyzes that single file without changing the loaded file.
- Only one file can be loaded at a time.

Examples:
- `load` sample-gc.log
- `analyze`
- `analyze` hs_err_pid123.log
- `ask` "What are the main issues in this log?"
- `ask` "Based on the previous response, what tuning parameters should I adjust?"

## Supported Data Types

Current Supported Data Types:
- GC logs
- Crash logs
- Native Memory Tracking (NMT) output
- Heap histograms

To be supported:
- Thread dumps
- Performance metrics
- JFR Files

Current agent coverage:
- GC logs analyzed via GCLogAgent
- Crash logs (hs_err) analyzed via HSErrLogAgent
- NMT memory analyzed via NMTAgent
- Heap histograms analyzed via HeapHistogramAgent

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
  - Interactive CLI and command parser (load, analyze, ask)
- src/main/java/com/example/agents/GCLogAgent.java
  - GC log analysis agent definition
- src/main/java/com/example/agents/HSErrLogAgent.java
  - HS_ERR log analysis agent definition
- src/main/java/com/example/agents/GCTools.java
  - Utility tools for parsing GC logs (available to integrate)
- src/main/java/com/example/agents/NMTAgent.java
  - NMT memory analysis agent definition
- src/main/java/com/example/agents/NMTTools.java
  - Utility tools for parsing NMT output
- src/main/java/com/example/data/*
  - Data models (DataType, DiagnosticData, AnalysisResult, Issue, Recommendation, etc.)
- src/main/java/com/example/modelproviders/*
  - OCIChatModelProvider (default), OllamaChatModelProvider (optional)
- DESIGN.md
  - Full architecture and design document

## Roadmap

- Add agents for thread dumps, performance metrics, and JFR files
- correlate and analyze multiple files
- Integrate SupervisorAgent for multi-file analysis
