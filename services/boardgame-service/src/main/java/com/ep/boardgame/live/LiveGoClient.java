package com.ep.boardgame.live;

/**
 * D-192: HTTP client abstraction used by {@link BoardCastService} to call the
 * live-go service. Decoupling it as an interface lets tests inject a mock
 * implementation and lets the production code swap JDK HttpClient for a
 * Resilience4j-wrapped client without touching call sites.
 */
public interface LiveGoClient {

    /**
     * Create a broadcast room in live-go.
     *
     * @param gameRoomId the source game room id
     * @return the live-go room info (ingest + play URLs), or null if unreachable
     */
    LiveRoomInfo createBroadcastRoom(Long gameRoomId);

    /**
     * Stop a broadcast room in live-go.
     *
     * @param gameRoomId the source game room id
     * @return true if the room was stopped (or already absent)
     */
    boolean stopBroadcastRoom(Long gameRoomId);

    /** Live-go room info returned when creating a broadcast room. */
    record LiveRoomInfo(String roomId, String ingestUrl, String playUrl) {}
}
