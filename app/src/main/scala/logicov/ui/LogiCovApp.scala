package logicov.ui

import everyui.app.screen.ScreenManager

object LogiCovApp extends ScreenManager {
  lazy val home: HomeScreen = new HomeScreen
}

@main def main(): Unit = LogiCovApp.run(LogiCovApp.home)
