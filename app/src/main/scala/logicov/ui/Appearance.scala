package logicov.ui

import everyui.component.Select
import org.scalajs.dom
import reactify.*

import scala.util.Try

object Appearance {
  private val key = "logicov.appearance"
  private lazy val device = dom.window.matchMedia("(prefers-color-scheme: dark)")
  val preference: Var[ThemePreference] = Var(ThemePreference.System)
  val dark: Var[Boolean] = Var(false)

  def initialize(): Unit = {
    preference @= stored()
    update()
    device.addEventListener("change", (_: dom.Event) => update())
    dom.window.addEventListener("storage", (event: dom.StorageEvent) => if (event.key == key || event.key == null) {
      preference @= stored()
      update()
    })
  }

  private def stored(): ThemePreference = {
    val value = Try(dom.window.localStorage.getItem(key)).toOption
    ThemePreference.values.find(p => value.contains(p.toString)).getOrElse(ThemePreference.System)
  }

  private def update(): Unit = {
    dark @= (preference() == ThemePreference.Dark || (preference() == ThemePreference.System && device.matches))
    dom.document.documentElement.setAttribute("data-theme", if (dark()) "dark" else "light")
    dom.document.documentElement.setAttribute("style", s"color-scheme: ${if (dark()) "dark" else "light"}")
    WorkspaceStyle.applyPalette(dark())
  }

  def selector(): Select = {
    val choices = ThemePreference.values.toList
    val select = new Select(choices.map(_.label))
    select.size.width @= 176.0
    select.size.height @= 38.0
    val element = select.node.el.querySelector("select").asInstanceOf[dom.html.Select]
    element.setAttribute("aria-label", "Appearance")
    select.selected @= choices.indexOf(preference())
    preference.attach(p => select.selected @= choices.indexOf(p))
    select.selected.attach { index =>
      preference @= choices.lift(index).getOrElse(ThemePreference.System)
      Try(dom.window.localStorage.setItem(key, preference().toString)).failed.foreach { _ =>
        dom.console.warn("Appearance preference could not be saved on this device.")
      }
      update()
    }
    select
  }
}
