package logicov

import logicov.ai.LogiCovSigil
import logicov.auth.EmailSignIn
import logicov.config.{Config, LogiCovConfig}
import logicov.db.DB
import logicov.server.Server
import logicov.workspace.WorkspaceService
import profig.*
import rapid.{RapidApp, Task}
import scribe.Level

object App extends RapidApp {
  lazy val config: Config = Config.environment(Profig("logicov").as[Config], sys.env)

  override def run(args: List[String]): Task[Unit] =
    start(args).flatMap(_ => Server.whileRunning()).guarantee(Server.dispose().guarantee(DB.dispose).guarantee(
      if (sys.env.get("LOGICOV_NARRATIVES_ENABLED").contains("true")) LogiCovSigil.shutdown else Task.unit))

  def start(args: List[String]): Task[Unit] = for {
    _ <- Task(LogiCovConfig.load())
    _ <- Task(Profig.merge(args))
    _ <- Task(initLogging())
    _ <- DB.init
    _ <- Task(WorkspaceService.initialize())
    _ <- Task(EmailSignIn.initialize())
    _ <- Server.start()
  } yield ()

  private def initLogging(): Unit = scribe.Logger.root
    .clearHandlers()
    .withHandler(minimumLevel = Some(Level.Info))
    .replace()
}
