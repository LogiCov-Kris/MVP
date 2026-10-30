package logicov.ui

import everyui.component.{Column, Component, Container, Select, TextInput, TextView}
import everyui.layout.VerticalLayout
import everyui.theme.Theme
import logicov.model.{SessionView, UserAction, UserRequest, UserRole, WorkspaceUser, ManagedWorkspace}
import org.scalajs.dom
import reactify.*

import scala.scalajs.js

class UserAdminPanel(width: () => Double, actor: SessionView, workspace: Option[ManagedWorkspace] = None) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val workspaceId = workspace.fold("")(_.id)
  private val workspaceName = workspace.fold(actor.tenantName)(_.name)
  private val inner = () => width() - 48.0
  private val busy = Var(false)
  private var dirty = false
  private var users = List.empty[WorkspaceUser]
  private var selected = Option.empty[String]
  private val message = Var("")
  private val failed = Var(false)
  private val status = TextView.bound(message(), 14.0, Theme.text())
  status.size.width := width()
  status.color := (if (failed()) Theme.danger() else WorkspaceStyle.Teal)
  status.node.el.setAttribute("role", "status")
  private val body = Column(18.0)()
  private val search = new TextInput("Search users by email")
  search.size.width := width()
  search.value.attach(_ => if (canLeave) { selected = None; render() })
  def processing: Boolean = busy()
  def canLeave: Boolean = !busy() && (!dirty || dom.window.confirm("Discard unsaved account changes?"))

  private def button(label: String, primary: Boolean = false)(run: => Unit): Component = {
    val b = WorkspaceStyle.button(label, () => run, primary)
    b.enabled := !busy()
    b
  }
  private def date(value: Option[Long]): String = value.fold("Not recorded")(v => new js.Date(v.toDouble).toLocaleString())
  private def load(): Unit = {
    busy @= true
    def loaded(result: Either[String, List[WorkspaceUser]]): Unit = {
      busy @= false
      result match {
        case Right(value) => users = value; dirty = false; render()
        case Left(error) => failed @= true; message @= error
      }
    }
    if (workspaceId.isEmpty) ApiClient.users(loaded) else ApiClient.workspaceUsers(workspaceId)(loaded)
  }
  private def change(request: UserRequest): Unit = {
    busy @= true
    message @= ""
    ApiClient.manageUser(request, workspaceId) { result =>
      busy @= false
      result match {
        case Right(value) =>
          users = value; selected = Some(request.email.trim.toLowerCase); dirty = false; failed @= false
          message @= (if (request.action == UserAction.Invite) "Invitation accepted by the email provider. Inbox delivery is not yet confirmed." else "Account changes saved.")
          render()
        case Left(error) => failed @= true; message @= error
      }
    }
  }
  private def roleInput(role: UserRole): Select = {
    val field = new Select(List("Analyst", "Administrator"))
    field.selected @= (if (role == UserRole.Admin) 1 else 0)
    field.size.width := inner()
    field.size.height @= 46.0
    field.node.el.querySelector("select").setAttribute("aria-label", "Account role")
    field.selected.attach(_ => dirty = true)
    busy.attach(value => field.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = value)
    field
  }
  private def chosenRole(field: Select): UserRole = if (field.selected() == 1) UserRole.Admin else UserRole.Analyst
  private def createForm(): Component = {
    val email = new TextInput("Email")
    email.size.width := inner()
    email.value.attach(_ => dirty = true)
    email.readOnly := busy()
    val role = roleInput(UserRole.Analyst)
    WorkspaceStyle.card(width, WorkspaceStyle.text("Create a user", 20.0, bold = true),
      WorkspaceStyle.paragraph("Create access in this workspace, then send an invitation. Analysts review deals; administrators also manage users and institution policy. No password is shared.", inner),
      email, WorkspaceStyle.text("Role", 13.0, muted = true), role,
      button("Create user", primary = true) { change(UserRequest(UserAction.Create, email.value(), chosenRole(role))) })
  }
  private def details(user: WorkspaceUser): Component = {
    val role = roleInput(user.role)
    val access = new Select(List("Active", "Suspended"))
    access.selected @= (if (user.active) 0 else 1)
    access.size.width := inner()
    access.size.height @= 46.0
    access.node.el.querySelector("select").setAttribute("aria-label", "Account access")
    access.selected.attach(_ => dirty = true)
    busy.attach(value => access.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = value)
    val history = user.history.reverse.map { event =>
      WorkspaceStyle.paragraph(s"${date(Some(event.at))} · ${event.actor}\n${event.detail}", inner, 13.0)
    }
    WorkspaceStyle.card(width, (Vector(
      WorkspaceStyle.paragraph(user.email, inner, 20.0, muted = false),
      WorkspaceStyle.paragraph(s"Last successful sign-in: ${date(user.lastSignIn)}\nUnexpired sessions: ${user.activeSessions}\nLast invitation attempt: ${date(user.invitationAt)}", inner),
      WorkspaceStyle.text("Role", 13.0, muted = true), role,
      WorkspaceStyle.text("Access", 13.0, muted = true), access,
      button("Save access", primary = true) {
        if (dom.window.confirm(s"Update access for ${user.email}? Existing sessions and sign-in links will be revoked.")) {
          change(UserRequest(UserAction.Update, user.email, chosenRole(role), access.selected() == 0, user.revision))
        }
      },
      button("Send invitation") {
        if (canLeave && dom.window.confirm(s"Send an invitation email to ${user.email}?")) change(UserRequest(UserAction.Invite, user.email, revision = user.revision))
      },
      button("Revoke sessions") {
        if (canLeave && dom.window.confirm(s"Sign ${user.email} out of all sessions and revoke outstanding sign-in links?")) change(UserRequest(UserAction.RevokeSessions, user.email, revision = user.revision))
      },
      WorkspaceStyle.text("Access history", 16.0, bold = true),
      WorkspaceStyle.paragraph(if (history.isEmpty) "No account-management events recorded yet." else "Administrative changes and invitation attempts are retained here.", inner)
    ) ++ history)* )
  }
  private def render(): Unit = {
    dirty = false
    val found = users.filter(_.email.contains(search.value().trim.toLowerCase))
    val rows = found.map { user =>
      WorkspaceStyle.card(width, WorkspaceStyle.paragraph(user.email, inner, 17.0, muted = false),
        WorkspaceStyle.paragraph(s"${if (user.platformAdmin) "Platform administrator" else if (user.role == UserRole.Admin) "Administrator" else "Analyst"} · ${if (user.active) "Active" else "Suspended"} · ${user.activeSessions} unexpired sessions", inner, 13.0),
        button("Manage user") { if (canLeave) { selected = Some(user.email); render() } })
    }
    body.children @= (selected.flatMap(email => users.find(_.email == email)) match {
      case Some(user) => Vector(button("Back to users") { if (canLeave) { selected = None; render() } }, details(user))
      case None => Vector(
        WorkspaceStyle.paragraph(s"${users.size} users · ${users.count(_.active)} active · ${users.count(u => u.active && u.role == UserRole.Admin)} administrators", width),
        createForm(), WorkspaceStyle.text("Workspace users", 20.0, bold = true), search,
        WorkspaceStyle.paragraph(s"Showing ${found.size} users. Session counts indicate valid, unexpired sessions, not live online presence.", width)) ++ rows
    })
  }
  children @= Vector(WorkspaceStyle.text("User administration", 28.0, bold = true),
    WorkspaceStyle.paragraph(s"Manage access to ${workspaceName}. Users in other workspaces are not visible here.", width),
    button("Refresh users") { if (canLeave) load() }, status, body)
  load()
}
