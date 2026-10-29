package logicov.workspace

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*

import java.nio.charset.StandardCharsets
import java.sql.Connection
import scala.collection.mutable.ListBuffer

class WorkspaceStore(val database: WorkspaceDatabase, cipher: TenantCipher) {
  private def key(connection: Connection, tenant: String): Array[Byte] = {
    val insert = connection.prepareStatement("INSERT INTO workspace_keys(tenant_id, wrapped_key) VALUES (?, ?) ON CONFLICT DO NOTHING")
    try {
      insert.setString(1, tenant)
      insert.setBytes(2, cipher.wrap(tenant, cipher.newKey()))
      insert.executeUpdate()
    } finally insert.close()
    val query = connection.prepareStatement("SELECT wrapped_key FROM workspace_keys WHERE tenant_id = ?")
    try {
      query.setString(1, tenant)
      val rows = query.executeQuery()
      try {
        require(rows.next(), "Tenant encryption key unavailable")
        cipher.unwrap(tenant, rows.getBytes(1))
      } finally rows.close()
    } finally query.close()
  }

  private def decode(connection: Connection, tenant: String, id: String, bytes: Array[Byte]): WorkspaceDeal =
    JsonParser(new String(cipher.decrypt(key(connection, tenant), s"deal:$tenant:$id", bytes), StandardCharsets.UTF_8)).as[WorkspaceDeal]

  private def encode(connection: Connection, tenant: String, deal: WorkspaceDeal): Array[Byte] =
    cipher.encrypt(key(connection, tenant), s"deal:$tenant:${deal.id}", JsonFormatter.Compact(deal.json).getBytes(StandardCharsets.UTF_8))

  private def find(connection: Connection, tenant: String, id: String, lock: Boolean): WorkspaceDeal = {
    val query = connection.prepareStatement("SELECT payload FROM workspace_deals WHERE tenant_id = ? AND id = ?" + (if (lock) " FOR UPDATE" else ""))
    try {
      query.setString(1, tenant)
      query.setString(2, id)
      val rows = query.executeQuery()
      try {
        if (!rows.next()) throw new NoSuchElementException("Deal not found")
        decode(connection, tenant, id, rows.getBytes(1))
      } finally rows.close()
    } finally query.close()
  }

  def get(tenant: String, id: String): WorkspaceDeal = database.tenant(tenant)(find(_, tenant, id, false))

  def list(tenant: String): List[DealSummary] = database.tenant(tenant) { connection =>
    val query = connection.prepareStatement("SELECT id, payload FROM workspace_deals WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT 200")
    try {
      query.setString(1, tenant)
      val rows = query.executeQuery()
      val found = ListBuffer.empty[DealSummary]
      try {
        while (rows.next()) {
          val deal = decode(connection, tenant, rows.getString(1), rows.getBytes(2))
          found += DealSummary(deal.id, deal.title, deal.stage, deal.revision, deal.grid.values.size,
            deal.issues.indices.count(i => !deal.dispositions.contains(i.toString)), deal.events.lastOption.fold("")(_.at))
        }
      } finally rows.close()
      found.toList
    } finally query.close()
  }

  def create(tenant: String, deal: WorkspaceDeal, files: List[(String, Array[Byte])]): WorkspaceDeal = database.tenant(tenant) { connection =>
    val insert = connection.prepareStatement("INSERT INTO workspace_deals(tenant_id,id,revision,payload) VALUES (?,?,?,?)")
    try {
      insert.setString(1, tenant); insert.setString(2, deal.id); insert.setLong(3, deal.revision)
      insert.setBytes(4, encode(connection, tenant, deal)); insert.executeUpdate()
    } finally insert.close()
    files.foreach((id, bytes) => putFile(connection, tenant, deal.id, id, bytes))
    deal
  }

  def change(tenant: String, id: String, expected: Long, files: List[(String, Array[Byte])] = Nil)
            (change: WorkspaceDeal => WorkspaceDeal): WorkspaceDeal = database.tenant(tenant) { connection =>
    val before = find(connection, tenant, id, true)
    if (before.revision != expected) throw new WorkspaceConflict("This deal changed. Reload it before saving.")
    val after = change(before).copy(revision = before.revision + 1)
    require(after.id == before.id, "Deal identity cannot change")
    val update = connection.prepareStatement("UPDATE workspace_deals SET revision=?, payload=?, updated_at=now() WHERE tenant_id=? AND id=? AND revision=?")
    try {
      update.setLong(1, after.revision); update.setBytes(2, encode(connection, tenant, after))
      update.setString(3, tenant); update.setString(4, id); update.setLong(5, expected)
      require(update.executeUpdate() == 1, "Deal revision update failed")
    } finally update.close()
    files.foreach((fileId, bytes) => putFile(connection, tenant, id, fileId, bytes))
    after
  }

  private def putFile(connection: Connection, tenant: String, deal: String, id: String, bytes: Array[Byte]): Unit = {
    val insert = connection.prepareStatement("INSERT INTO workspace_files(tenant_id,deal_id,id,payload) VALUES (?,?,?,?)")
    try {
      insert.setString(1, tenant); insert.setString(2, deal); insert.setString(3, id)
      insert.setBytes(4, cipher.encrypt(key(connection, tenant), s"file:$tenant:$deal:$id", bytes))
      insert.executeUpdate()
    } finally insert.close()
  }

  def file(tenant: String, dealId: String, fileId: String): (WorkspaceDocument, Array[Byte]) = database.tenant(tenant) { connection =>
    val deal = find(connection, tenant, dealId, false)
    val document = deal.documents.find(_.id == fileId).getOrElse(throw new NoSuchElementException("Document not found"))
    val query = connection.prepareStatement("SELECT payload FROM workspace_files WHERE tenant_id=? AND deal_id=? AND id=?")
    try {
      query.setString(1, tenant); query.setString(2, dealId); query.setString(3, fileId)
      val rows = query.executeQuery()
      try {
        if (!rows.next()) throw new NoSuchElementException("Document not found")
        document -> cipher.decrypt(key(connection, tenant), s"file:$tenant:$dealId:$fileId", rows.getBytes(1))
      } finally rows.close()
    } finally query.close()
  }
}
