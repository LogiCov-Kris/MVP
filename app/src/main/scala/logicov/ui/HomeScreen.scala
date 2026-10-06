package logicov.ui

import everyui.app.screen.{ScreenManager, UIScreen}
import everyui.async.Task
import everyui.component.{Component, TextView}
import everyui.net.URLPath
import everyui.theme.Theme
import reactify.*

class HomeScreen extends UIScreen {
  override def manager: ScreenManager = LogiCovApp
  override def path: URLPath = URLPath("/")

  private lazy val heading = new TextView {
    content @= "LogiCov"
    font.size @= 32.0
    color := Theme.text()
  }

  override def defaultFocus(): Component = heading
  override def createUI(): Task[Unit] = Task(container.children += heading)
}
