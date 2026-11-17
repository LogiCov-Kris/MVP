package logicov.edgar

object EdgarPacing {
  private var nextRequest = 0L

  def awaitTurn(): Unit = synchronized {
    val wait = nextRequest - System.nanoTime()
    if (wait > 0) Thread.sleep((wait + 999999L) / 1000000L)
    nextRequest = System.nanoTime() + 400000000L
  }
}
