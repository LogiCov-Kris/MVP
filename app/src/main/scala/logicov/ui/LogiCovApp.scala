package logicov.ui

import everyui.app.screen.ScreenManager

object LogiCovApp extends ScreenManager {
  lazy val home: HomeScreen = new HomeScreen

  def main(args: Array[String]): Unit = {
    WorkspaceStyle.initialize()
    run(home)
  }
}
