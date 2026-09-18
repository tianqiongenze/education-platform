package com.ep.grading.ide.yjs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * D-216: in-memory registry of {@link YjsDocument} instances keyed by docId.
 *
 * <p>The {@link YjsUpdateBuffer} consumer thread needs to look up (or lazily
 * create) the in-memory {@link YjsDocument} for an inbound update before it can
 * call {@link YjsDocument#applyUpdate} and {@link YjsSnapshotStore#saveSnapshot}.
 * Prior to this, {@link YjsSyncHandler} held its own per-room document and there
 * was no shared lookup; the buffer needs a single authoritative store so that
 * updates from multiple WebSocket rooms for the same docId land on the same
 * CRDT object.
 *
 * <p>Thread-safety: {@link ConcurrentHashMap#computeIfAbsent} guarantees the
 * {@link YjsDocument} is created exactly once per docId. Access to the
 * document itself is serialised by the Disruptor consumer thread (single
 * worker), so the non-thread-safe {@link YjsDocument} is safe under that
 * contract.
 */
@Component
public class YjsDocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(YjsDocumentRegistry.class);

    private final ConcurrentHashMap<String, YjsDocument> documents = new ConcurrentHashMap<>();

    /** Get the existing document for docId, or lazily create one. */
    public YjsDocument getOrCreate(String docId) {
        return documents.computeIfAbsent(docId, YjsDocument::new);
    }

    /** Get the existing document for docId, or null if absent. */
    public YjsDocument get(String docId) {
        return documents.get(docId);
    }

    /** Remove a document (e.g. when an IDE session is torn down). */
    public YjsDocument remove(String docId) {
        YjsDocument removed = documents.remove(docId);
        if (removed != null) {
            log.debug("[YjsDocumentRegistry] removed doc {}", docId);
        }
        return removed;
    }

    /** Number of currently registered documents (monitoring). */
    public int size() {
        return documents.size();
    }
}
