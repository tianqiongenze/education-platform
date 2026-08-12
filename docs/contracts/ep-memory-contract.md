# ep-memory Cross-Language Contract (D-045 / D-094)

> **Status:** Active — hand-mirrored contract, manually synchronized.
> **Last verified:** 2026-08-08 (D-094)
> **Owners:** SP-04 AI (Python) + ep-memory-java SDK (Java)

This document pins the wire contract between the Python memory service
(`services/sp04-ai/ep_memory/rpc.py`) and the Java SDK
(`services/ep-memory-java/src/main/java/com/ep/memory/MemoryRpcService.java`
+ `model/*`). It is the single reference both sides must keep in sync.

## Scope

The Java side exposes a **Dubbo RPC interface** (`MemoryRpcService`) plus a
REST client (`MemoryClient`) that talks to the SP-04 AI Gateway's REST
surface (`/v1/memory/*`). The Python side (`MemoryRpcDispatcher`) is a
framework-agnostic dispatcher over `MemoryManager` that defines the
authoritative request/response JSON shapes and a method-name table so the
same contract can serve both `application/json` REST and a future framed-JSON
RPC transport.

Design goals (from `rpc.py`):

1. Java and Python share one source of truth.
2. A future Dubbo / Tri-protocol provider can reuse the same contract.
3. The gateway can serve both REST and framed-JSON RPC from one dispatcher.

## Method signatures

The five RPC methods mirror 1:1 between the two languages. Java signatures
are the Dubbo interface; Python signatures are the async dispatcher.

| Method | Java (`MemoryRpcService`) | Python (`MemoryRpcDispatcher`) | Returns |
|--------|---------------------------|--------------------------------|---------|
| add | `void add(MemoryAddRequest request)` | `async def add(request: dict) -> dict` | status JSON |
| search | `List<Memory> search(MemorySearchRequest request)` | `async def search(request: dict) -> list[dict]` | `Memory[]` |
| getContext | `ContextWindow getContext(String tenantId, String userId, String sessionId)` | `async def get_context(tenant_id, user_id, session_id, query=None) -> dict` | `ContextWindow` JSON |
| forget | `void forget(String memoryId)` | `async def forget(memory_id, tenant_id=None) -> dict` | status JSON |
| promoteToOrg | `void promoteToOrg(String memoryId)` | `async def promote_to_org(memory_id, tenant_id=None) -> dict` | status JSON |

Python method-name table (for a future framed-JSON transport), from
`rpc.py::RPC_METHODS`:

```python
RPC_METHODS = {
    "add":           "add",
    "search":        "search",
    "getContext":    "get_context",
    "forget":        "forget",
    "promoteToOrg":  "promote_to_org",
}
```

### Return-type asymmetry (intentional)

The Java Dubbo interface declares `void` for `add` / `forget` /
`promoteToOrg`, while the Python REST dispatcher returns a small status JSON
for those same methods (`{"status": "added", ...}`). This is intentional:
over REST the caller expects an HTTP body; over Dubbo the caller relies on
the RPC return + exceptions. Both sides agree on the *input* shapes; the
status JSON is a REST-only convenience and is not part of the Dubbo contract.

## Request / response JSON shapes

> All JSON is **camelCase** on the wire to match the Java records. The
> Python side converts its snake_case dataclasses to camelCase in
> `rpc.py::_memory_to_camel` / `_context_to_camel`.

### `MemoryAddRequest`

```json
{
  "tenantId":   "t-123",
  "userId":     "u-456",
  "sessionId":  "s-789",
  "messages":   [ {"role": "user", "content": "..."},
                  {"role": "assistant", "content": "..."} ]
}
```

- Java: `record MemoryAddRequest(String tenantId, String userId, String sessionId, List<Map<String,String>> messages)`
- Python dispatcher reads the same keys (`tenantId`, `userId`, `sessionId`,
  `messages`).
- Validation: `tenantId` and `messages` are required (`RpcError("BAD_REQUEST", ...)`).

Response (REST only): `{"status": "added", "messageCount": <int>}`.

### `MemorySearchRequest`

```json
{
  "tenantId": "t-123",
  "userId":   "u-456",
  "query":    "how do I reset my password?",
  "topK":     5
}
```

- Java: `record MemorySearchRequest(String tenantId, String userId, String query, int topK)`
- Python: `topK` defaults to `5` when omitted.
- Validation: `tenantId` and `query` are required.

Response: a JSON array of `Memory` objects (shape below).

### `Memory`

```json
{
  "id":          "mem_abcdef012345",
  "tenantId":    "t-123",
  "userId":      "u-456",
  "content":     "...",
  "memoryType":  "episodic",
  "score":       0.92,
  "createdAt":   1715000000,
  "metadata":    { }
}
```

- `memoryType` ∈ `{ "episodic", "semantic", "procedural" }`
  (Python `MemoryType` enum / Java `String`).
- Java record: `record Memory(String id, String tenantId, String userId, String content, String memoryType, double score, long createdAt, Map<String,Object> metadata)`.

### `ContextWindow` (response to `getContext`)

```json
{
  "immediateWindow":     [ {"role": "user", "content": "..."} ],
  "workingSummary":      "rolling summary of the session",
  "longTermMemories":    [ { ...Memory } ],
  "orgMemories":         [ { ...Memory } ]
}
```

- Java: `record ContextWindow(List<Map<String,String>> immediateWindow, String workingSummary, List<Memory> longTermMemories, List<Memory> orgMemories)`.
- Four levels: L0 immediateWindow, L1 workingSummary, L2 longTermMemories,
  L3 orgMemories.

### Error envelope

Returned for any HTTP status ≥ 400:

```json
{ "error": { "code": "NOT_FOUND", "message": "memory 'mem_x' not found in L2" } }
```

`code` ∈ `{ "BAD_REQUEST", "FORBIDDEN", "NOT_FOUND", "INTERNAL" }`
(Python `RpcError`). Tenant isolation: a cross-tenant access on `forget` /
`promoteToOrg` yields `FORBIDDEN` (403); a missing L2 memory on
`promoteToOrg` yields `NOT_FOUND` (404).

## Tenant isolation (D-044)

Every method takes `tenantId`. The Python dispatcher enforces ownership on
`forget` / `promoteToOrg` via `_find_owned(memory_id, tenant_id)`; a
mismatch raises `RpcError("FORBIDDEN", ...)`. The Java side must pass
`tenantId` on every call and must not expose a tenant-less overload in a
multi-tenant context.

## Maintenance Warning

> **This contract is hand-mirrored.** There is no codegen, no protobuf, no
> OpenAPI spec tying the two sides together. A change to one side MUST be
> reflected in the other within the same PR, or the cross-language call will
> silently desync (Java `MemoryClient` will deserialize `null`/default
> fields without throwing).

### Known drift risks

1. **Field set mismatch (pre-existing).** The Python `Memory` dataclass
   (`services/sp04-ai/ep_memory/models.py`) carries two fields the Java
   `Memory` record does **not**:
   - `last_accessed_at` (float)
   - `access_count` (int)

   These are access-tracking fields used internally by the Python store.
   They are **intentionally omitted** from the Java record, which is the
   *consumer* of memory, not the *store*. The Python `_memory_to_camel`
   serializer also omits them, so the wire JSON matches the Java record.
   **Risk:** if a future Python change starts emitting `lastAccessedAt` /
   `accessCount` on the wire, the Java record will silently ignore them
   (no failure, just data loss on the Java side). Flag any such change here.

2. **camelCase discipline.** Any new Python field must be added to the
   appropriate `_xxx_to_camel` helper in `rpc.py`, OR the Java record field
   name will not match and deserialization will yield `null`. There is no
   compile-time check across the two languages.

3. **`memoryType` is a free-form string on the Java side** (Java record
   declares `String memoryType`) but an enum on the Python side
   (`MemoryType`). If the Python enum grows a new value and emits it, the
   Java side will accept the unknown string without validation — callers
   must not assume Java has validated it.

4. **`getContext` signature.** Java declares
   `getContext(String tenantId, String userId, String sessionId)` (3 args).
   Python `get_context` additionally accepts an optional `query` (4th arg).
   The Java side currently never sends `query`; if that changes, the Java
   interface must be extended (breaking Dubbo interface change) and the
   Python signature already supports it.

### Change checklist (do all in one PR)

- [ ] Update `services/sp04-ai/ep_memory/rpc.py` (dispatcher + `_xxx_to_camel`).
- [ ] Update `services/sp04-ai/ep_memory/models.py` (Python dataclass).
- [ ] Update `services/ep-memory-java/src/main/java/com/ep/memory/model/*.java`
      (the corresponding record).
- [ ] Update `services/ep-memory-java/src/main/java/com/ep/memory/MemoryRpcService.java`
      if a method signature changed.
- [ ] Update the JSON-shape tables above.
- [ ] Add a row to "Known drift risks" if the change introduces a new
      cross-language divergence.

## Field mapping table (Java ↔ Python ↔ wire)

The canonical `Memory` record exists in two languages. This table pins every
field across the Java record, the Python dataclass, and the on-the-wire JSON
key (camelCase). It is the authoritative reference for the camelCase ↔
snake_case conversion performed by `rpc.py::_memory_to_camel`.

| Wire (JSON) key | Java record field (`com.ep.memory.model.Memory`) | Python dataclass field (`ep_memory.models.Memory`) | Type | On the wire? |
|-----------------|--------------------------------------------------|----------------------------------------------------|------|--------------|
| `id`            | `id`           | `id`                | string        | yes |
| `tenantId`      | `tenantId`     | `tenant_id`         | string        | yes |
| `userId`        | `userId`       | `user_id`           | string        | yes |
| `content`       | `content`      | `content`           | string        | yes |
| `memoryType`    | `memoryType`   | `memory_type`       | string / enum | yes (string on wire; `MemoryType` enum on Python) |
| `score`         | `score`        | `score`             | double / float | yes |
| `createdAt`     | `createdAt`    | `created_at`        | long (epoch ms) / float (epoch s) | yes |
| `metadata`      | `metadata`     | `metadata`          | `Map<String,Object>` / `dict` | yes |
| `lastAccessedAt`| *(absent)*     | `last_accessed_at`  | float (epoch s) | **no** — store-internal, stripped by `_memory_to_camel` |
| `accessCount`   | *(absent)*     | `access_count`      | int            | **no** — store-internal, stripped by `_memory_to_camel` |

### Notes on the mapping

- **Naming convention.** Java is `camelCase`; Python is `snake_case`; the
  wire is **always `camelCase`** to match the Java records. The Python
  serializer `_memory_to_camel` performs the snake_case → camelCase
  conversion for every field that crosses the wire. Any new field added on
  either side MUST be added to `_memory_to_camel` (Python) or it will not
  appear on the wire under the expected key, and the Java record will
  silently deserialize `null`.
- **`last_accessed_at` / `access_count` drift (intentional).** These two
  Python-only fields are access-tracking metadata used internally by the
  Python store (see `Memory.touch()` in `models.py`). They are deliberately
  **not** serialized by `_memory_to_camel` and therefore not present on the
  wire and not on the Java record. The Java side is a *consumer* of memory,
  not the *store*. This is the pre-existing drift documented under
  "Known drift risks" below; the mapping table makes it explicit.
- **`createdAt` unit asymmetry.** The Python `created_at` is a float epoch
  **seconds** (`time.time()`); the Java `createdAt` is a `long`. Both sides
  agree on epoch seconds today. If either side switches to epoch
  milliseconds, this table MUST be updated and both sides re-verified —
  silent 1000x scaling bugs would otherwise result.
- **`memoryType` enum vs string.** Python uses the `MemoryType` enum; Java
  declares `String memoryType`. The wire carries the enum's string value
  (`"episodic"` / `"semantic"` / `"procedural"`). The Java side does no
  validation — see drift risk #3 below.

## Future work (D-094 and beyond)

The hand-mirroring risk above is the canonical motivation for moving to a
generated contract. Tracked options:

| Option | Trade-off |
|--------|-----------|
| **Protobuf + codegen (recommended)** | Single `.proto` generates both Java records and Python dataclasses; drift becomes a compile error. Cost: introducing grpc / protobuf toolchain, migrating the REST surface to grpc-gateway or keeping dual transport. |
| **OpenAPI spec as source of truth** | Reuses the D-094 OpenAPI pipeline (`scripts/generate-openapi-specs.sh`); generate Java + Python models from one YAML. Cost: OpenAPI/JSON-Schema → Java record codegen is weaker than protobuf; loses the Dubbo RPC framing story. |
| **Keep hand-mirroring + add a contract test** | Cheapest: add a cross-language fixture test that round-trips each payload through both sides and asserts field equality. Does not prevent drift at authoring time, only catches it at test time. Lowest effort stopgap. |

The current contract is option 3 (hand-mirrored) with this document as the
only synchronization mechanism. Migration to option 1 or 2 is deferred
future work.

### Protobuf codegen plan (D-160)

The **recommended path is protobuf codegen (option 1)**, tracked as the
long-term resolution of the drift documented in the field mapping table
above. The planned end state:

1. Author a single `ep_memory.proto` containing the `Memory`, `MemoryAddRequest`,
   `MemorySearchRequest`, `ContextWindow`, and the error envelope messages,
   plus the five RPC method definitions.
2. Generate Java records/messages with `protoc` + `protobuf-java` and
   Python dataclasses with `protoc` + the Python plugin, so both sides
   share one compiled source of truth. The camelCase ↔ snake_case
   conversion and the `_memory_to_camel` / `_context_to_camel` helpers
   disappear entirely — the generated code produces the wire shape.
3. The store-internal `last_accessed_at` / `access_count` fields stay
   Python-only by being declared **outside** the proto `Memory` message
   (e.g. a separate `MemoryInternal` message or a side-table in the store),
   so they never appear on the wire and the field-set drift becomes a
   non-issue.
4. Migrate the REST surface to grpc-gateway (or keep dual transport behind
   the same dispatcher), preserving the existing `/v1/memory/*` URLs for
   callers that cannot move to gRPC yet.

Until this lands, the field mapping table above + the change checklist below
are the only safeguards against drift. Any PR that touches a `Memory` field
MUST update the table and run the existing `services/ai-platform/tests/test_ep_memory.py`
round-trip fixtures.
