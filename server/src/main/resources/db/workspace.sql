SELECT pg_advisory_xact_lock(1845249091);
CREATE TABLE IF NOT EXISTS workspace_keys (
  tenant_id text PRIMARY KEY,
  wrapped_key bytea NOT NULL
);
CREATE TABLE IF NOT EXISTS workspace_deals (
  tenant_id text NOT NULL,
  id text NOT NULL,
  revision bigint NOT NULL,
  payload bytea NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, id)
);
CREATE TABLE IF NOT EXISTS workspace_files (
  tenant_id text NOT NULL,
  deal_id text NOT NULL,
  id text NOT NULL,
  payload bytea NOT NULL,
  PRIMARY KEY (tenant_id, deal_id, id),
  FOREIGN KEY (tenant_id, deal_id) REFERENCES workspace_deals(tenant_id, id)
);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'logicov_workspace') THEN
    CREATE ROLE logicov_workspace NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;
GRANT logicov_workspace TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO logicov_workspace;
GRANT SELECT, INSERT, UPDATE ON workspace_keys, workspace_deals, workspace_files TO logicov_workspace;
ALTER TABLE workspace_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_keys FORCE ROW LEVEL SECURITY;
ALTER TABLE workspace_deals ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_deals FORCE ROW LEVEL SECURITY;
ALTER TABLE workspace_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_files FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_scope ON workspace_keys;
CREATE POLICY tenant_scope ON workspace_keys TO logicov_workspace
  USING (tenant_id = current_setting('logicov.tenant', true))
  WITH CHECK (tenant_id = current_setting('logicov.tenant', true));
DROP POLICY IF EXISTS tenant_scope ON workspace_deals;
CREATE POLICY tenant_scope ON workspace_deals TO logicov_workspace
  USING (tenant_id = current_setting('logicov.tenant', true))
  WITH CHECK (tenant_id = current_setting('logicov.tenant', true));
DROP POLICY IF EXISTS tenant_scope ON workspace_files;
CREATE POLICY tenant_scope ON workspace_files TO logicov_workspace
  USING (tenant_id = current_setting('logicov.tenant', true))
  WITH CHECK (tenant_id = current_setting('logicov.tenant', true));

CREATE TABLE IF NOT EXISTS workspace_schema (
  id integer PRIMARY KEY CHECK (id = 1),
  version integer NOT NULL
);
INSERT INTO workspace_schema(id, version) VALUES (1, 1) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
