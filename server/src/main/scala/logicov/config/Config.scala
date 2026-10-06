package logicov.config

import fabric.rw.*

case class Config(host: String = "0.0.0.0",
                  port: Int = 8300,
                  db: DBConfig) derives RW
