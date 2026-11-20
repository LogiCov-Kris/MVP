CREATE TABLE workspace_policy (
  tenant_id text PRIMARY KEY,
  revision bigint NOT NULL,
  payload bytea NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON workspace_policy TO logicov_workspace;
ALTER TABLE workspace_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON workspace_policy TO logicov_workspace
  USING (tenant_id = current_setting('logicov.tenant', true))
  WITH CHECK (tenant_id = current_setting('logicov.tenant', true));
UPDATE workspace_schema SET version = 2 WHERE id = 1;
