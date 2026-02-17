package com.example.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NMTTools {

    @Tool("Parse memory usage for a specific category from NMT output")
    public Map<String, Long> parseMemoryCategory(@P("NMT output content") String nmtContent, @P("category name") String category) {
        Map<String, Long> result = new HashMap<>();
        // Pattern for categories like "-                     Class (reserved=1069050KB, committed=26430KB)"
        Pattern p = Pattern.compile("-\\s*" + Pattern.quote(category) + "\\s*\\(reserved=(\\d+)KB, committed=(\\d+)KB\\)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(nmtContent);
        if (m.find()) {
            result.put("reserved", Long.parseLong(m.group(1)));
            result.put("committed", Long.parseLong(m.group(2)));
        }
        return result;
    }

    @Tool("Extract metaspace usage details from Class category")
    public Map<String, Long> parseMetaspaceUsage(@P("NMT output content") String nmtContent) {
        Map<String, Long> result = new HashMap<>();
        // Pattern for Metadata: reserved=24576KB, committed=24280KB, used=23890KB, free=390KB
        Pattern p = Pattern.compile("Metadata:\\s*reserved=(\\d+)KB, committed=(\\d+)KB, used=(\\d+)KB, free=(\\d+)KB", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(nmtContent);
        if (m.find()) {
            result.put("reserved", Long.parseLong(m.group(1)));
            result.put("committed", Long.parseLong(m.group(2)));
            result.put("used", Long.parseLong(m.group(3)));
            result.put("free", Long.parseLong(m.group(4)));
        }
        return result;
    }

    @Tool("Extract thread count and stack memory from Thread category")
    public Map<String, Object> parseThreadInfo(@P("NMT output content") String nmtContent) {
        Map<String, Object> result = new HashMap<>();
        // Pattern for (thread #25), (stack: reserved=23296KB, committed=23296KB)
        Pattern threadP = Pattern.compile("\\(thread #(\\d+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher tm = threadP.matcher(nmtContent);
        if (tm.find()) {
            result.put("threadCount", Integer.parseInt(tm.group(1)));
        }
        Pattern stackP = Pattern.compile("\\(stack: reserved=(\\d+)KB, committed=(\\d+)KB\\)", Pattern.CASE_INSENSITIVE);
        Matcher sm = stackP.matcher(nmtContent);
        if (sm.find()) {
            result.put("stackReserved", Long.parseLong(sm.group(1)));
            result.put("stackCommitted", Long.parseLong(sm.group(2)));
        }
        return result;
    }
}
