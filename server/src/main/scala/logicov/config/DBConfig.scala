package logicov.config

import fabric.rw.*

case class DBConfig(url: String,
                    user: String,
                    password: String,
                    maximumPoolSize: Int = 16) derives RW
