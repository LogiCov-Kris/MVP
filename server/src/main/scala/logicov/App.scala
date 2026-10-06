package logicov

import logicov.config.{Config, LogiCovConfig}
import logicov.db.DB
import logicov.server.Server
import profig.*
import rapid.{RapidApp, Task}
import scribe.Level
import scribe.file.*

object App extends RapidApp {
  lazy val config: Config = Profig("logicov").as[Config]

  override def run(args: List[String]): Task[Unit] = for {
    _ <- start(args)
    _ <- Server.whileRunning()
    _ <- Server.dispose()
    _ <- DB.dispose
  } yield ()

  def start(args: List[String]): Task[Unit] = for {
    _ <- Task(LogiCovConfig.load())
    _ <- Task(Profig.merge(args))
    _ <- Task(initLogging())
    _ <- DB.init
    _ <- Server.start()
  } yield ()

  private def initLogging(): Unit = scribe.Logger.root
    .withHandler(
      writer = FileWriter("logs" / ("logicov" % rolling("-" % year % "-" % month % "-" % day) % ".log")),
      minimumLevel = Some(Level.Info)
    )
    .replace()
}
