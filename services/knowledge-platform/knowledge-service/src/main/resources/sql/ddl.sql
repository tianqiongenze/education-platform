-- sp07-knowledge-service DDL (PostgreSQL, database: knowledge)
-- D-072: Versioned DDL files, DBA-reviewed, NOT auto-executed
CREATE TABLE IF NOT EXISTS kb_document (
  id            VARCHAR(32)  PRIMARY KEY,
  tenant_id     VARCHAR(32)  NOT NULL,
  title         VARCHAR(512) NOT NULL,
  format        VARCHAR(32),
  content_path  VARCHAR(1024),
  chunk_count   INTEGER      DEFAULT 0,
  status        VARCHAR(32)  DEFAULT 'PENDING',
  version       INTEGER      DEFAULT 1,
  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kb_document_tenant ON kb_document(tenant_id);

CREATE TABLE IF NOT EXISTS kb_category (
  id          VARCHAR(32)  PRIMARY KEY,
  tenant_id   VARCHAR(32)  NOT NULL,
  name        VARCHAR(256) NOT NULL,
  parent_id   VARCHAR(32)
);
CREATE INDEX IF NOT EXISTS idx_kb_category_tenant ON kb_category(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_category_parent ON kb_category(parent_id);
