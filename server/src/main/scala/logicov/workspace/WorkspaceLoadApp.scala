package logicov.workspace

import lightdb.id.Id
import logicov.auth.{AuthService, SessionTokens, User}
import logicov.config.LogiCovConfig
import logicov.db.DB
import logicov.deal.DealBuilder
import logicov.edgar.SourcePack
import logicov.model.SessionView

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object WorkspaceLoadApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: WorkspaceLoadApp <existing analyst email> <source pack directory>")
    val root = Path.of(args(1)).toRealPath()
    val record = DealBuilder.build(new SourcePack(root))
    LogiCovConfig.load()
    DB.init.sync()
    try {
      WorkspaceService.initialize()
      val user = DB.users.transaction(_.get(Id[User](SessionTokens.digest(AuthService.email(args(0)))))).sync()
        .filter(_.active).getOrElse(throw new IllegalArgumentException("Active analyst account required"))
      val tenant = DB.tenants.transaction(_.get(user.tenantId)).sync().filter(_.active)
        .getOrElse(throw new IllegalArgumentException("Active tenant required"))
      val session = SessionView(user.email, tenant._id.value, tenant.name)
      val walk = Files.walk(root)
      val retained = try walk.iterator().asScala.filter(Files.isRegularFile(_)).toList.sorted.map { path =>
        require(path.toRealPath().startsWith(root), "Source pack must not link to files outside its directory")
        require(Files.size(path) <= 50 * 1024 * 1024, "Source exceeds 50 MB")
        val bytes = Files.readAllBytes(path)
        val name = root.relativize(path).toString
        WorkspaceService.workflow.document(name, "application/octet-stream", bytes) -> bytes
      } finally walk.close()
      require(retained.map(_._2.length.toLong).sum <= 200 * 1024 * 1024, "Source pack exceeds 200 MB")
      val deal = WorkspaceService.workflow.create(session, CreateDealRequest(record.borrower, record = Some(record)), retained)
      println(s"Saved ${deal.title}; deal ${deal.id}; ${retained.size} retained source files; awaiting analyst approval")
    } finally DB.dispose.sync()
  }
}
