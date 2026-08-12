package com.ep.memory.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * D-045 Four-level context window assembled by the memory service.
 *
 * <ul>
 *   <li>L0 immediateWindow: last ~10 conversation turns (raw messages)</li>
 *   <li>L1 workingSummary: rolling summary of the current session</li>
 *   <li>L2 longTermMemories: personalized long-term recall</li>
 *   <li>L3 orgMemories: organization-wide shared memory (tenant scope)</li>
 * </ul>
 */
public record ContextWindow(
        List<Map<String, String>> immediateWindow,
        String workingSummary,
        List<Memory> longTermMemories,
        List<Memory> orgMemories
) implements Serializable {
}
