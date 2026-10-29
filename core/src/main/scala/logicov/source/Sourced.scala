package logicov.source

import fabric.rw.*

case class Sourced[A](value: A, source: Source) derives RW
