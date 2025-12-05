package com.example.data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents diagnostic data submitted for analysis
 */
public record DiagnosticData(
    DataType type,
    String content,
    String sourceFile,
    LocalDateTime timestamp,
    Map<String, Object> metadata
) {

    public DiagnosticData(DataType type, String content, String sourceFile) {
        this(type, content, sourceFile, LocalDateTime.now(), new HashMap<>());
    }

    public DiagnosticData(DataType type, String content, String sourceFile, Map<String, Object> metadata) {
        this(type, content, sourceFile, LocalDateTime.now(), metadata);
    }

    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public int getContentSize() {
        return content != null ? content.length() : 0;
    }

    @Override
    public String toString() {
        return "DiagnosticData{" +
                "type=" + type +
                ", sourceFile='" + sourceFile + '\'' +
                ", timestamp=" + timestamp +
                ", contentSize=" + getContentSize() +
                ", metadata=" + metadata +
                '}';
    }
}
