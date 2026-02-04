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
│   CLI Interface │    │  Direct Agent   │    │   AI Models     │
│                 │    │  Routing        │    │                 │
│ JVMTroubleshooter│───▶│ (Supervisor    │───▶│ OCI (default)  │
│                 │    │  optional)      │    │ Ollama (opt)    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                             │
                             ▼
                      ┌─────────────────┐
                      │  Sub-Agents     │
                      │                 │
                      │ GCLogAgent      │
                      │ HSErrLogAgent   │
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
- `loadDiagnosticData()` - File loading with content-based type detection via DataType.fromContents()
- `askQuestion()` - Handles questions with conversation history

### 3.2 Agent System

#### Agent Routing
- **Current Implementation:** Direct routing to sub-agents based on DataType
- **Supervisor Agent:** Optionally, use Supervisor Agent for routing
- **Strategy:** Chat memory for conversation history in 'ask' command
- **Response Strategy:** Direct agent response

#### Sub-Agents
- **GCLogAgent:** Specialized for garbage collection log analysis
- **HSErrLogAgent:** Specialized for JVM crash log analysis (hs_err files)
- **NMTAgent:** Specialized for JVM Native Memory Tracking output analysis
- **HeapHistogramAgent:** Specialized for JVM heap histogram comparison and memory leak detection

#### Agent Interface Pattern
```java
public interface GCLogAgent {
    @Agent(name = "gcLogAgent", description = "...")
    @SystemMessage("You are an expert in JVM GC troubleshooting...")
    @UserMessage("Analyze the following GC log content...")
    String analyze(@V("logContent") String logContent);
}

public interface HSErrLogAgent {
    @Agent(name = "hsErrLogAgent", description = "Analyze JVM crash logs (hs_err) to identify crash causes and provide recommendations.")
    @SystemMessage("You are an expert in JVM crash analysis and HotSpot troubleshooting...")
    @UserMessage("Analyze the following JVM crash log content...")
    String analyze(@V("logContent") String logContent);
}
```

#### HSErrLogAgent Details
- **Purpose:** Analyzes JVM crash logs (hs_err files) to identify root causes of fatal JVM errors
- **Capabilities:**
  - Crash type identification (OOM, segfaults, etc.)
  - JVM configuration analysis (heap settings, GC collector, VM arguments)
  - Memory usage examination (heap, metaspace, GC history)
  - Stack trace analysis for problematic code paths
  - System information review
  - Root cause determination and prevention recommendations
- **Analysis Steps:**
  1. Identify crash type and immediate cause
  2. Extract JVM version and configuration details
  3. Analyze memory usage patterns
  4. Examine stack traces for code issues
  5. Review system and thread information
  6. Provide specific recommendations with priorities

#### NMTAgent Details
- **Purpose:** Analyzes JVM Native Memory Tracking (NMT) output to identify memory pressure and inefficiencies
- **Capabilities:**
  - Metaspace usage analysis and OutOfMemory detection
  - Code cache utilization monitoring
  - Thread stack memory assessment
  - GC native memory overhead evaluation
  - Memory category imbalance detection
- **Analysis Steps:**
  1. Parse memory usage for key categories (Class, Thread, Code, GC)
  2. Calculate utilization ratios and identify pressure points
  3. Detect specific issues like metaspace nearing limits, code cache saturation, excessive thread counts
  4. Assess overall memory health and OOM risk
  5. Provide targeted recommendations for JVM tuning parameters

#### HeapHistogramAgent Details
- **Purpose:** Analyzes JVM heap histogram comparisons to identify memory leaks and growth patterns
- **Capabilities:**
  - Class instance count and byte usage comparison
  - Memory leak suspect identification based on growth rates
  - Detection of collection classes accumulating entries
  - Custom application class analysis for unexpected growth
  - Total heap size and distribution analysis
- **Analysis Steps:**
  1. Parse baseline and current heap histograms
  2. Compare instance counts and memory usage per class
  3. Calculate growth rates and identify significant increases
  4. Flag potential leak suspects (e.g., HashMap$Entry, ArrayList with high growth)
  5. Assess overall heap growth and provide leak investigation recommendations

### 3.3 Data Models

#### DiagnosticData
- **Purpose:** Represents input diagnostic data
- **Fields:** type, content, sourceFile, timestamp, metadata
- **Features:** Automatic type detection, metadata support

#### DataType Enumeration
- **Data Types:**
  - GC_LOG: Garbage collection logs
  - HS_ERR_LOG: JVM crash logs
  - NMT_MEMORY: JVM Native Memory Tracking output
  - HEAP_HISTOGRAM: JVM heap histogram output
  - JFR: Flight recording file

#### AnalysisResult
- **Purpose:** Structured analysis output
- **Fields:** issues, recommendations, confidence score
- **Features:** Issue severity levels, recommendation priorities

### 3.4 Model Providers

#### OCIChatModelProvider (Default)
- **Purpose:** Oracle Cloud AI integration
- **Configuration:** OCI config files, compartment ID, model selection

#### OllamaChatModelProvider (Optional)
- **Purpose:** Local AI model integration
- **Configuration:** Environment variables (OLLAMA_BASE_URL, OLLAMA_MODEL_NAME)
- **Default Model:** llama3.2
- **Features:** Request logging, temperature control

### 3.5 Tools

#### GCTools
- **Purpose:** Specialized utilities for GC log analysis
- **Available Tools:**
  - `extractPauses()` - Extract GC pause times
  - `calculateThroughput()` - Calculate GC throughput percentage using real elapsed time (uptime timestamps) and summed GC pause durations
  - `detectCollector()` - Identify garbage collector type (ZGC, G1, Parallel, CMS)
  - `getHeapSizes()` - Parse heap generation sizes
  - `extractTimestampsFromGCLog()` - Extract wall-clock timestamps from GC logs
  - `extractStructuredTimestamps()` - Extract both wall-clock and uptime timestamps from GC logs

#### PmapTools
- **Purpose:** Specialized utilities for pmap output parsing and memory mapping analysis
- **Available Tools:**
  - `parseTotalMemoryFromPmap()` - Extract total memory usage from pmap output
  - `parseBreakdownByCategory()` - Categorize memory mappings by type (anon, shared_libs, stack, heap, file_backed, other)
  - `parseTopMappings()` - Extract top N largest memory mappings by RSS size

#### NMTTools
- **Purpose:** Specialized utilities for NMT output parsing and analysis
- **Available Tools:**
  - `parseMemoryCategory()` - Extract memory usage for specific categories
  - `parseMetaspaceUsage()` - Parse detailed metaspace statistics
  - `parseThreadInfo()` - Extract thread count and stack memory info
  - `calculateMemoryRatios()` - Compute utilization ratios across categories
  - `detectMemoryPressure()` - Identify potential memory issues

#### HeapHistogramTools
- **Purpose:** Specialized utilities for heap histogram parsing and comparison
- **Available Tools:**
  - `parseHistogram()` - Parse single histogram into class statistics
  - `compareHistograms()` - Compare two histograms and calculate growth metrics
  - `identifyLeakSuspects()` - Flag classes with suspicious growth patterns
  - `calculateTotalHeapGrowth()` - Compute overall heap size changes
  - `getTopMemoryConsumers()` - Identify classes consuming most memory

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

### 5.2 Libraries

- **OCI Java SDK (3.76.1):** Oracle Cloud integration

### 5.3 Build Tools

- **Maven:** Dependency management and build
- **Maven Compiler Plugin (3.11.0):** Java 21 compilation
- **Maven Shade Plugin:** Uber-JAR creation
- **Exec Maven Plugin:** Application execution

## 6. Configuration

### 6.1 Build Configuration

**Java Version:** 25
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
Commands:
  load <file>     - Load diagnostic data file
  analyze         - Analyze loaded diagnostic data
  ask <question>  - Ask a question about the loaded data
  status          - Show current status
  help            - Show this help
  quit            - Exit the application
```

### 7.4 Supported Data Types (Content-Based Detection)

- **GC Logs:**
- **Crash Logs:**
- **NMT Memory:**
- **Heap Histograms:**

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
2. Create corresponding agents and tools

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
