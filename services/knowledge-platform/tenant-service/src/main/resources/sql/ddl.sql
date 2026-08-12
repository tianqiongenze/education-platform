-- sp07-tenant-service DDL (PostgreSQL, database: tenant)
-- D-072: Versioned DDL files, DBA-reviewed, NOT auto-executed (no Flyway to protect shared PG cluster)
CREATE TABLE IF NOT EXISTS tenant (
  id            VARCHAR(32)  PRIMARY KEY,
  name          VARCHAR(128) NOT NULL,
  plan          VARCHAR(32)  NOT NULL DEFAULT 'FREE',
  quota         BIGINT,
  brand_config  TEXT,
  domain        VARCHAR(255),
  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tenant_api_key (
  id          VARCHAR(32)  PRIMARY KEY,
  tenant_id   VARCHAR(32)  NOT NULL,
  api_key     VARCHAR(128) NOT NULL,
  secret      VARCHAR(256) NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | REVOKED
  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_api_key_key ON tenant_api_key(api_key);
