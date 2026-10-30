package logicov.ui

import everyui.component.{Column, Component, Container, Select, TextInput, TextView}
import everyui.layout.VerticalLayout
import everyui.theme.Theme
import logicov.model.{ManagedWorkspace, SessionView, WorkspaceAction, WorkspaceRequest}
import org.scalajs.dom
import reactify.*

import scala.scalajs.js

class WorkspaceAdminPanel(width: () => Double, actor: SessionView) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private val busy = Var(false)
  private val failed = Var(false)
  private val message = Var("")
  private var dirty = false
  private var workspaces = List.empty[ManagedWorkspace]
  private var selected = Option.empty[String]
  private var members = Option.empty[UserAdminPanel]
  private val body = Column(18.0)()
  private val status = TextView.bound(message(), 14.0, Theme.text())
  status.size.width := width()
  status.color := (if (failed()) Theme.danger() else WorkspaceStyle.Teal)
  status.node.el.setAttribute("role", "status")
  private val search = new TextInput("Search workspaces")
  search.size.width := width()
  search.value.attach(_ => if (canLeave) render())
  def processing: Boolean = busy() || members.exists(_.processing)
  def canLeave: Boolean = !processing && members.forall(_.canLeave) && (!dirty || dom.window.confirm("Discard unsaved workspace changes?"))

  private def button(label: String, primary: Boolean = false)(run: => Unit): Component = {
    val b = WorkspaceStyle.button(label, () => run, primary)
    b.enabled := !busy()
    b
  }
  private def field(label: String, value: String = ""): TextInput = {
    val input = new TextInput(label)
    input.value @= value
    input.size.width := inner()
    input.readOnly := busy()
    input.value.attach(_ => dirty = true)
    input
  }
  private def date(value: Option[Long]): String = value.fold("Not recorded")(v => new js.Date(v.toDouble).toLocaleString())
  private def load(): Unit = {
    busy @= true
    ApiClient.workspaces { result =>
      busy @= false
      result match {
        case Right(value) => workspaces = value; dirty = false; members = None; render()
        case Left(error) => failed @= true; message @= error
      }
    }
  }
  private def save(request: WorkspaceRequest): Unit = {
    busy @= true
    message @= ""
    ApiClient.manageWorkspace(request) { result =>
      busy @= false
      result match {
        case Right(value) =>
          workspaces = value
          selected = value.find(w => if (request.action == WorkspaceAction.Create) w.name == request.name.trim else w.id == request.id).map(_.id)
          dirty = false; failed @= false
          message @= (if (request.action == WorkspaceAction.Create) "Workspace created. Open its users to invite the first administrator." else "Workspace changes saved.")
          render()
        case Left(error) => failed @= true; message @= error
      }
    }
  }
  private def createForm(): Component = {
    val name = field("Workspace name")
    val email = field("First administrator email")
    WorkspaceStyle.card(width, WorkspaceStyle.text("Create a workspace", 20.0, bold = true),
      WorkspaceStyle.paragraph("Each customer workspace has its own users and isolated deal data. Use an administrator email that is not already assigned to another workspace. Creation does not send an email.", inner),
      name, email, button("Create workspace", primary = true) { save(WorkspaceRequest(WorkspaceAction.Create, name.value(), administratorEmail = email.value())) })
  }
  private def details(workspace: ManagedWorkspace): Component = {
    val name = field("Workspace name", workspace.name)
    val access = new Select(List("Active", "Suspended"))
    access.selected @= (if (workspace.active) 0 else 1)
    access.size.width := inner()
    access.size.height @= 46.0
    access.node.el.querySelector("select").setAttribute("aria-label", "Workspace access")
    access.selected.attach(_ => dirty = true)
    busy.attach(value => access.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = value)
    val history = workspace.history.reverse.map(event => WorkspaceStyle.paragraph(s"${date(Some(event.at))} · ${event.actor}\n${event.detail}", inner, 13.0))
    WorkspaceStyle.card(width, (Vector(
      WorkspaceStyle.paragraph(workspace.name, inner, 22.0, muted = false),
      WorkspaceStyle.paragraph(s"${WorkspaceStyle.quantity(workspace.users, "user")} · ${WorkspaceStyle.quantity(workspace.activeUsers, "active account")} · ${WorkspaceStyle.quantity(workspace.administrators, "administrator")}\nLast successful user sign-in: ${date(workspace.lastSignIn)}", inner),
      WorkspaceStyle.paragraph("Suspension blocks every user in this workspace and revokes existing sessions and sign-in links. Records are retained. Reactivation requires users to sign in again.", inner),
      name, WorkspaceStyle.text("Workspace access", 13.0, muted = true), access,
      button("Save workspace", primary = true) {
        if (dom.window.confirm(s"Update ${workspace.name}?${if (access.selected() == 1) " All workspace users will lose access until reactivation." else ""}")) {
          save(WorkspaceRequest(WorkspaceAction.Update, name.value(), workspace.id, active = access.selected() == 0, revision = workspace.revision))
        }
      },
      button("Manage workspace users") {
        if (canLeave) {
          dirty = false
          val panel = new UserAdminPanel(width, actor, Some(workspace))
          members = Some(panel)
          body.children @= Vector(button("Back to workspace") { if (canLeave) { members = None; load() } }, panel)
        }
      },
      WorkspaceStyle.text("Workspace history", 17.0, bold = true)
    ) ++ history)* )
  }
  private def render(): Unit = {
    dirty = false
    body.children @= (selected.flatMap(id => workspaces.find(_.id == id)) match {
      case Some(workspace) => Vector(button("Back to workspaces") { if (canLeave) { selected = None; render() } }, details(workspace))
      case None =>
        val found = workspaces.filter(_.name.toLowerCase.contains(search.value().trim.toLowerCase))
        Vector(WorkspaceStyle.paragraph(s"${workspaces.size} workspaces · ${workspaces.count(_.active)} active", width),
          createForm(), WorkspaceStyle.text("Customer workspaces", 20.0, bold = true), search,
          WorkspaceStyle.paragraph(s"Showing ${found.size} workspaces", width)) ++ found.map { workspace =>
          WorkspaceStyle.card(width, WorkspaceStyle.paragraph(workspace.name, inner, 18.0, muted = false),
            WorkspaceStyle.paragraph(s"${if (workspace.active) "Active" else "Suspended"} · ${WorkspaceStyle.quantity(workspace.users, "user")} · ${WorkspaceStyle.quantity(workspace.administrators, "administrator")}", inner),
            button("Manage workspace") { if (canLeave) { selected = Some(workspace.id); render() } })
        }
    })
  }
  children @= Vector(WorkspaceStyle.text("Workspace administration", 26.0, bold = true),
    WorkspaceStyle.paragraph("Platform administration for customer workspaces and their users. Customer deal records remain within their own workspace.", width),
    button("Refresh workspaces") { if (canLeave) load() }, status, body)
  load()
}
