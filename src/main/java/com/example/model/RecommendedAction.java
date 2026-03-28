package com.example.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical operator action emitted alongside findings.
 */
public record RecommendedAction(
    String id,
    String summary,
    String rationale,
    ActionType actionType,
    ActionPriority priority,
    List<String> steps,
    List<String> relatedFindingIds
) {

    public RecommendedAction {
        steps = steps == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(steps));
        relatedFindingIds = relatedFindingIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(relatedFindingIds));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", id);
        canonical.put("summary", summary);
        canonical.put("rationale", rationale);
        canonical.put("actionType", actionType != null ? actionType.name() : null);
        canonical.put("priority", priority != null ? priority.name() : null);
        canonical.put("steps", steps);
        canonical.put("relatedFindingIds", relatedFindingIds);
        return canonical;
    }
}
