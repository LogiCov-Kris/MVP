package logicov.auth

import logicov.config.LogiCovConfig
import logicov.db.DB

import scala.io.StdIn

object ProvisionApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: ProvisionApp <tenant name or --tenant-id=ID> <email>; password supplied on stdin")
    LogiCovConfig.load()
    val password = Option(System.console()).map(c => new String(c.readPassword("Password: ")))
      .getOrElse(Option(StdIn.readLine()).getOrElse(throw new IllegalArgumentException("Password required on stdin")))
    DB.init.sync()
    try {
      val user = (if (args(0).startsWith("--tenant-id=")) AuthService.addUser(args(0).stripPrefix("--tenant-id="), args(1), password)
        else AuthService.provision(args(0), args(1), password)).sync()
      println(s"Provisioned ${user.email}; tenant ${user.tenantId.value}")
    } finally DB.dispose.sync()
  }
}
