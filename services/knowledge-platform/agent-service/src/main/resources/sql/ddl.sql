-- sp07-agent-service DDL (PostgreSQL, database: knowledge — shared with knowledge-service)
-- D-072: Versioned DDL files, DBA-reviewed, NOT auto-executed
CREATE TABLE IF NOT EXISTS agent_session (
  id          VARCHAR(64)  PRIMARY KEY,
  agent_id    VARCHAR(64)  NOT NULL,
  tenant_id   VARCHAR(32)  NOT NULL,
  user_id     VARCHAR(64),
  context     JSONB,
  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_session_tenant ON agent_session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_session_agent ON agent_session(agent_id);
