# JVM Troubleshooting Agentic Assistant - Design Document

## 1. Project Overview

The JVM Troubleshooting Agentic Assistant is a multi-agent AI system built with LangChain4j that helps analyze JVM diagnostic data. The system uses specialized AI agents to process different types of JVM logs and provide actionable insights for performance optimization and issue resolution.

### 1.1 Key Features
- Multi-agent architecture using LangChain4j
- Support for multiple JVM diagnostic data types (GC logs, thread dumps, crash logs, etc.)
- AI-powered analysis using local Ollama models or cloud providers (OCI)
- Interactive command-line interface
- Extensible agent framework for adding new diagnostic capabilities

### 1.2 Target Users
- JVM application developers
- System administrators
- Performance engineers
- DevOps engineers troubleshooting JVM applications

## 2. Architecture Overview

### 2.1 High-Level Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   CLI Interface │    │  Agent System   │    │   AI Models     │
│                 │    │                 │    │                 │
│ JVMTroubleshooter│───▶│ SupervisorAgent │───▶│ Ollama/OCI     │
│                 │    │                 │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                             │
                             ▼
                      ┌─────────────────┐
                      │  Sub-Agents     │
                      │                 │
                      │ GCLogAgent      │
                      │ ThreadDumpAgent │
                      │ ...             │
                      └─────────────────┘
```

### 2.2 Core Components

1. **JVMTroubleshooter** - Main application entry point and CLI interface
2. **Agent System** - Multi-agent orchestration using LangChain4j
3. **Data Models** - Type-safe data structures for diagnostic information
4. **Model Providers** - Abstraction layer for different AI model backends
5. **Tools** - Specialized utilities for extracting metrics from logs

## 3. Component Details

### 3.1 JVMTroubleshooter (Main Application)

**Location:** `src/main/java/com/example/JVMTroubleshooter.java`

**Responsibilities:**
- Command-line interface management
- User interaction (load, analyze, ask, status commands)
- Diagnostic data loading and validation
- Agent orchestration and result presentation

**Key Methods:**
- `main()` - Application entry point
- `startInteractiveMode()` - CLI loop
- `loadDiagnosticData()` - File loading and type detection
- `analyzeDiagnosticData()` - Agent execution and result handling

### 3.2 Agent System

#### Supervisor Agent
- **Purpose:** Orchestrates multiple specialized agents
- **Strategy:** Uses chat memory and summarization for context management
- **Response Strategy:** Returns the last agent's response (sequential processing)

#### Sub-Agents
- **GCLogAgent:** Specialized for garbage collection log analysis
- **Future:** ThreadDumpAgent, HeapDumpAgent, etc.

#### Agent Interface Pattern
```java
public interface GCLogAgent {
    @Agent(name = "gcLogAgent", description = "...")
    @SystemMessage("You are an expert in JVM GC troubleshooting...")
    @UserMessage("Analyze the following GC log content...")
    String analyze(@V("logContent") String logContent);
}
```

### 3.3 Data Models

#### DiagnosticData
- **Purpose:** Represents input diagnostic data
- **Fields:** type, content, sourceFile, timestamp, metadata
- **Features:** Automatic type detection, metadata support

#### DataType Enumeration
- **Supported Types:**
  - GC_LOG: Garbage collection logs
  - THREAD_DUMP: JVM thread dumps
  - HS_ERR_LOG: JVM crash logs
  - PERFORMANCE_METRICS: Performance monitoring data
  - HEAP_DUMP: Memory heap dumps

#### AnalysisResult
- **Purpose:** Structured analysis output
- **Fields:** issues, recommendations, confidence score
- **Features:** Issue severity levels, recommendation priorities

### 3.4 Model Providers

#### OllamaChatModelProvider
- **Purpose:** Local AI model integration
- **Configuration:** Environment variables (OLLAMA_BASE_URL, OLLAMA_MODEL_NAME)
- **Default Model:** llama3.2
- **Features:** Request logging, temperature control

#### OCIChatModelProvider
- **Purpose:** Oracle Cloud AI integration
- **Configuration:** OCI config files, compartment ID, model selection
- **Model:** xai.grok3-fast
- **Authentication:** ConfigFileAuthenticationDetailsProvider

### 3.5 Tools

#### GCTools
- **Purpose:** Specialized utilities for GC log analysis
- **Available Tools:**
  - `extractPauses()` - Extract GC pause times
  - `calculateThroughput()` - Calculate GC throughput percentage
  - `detectCollector()` - Identify garbage collector type
  - `getHeapSizes()` - Parse heap generation sizes

## 4. Data Flow

### 4.1 Analysis Workflow

1. **User Input:** Load diagnostic file via CLI
2. **Data Loading:** File read → type detection → DiagnosticData creation
3. **Agent Selection:** Supervisor agent selects appropriate sub-agent based on data type
4. **Analysis Execution:**
   - Sub-agent processes data using tools
   - AI model generates insights
   - Structured results produced
5. **Result Presentation:** Formatted output to user

### 4.2 Agent Communication

```
User Request → SupervisorAgent → SubAgent → Tools → AI Model → Response
                     ↓
               Context Memory
                     ↓
              Response Strategy
```

## 5. Dependencies

### 5.1 Core Dependencies

- **LangChain4j (1.8.0):** Agent framework and AI integration
- **LangChain4j Agentic (1.8.0-beta15):** Multi-agent orchestration
- **LangChain4j Ollama (1.9.1):** Local model support
- **LangChain4j OCI GenAI (1.8.0-beta15):** Cloud AI support

### 5.2 Supporting Libraries

- **Jackson (2.15.2):** JSON processing
- **SLF4J + Logback:** Logging framework
- **OCI Java SDK (3.76.1):** Oracle Cloud integration

### 5.3 Build Tools

- **Maven:** Dependency management and build
- **Maven Compiler Plugin (3.11.0):** Java 21 compilation
- **Maven Shade Plugin:** Uber-JAR creation
- **Exec Maven Plugin:** Application execution

## 6. Configuration

### 6.1 Build Configuration

**Java Version:** 21
**Packaging:** Executable JAR with shaded dependencies
**Main Class:** `com.example.JVMTroubleshooter`

## 7. Usage

### 7.1 Building the Application

```bash
mvn clean compile
mvn package  # Creates executable JAR
```

### 7.2 Running the Application

```bash
# Using Maven exec plugin
mvn exec:java

# Using packaged JAR
java -jar target/jvm-troubleshooting-agentic-assistant-1.0.0-SNAPSHOT.jar
```

### 7.3 CLI Commands

```
JVM Troubleshooting Agentic Assistant
=====================================
Commands:
  load <file>     - Load diagnostic data file
  analyze         - Analyze loaded diagnostic data
  ask <question>  - Ask a question about the loaded data
  status          - Show current status
  help            - Show this help
  quit            - Exit the application
```

### 7.4 Supported File Types

- **GC Logs:** Files containing "gc" or "garbage" in filename
- **Thread Dumps:** Files containing "thread" or "dump"
- **Crash Logs:** Files containing "hs_err" or "error"
- **Performance Metrics:** Files containing "metrics" or "perf"
- **Heap Dumps:** Files containing "heap"

## 8. Extension Points

### 8.1 Adding New Agents

1. Create agent interface with `@Agent` annotation
2. Define system and user messages
3. Implement analysis methods
4. Register with SupervisorAgent

### 8.2 Adding New Tools

1. Create tool class with `@Tool` annotated methods
2. Implement utility functions
3. Integrate with agent interfaces

### 8.3 Adding New Data Types

1. Extend `DataType` enum
2. Update `fromFileName()` method
3. Create corresponding agents and tools

### 8.4 Adding New Model Providers

1. Implement `ChatModel` creation logic
2. Handle authentication and configuration
3. Add to model provider package

## 9. Performance Considerations

### 9.1 Memory Usage
- Large log files processed in memory
- Consider streaming for very large files (>100MB)

### 9.2 AI Model Latency
- Local Ollama models: Fast inference, no network latency
- Cloud OCI models: Network-dependent, higher latency

### 9.3 Agent Orchestration
- Supervisor agent manages context memory
- Configurable agent invocation limits (currently 2)

## 10. Security Considerations

### 10.1 Data Privacy
- Diagnostic logs may contain sensitive information
- Consider local processing for sensitive data

### 10.2 Authentication
- OCI credentials stored in config files
- Environment variables for Ollama configuration

### 10.3 Network Security
- HTTPS for OCI API calls
- Local Ollama deployment recommended for air-gapped environments

## 11. Future Enhancements

### 11.1 Planned Features
- Additional agent types (ThreadDumpAgent, HeapDumpAgent)
- Web-based UI
- Batch processing capabilities
- Integration with monitoring systems
- Custom rule engine for issue detection

### 11.2 Technology Evolution
- Upgrade to latest LangChain4j versions
- Support for additional AI model providers
- Enhanced tool capabilities
- Improved error handling and logging
