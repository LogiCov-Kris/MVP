package logicov.workspace

import logicov.db.ManagedConnections

import java.nio.charset.StandardCharsets
import java.sql.Connection

class WorkspaceDatabase(connections: ManagedConnections) {
  def initialize(): Unit = connections.jdbc { connection =>
    val statement = connection.createStatement()
    try {
      statement.execute("SELECT pg_advisory_xact_lock(1845249091)")
      val present = statement.executeQuery("SELECT to_regclass('public.workspace_schema') IS NOT NULL")
      val exists = try { present.next(); present.getBoolean(1) } finally present.close()
      val version = if (exists) {
        val rows = statement.executeQuery("SELECT version FROM workspace_schema WHERE id = 1")
        try { if (rows.next()) rows.getInt(1) else 0 } finally rows.close()
      } else 0
      require(version <= 1, "Workspace schema is newer than this application")
      if (version == 0) {
        val stream = getClass.getClassLoader.getResourceAsStream("db/workspace.sql")
        val sql = try new String(stream.readAllBytes(), StandardCharsets.UTF_8) finally stream.close()
        statement.execute(sql)
      }
    } finally statement.close()
  }

  def verify(): Unit = connections.jdbc { connection =>
    val statement = connection.createStatement()
    try {
      val schema = statement.executeQuery("SELECT version FROM workspace_schema WHERE id = 1")
      try { require(schema.next() && schema.getInt(1) == 1, "Workspace migration version 1 is required") }
      finally schema.close()
      statement.execute("SET LOCAL ROLE logicov_workspace")
      val role = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
      try { require(role.next() && !role.getBoolean(1), "Workspace role must not bypass row-level security") }
      finally role.close()
      val result = statement.executeQuery("SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname IN ('workspace_keys','workspace_deals','workspace_files') AND relrowsecurity AND relforcerowsecurity")
      try { require(result.next() && result.getInt(1) == 3, "Workspace row-level security migration is required") }
      finally result.close()
    } finally statement.close()
  }

  def tenant[A](tenant: String)(work: Connection => A): A = {
    require(tenant.nonEmpty && tenant.length <= 128, "Invalid tenant identity")
    connections.jdbc { connection =>
      val role = connection.createStatement()
      try {
        role.execute("SELECT pg_advisory_xact_lock_shared(1845249091)")
        role.execute("SET LOCAL ROLE logicov_workspace")
      } finally role.close()
      val scope = connection.prepareStatement("SELECT set_config('logicov.tenant', ?, true)")
      try { scope.setString(1, tenant); scope.execute() } finally scope.close()
      work(connection)
    }
  }
}
