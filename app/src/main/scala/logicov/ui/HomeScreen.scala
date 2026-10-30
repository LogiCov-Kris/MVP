package logicov.ui

import everyui.Color
import everyui.app.screen.{ScreenManager, UIScreen}
import everyui.async.Task
import everyui.component.{Column, Component, Container, Row, ScrollContainer, TextInput, TextView}
import everyui.component.types.{Autofill, Semantics, TextOverflow, WhiteSpace}
import everyui.net.URLPath
import everyui.theme.Theme
import logicov.model.{SessionView, UserRole}
import org.scalajs.dom
import reactify.*
import scala.scalajs.js

class HomeScreen extends UIScreen {
  override def manager: ScreenManager = LogiCovApp
  override def path: URLPath = URLPath("/")

  private var emailAvailable = false
  private var emailMode = true
  private var resetMode = false
  private var signInToken = ""
  private val message = Var("")
  private val notice = Var(false)
  private val busy = Var(false)
  private val clock = Var(0.0)
  private val cooldownUntil = Var(0.0)
  private val cooldownEmail = Var("")
  private val email = new TextInput("Email")
  private val password = new TextInput("Password", secure = true)
  private val newPassword = new TextInput("New password", secure = true)
  private val repeatPassword = new TextInput("Confirm new password", secure = true)
  private val status = TextView.bound(message(), 13.0, Theme.danger())
  private val root = new Container
  override def defaultFocus(): Component = email

  override def createUI(): Task[Unit] = Task {
    container.backgroundColor := Theme.background()
    root.size.width := container.size.width()
    root.size.height := container.size.height()
    container.children += root
    email.literal @= true
    email.fieldName @= "email"
    email.autofill @= Some(Autofill.Username)
    password.fieldName @= "password"
    password.autofill @= Some(Autofill.CurrentPassword)
    password.submitted.attach(_ => signIn())
    newPassword.autofill @= Some(Autofill.NewPassword)
    repeatPassword.autofill @= Some(Autofill.NewPassword)
    repeatPassword.submitted.attach(_ => resetPassword())
    status.color := (if (notice()) WorkspaceStyle.Teal else Theme.danger())
    status.whiteSpace @= WhiteSpace.Normal
    status.node.el.setAttribute("role", "status")
    clock @= js.Date.now()
    dom.window.setInterval(() => clock @= js.Date.now(), 1000)
    readSignInLink()
    dom.window.addEventListener("hashchange", (_: dom.Event) => if (readSignInLink()) loginForm(""))
    email.submitted.attach(_ => if (resetMode && signInToken.nonEmpty) resetPassword() else if (resetMode) requestEmail() else if (signInToken.nonEmpty) confirmEmail() else if (emailAvailable && emailMode) requestEmail())
    loginForm("")
    ApiClient.authOptions {
      case Right(options) =>
        emailAvailable = options.emailSignIn
        if (signInToken.nonEmpty) loginForm("") else refresh()
      case Left(error) => loginForm(error)
    }
  }

  private def readSignInLink(): Boolean = {
    val fragment = dom.window.location.hash
    if (fragment.startsWith("#signin=") || fragment.startsWith("#reset=")) {
      resetMode = fragment.startsWith("#reset=")
      signInToken = fragment.substring(fragment.indexOf("=") + 1)
      dom.window.history.replaceState(null, "", "/")
      true
    } else false
  }

  private def brand(light: Boolean = false): Container = {
    val mark = WorkspaceStyle.text("L", 23.0, bold = true)
    mark.color @= Color.White
    val tile = new Container
    tile.backgroundColor @= WorkspaceStyle.Teal
    tile.radius @= 9.0
    tile.size.width @= 36.0
    tile.size.height @= 36.0
    mark.position.center := tile.size.center()
    mark.position.middle := tile.size.middle()
    tile.children += mark
    val name = WorkspaceStyle.text("LogiCov", 23.0, bold = true)
    if (light) name.color @= Color.White
    Row(11.0, middle = true)(tile, name)
  }

  private def refresh(): Unit = ApiClient.session {
    case Right(Some(session)) => workspace(session)
    case Right(None) => loginForm("")
    case Left(error) => loginForm(error)
  }

  private def loginForm(error: String): Unit = {
    notice @= false
    message @= error
    val wide = Val(false)
    val left = new Container
    left.size.width := (if (wide()) root.size.width() * 0.43 else 0.0)
    left.size.height := root.size.height()
    left.backgroundColor @= WorkspaceStyle.Navy
    left.visible := wide()
    val logo = brand(light = true)
    logo.position.left @= 48.0
    logo.position.top @= 44.0
    val story = Column(22.0)(
      WorkspaceStyle.paragraph("Clarity at every step of credit review.", () => left.size.width() - 96.0, 38.0, muted = false),
      WorkspaceStyle.paragraph("From financial statements to a Golden Grid you can trace, review and trust.", () => left.size.width() - 96.0, 17.0),
      WorkspaceStyle.paragraph("01  Bring your source financials\n02  Review every cited value\n03  Export with confidence", () => left.size.width() - 96.0, 15.0))
    story.children().collect { case t: TextView => t }.foreach(_.color @= Color.fromHex("D6E4EA"))
    story.position.left @= 48.0
    story.position.middle := left.size.middle()
    left.children ++= Vector(logo, story)
    val scroll = new ScrollContainer
    scroll.position.left := left.size.width()
    scroll.size.width := root.size.width() - left.size.width()
    scroll.size.height := root.size.height()
    val width = () => math.min(390.0, scroll.size.width() - 104.0)
    email.size.width := width()
    password.size.width := width()
    newPassword.size.width := width()
    repeatPassword.size.width := width()
    status.size.width := width()
    val confirming = signInToken.nonEmpty
    val useEmail = emailAvailable && emailMode && !confirming
    val action = () => if (resetMode && confirming) resetPassword() else if (resetMode) requestEmail() else if (confirming) confirmEmail() else if (useEmail) requestEmail() else signIn()
    val label = if (resetMode && confirming) "Reset password" else if (resetMode) "Email me a reset link" else if (confirming) "Continue to workspace" else if (useEmail) "Email me a sign-in link" else "Sign in"
    val signInButton = WorkspaceStyle.button(label, action, primary = true)
    signInButton.minWidth := width()
    val requestingLink = !confirming && (resetMode || useEmail)
    val remaining = Val(if (cooldownEmail() == email.value().trim.toLowerCase) math.max(0, math.ceil((cooldownUntil() - clock()) / 1000).toInt) else 0)
    signInButton.enabled := !busy() && (!requestingLink || remaining() == 0)
    signInButton.label := (if (busy()) "Please wait..." else if (requestingLink && remaining() > 0) s"Resend in ${remaining()}s" else label)
    val fields = if (resetMode && confirming) Vector(email, newPassword, repeatPassword) else if (resetMode || confirming || useEmail) Vector(email) else Vector(email, password)
    val alternate = WorkspaceStyle.button(if (useEmail) "Use a password instead" else "Use an email sign-in link", () => {
      resetMode = false
      emailMode = !emailMode
      signInToken = ""
      password.value @= ""
      loginForm("")
    })
    alternate.enabled := !busy()
    val retry = WorkspaceStyle.button("Request a new link", () => { signInToken = ""; emailMode = true; loginForm("") })
    val forgot = WorkspaceStyle.button("Forgot password?", () => { resetMode = true; signInToken = ""; password.value @= ""; loginForm("") })
    forgot.enabled := !busy()
    val back = WorkspaceStyle.button("Back to sign in", () => { resetMode = false; signInToken = ""; newPassword.value @= ""; repeatPassword.value @= ""; loginForm("") })
    back.enabled := !busy()
    val switches = if (resetMode) Vector(back) else if (confirming) Vector(retry) else if (emailAvailable) Vector(alternate, forgot) else Vector.empty
    val heading = if (resetMode) "Reset your password" else if (confirming) "Confirm your email" else "Welcome back"
    val explanation = if (resetMode && confirming) "Confirm your email and choose a password with 14 to 256 characters. Existing sessions will be signed out."
      else if (resetMode) "We’ll email you a secure link to choose a new password."
      else if (confirming) "Enter the email address that received this link to finish signing in. If it has expired, request a new link below."
      else if (useEmail) "We’ll email you a secure sign-in link. No password needed."
      else "Sign in to your underwriting workspace."
    val form = Column(22.0, padding = 28.0)((Vector(brand(), WorkspaceStyle.text(heading, 30.0, bold = true),
      WorkspaceStyle.paragraph(explanation, width)) ++ fields ++ Vector(signInButton, status) ++ switches ++ Vector(
      WorkspaceStyle.paragraph("Access is provided by your workspace administrator.", width, 12.0)))* )
    form.backgroundColor @= Color.White
    form.radius @= 14.0
    form.borderWidth @= 1.0
    form.borderColor := Theme.border()
    form.node.el.style.boxShadow = "0 16px 60px #25245a10"
    form.position.center := scroll.size.center()
    form.position.top := math.max(48.0, (scroll.size.height() - 530.0) / 2.0)
    scroll.content.children += form
    root.children @= Vector(left, scroll)
  }

  private def requestEmail(): Unit = if (!busy() && (cooldownEmail() != email.value().trim.toLowerCase || cooldownUntil() <= js.Date.now())) {
    busy @= true
    message @= ""
    val send = if (resetMode) ApiClient.requestPasswordReset else ApiClient.requestEmail
    send(email.value()) { error =>
      busy @= false
      notice @= error.isEmpty
      if (error.isEmpty) {
        cooldownEmail @= email.value().trim.toLowerCase
        clock @= js.Date.now()
        cooldownUntil @= clock() + 60000
      }
      message @= error.getOrElse("If this email has workspace access, a " + (if (resetMode) "password-reset" else "sign-in") + " link has been requested. Delivery may take a few minutes. Check your inbox and spam folder before requesting another link. Links expire 10 minutes after being issued.")
    }
  }

  private def resetPassword(): Unit = if (!busy()) {
    if (newPassword.value().length < 14 || newPassword.value().length > 256) message @= "Passwords must contain 14 to 256 characters."
    else if (newPassword.value() != repeatPassword.value()) message @= "The passwords do not match."
    else {
      busy @= true
      message @= ""
      ApiClient.resetPassword(email.value(), signInToken, newPassword.value()) { error =>
        busy @= false
        error match {
          case Some(value) => message @= value
          case None =>
            signInToken = ""
            resetMode = false
            emailMode = false
            newPassword.value @= ""
            repeatPassword.value @= ""
            password.value @= ""
            loginForm("")
            notice @= true
            message @= "Your password has been reset. Sign in with your new password."
        }
      }
    }
  }

  private def confirmEmail(): Unit = if (!busy()) {
    busy @= true
    message @= ""
    ApiClient.confirmEmail(email.value(), signInToken) { error =>
      busy @= false
      error match {
        case Some(value) => message @= value
        case None => signInToken = ""; refresh()
      }
    }
  }

  private def signIn(): Unit = if (!busy()) {
    if (email.value().trim.isEmpty || password.value().isEmpty) message @= "Enter your email and password."
    else {
      busy @= true
      message @= ""
      ApiClient.login(email.value(), password.value()) { error =>
        busy @= false
        password.value @= ""
        error match {
          case Some(value) => message @= value
          case None => refresh()
        }
      }
    }
  }

  private def workspace(session: SessionView): Unit = {
    message @= ""
    val sidebar = new Container
    sidebar.size.width := (if (root.size.width() >= 1000.0) 224.0 else 0.0)
    sidebar.size.height := root.size.height()
    sidebar.visible := root.size.width() >= 1000.0
    sidebar.backgroundColor @= WorkspaceStyle.Navy
    sidebar.semantics @= Semantics.navigation("Workspace")
    val logo = brand(light = true)
    logo.position.left @= 24.0
    logo.position.top @= 25.0
    val label = WorkspaceStyle.text("WORKSPACE", 10.0, bold = true)
    label.color @= Color.fromHex("90A7B6")
    label.position.left @= 26.0
    label.position.top @= 115.0
    val activeLabel = WorkspaceStyle.text("Deal workspace", 14.0, bold = true)
    val active = WorkspaceStyle.card(() => 192.0, activeLabel)
    active.padding @= 14.0
    active.backgroundColor @= Color.fromHex("DCEDEA")
    active.borderWidth @= 0.0
    active.position.left @= 16.0
    active.position.top @= 140.0
    val footer = WorkspaceStyle.paragraph("Every value.\nBack to its source.", () => 170.0, 13.0)
    footer.color @= Color.fromHex("90A7B6")
    footer.position.left @= 26.0
    footer.position.top := math.max(240.0, sidebar.size.height() - 90.0)
    sidebar.children ++= Vector(logo, label, active, footer)
    val header = new Container
    header.position.left := sidebar.size.width()
    header.size.width := root.size.width() - sidebar.size.width()
    header.size.height @= 80.0
    header.backgroundColor @= Color.White
    header.semantics @= Semantics.banner
    val tenant = WorkspaceStyle.text(session.tenantName, 15.0, bold = true)
    tenant.content := (if (root.size.width() < 1000.0) s"LogiCov · ${session.tenantName}" else session.tenantName)
    tenant.whiteSpace @= WhiteSpace.NoWrap
    tenant.textOverflow @= TextOverflow.Ellipsis
    tenant.position.left @= 24.0
    tenant.position.top @= 19.0
    tenant.maxWidth := math.max(150.0, header.size.width() - 150.0)
    val identity = WorkspaceStyle.text(session.email, 11.0, muted = true)
    identity.content @= (session.email + (if (session.platformAdmin) " · Platform administrator" else if (session.role == UserRole.Admin) " · Administrator" else ""))
    identity.whiteSpace @= WhiteSpace.NoWrap
    identity.textOverflow @= TextOverflow.Ellipsis
    identity.position.left @= 24.0
    identity.position.top @= 43.0
    identity.maxWidth := math.max(150.0, header.size.width() - 150.0)
    val scroll = new ScrollContainer
    scroll.position.left := sidebar.size.width()
    scroll.position.top @= 80.0
    scroll.size.width := root.size.width() - sidebar.size.width()
    scroll.size.height := root.size.height() - 80.0
    scroll.semantics @= Semantics.main
    val width = () => math.min(1120.0, scroll.size.width() - (if (scroll.size.width() < 600.0) 32.0 else 64.0))
    status.size.width := width()
    val panel = new WorkspacePanel(width)
    var administration = Option.empty[UserAdminPanel]
    var workspaceAdministration = Option.empty[WorkspaceAdminPanel]
    val area = Var(WorkspaceArea.Deals)
    def canLeaveWorkspace: Boolean = area() match {
      case WorkspaceArea.Deals => panel.canLeave
      case WorkspaceArea.Users => administration.forall(_.canLeave)
      case WorkspaceArea.Workspaces => workspaceAdministration.forall(_.canLeave)
    }
    val page = Column(14.0)(panel)
    val workspaceButton = WorkspaceStyle.button("Deals", () => if (canLeaveWorkspace) {
      area @= WorkspaceArea.Deals
      activeLabel.content @= "Deal workspace"
      page.children @= Vector(panel)
    })
    val usersButton = WorkspaceStyle.button("Users", () => if (canLeaveWorkspace) {
      val users = new UserAdminPanel(width, session)
      administration = Some(users)
      area @= WorkspaceArea.Users
      activeLabel.content @= "User administration"
      page.children @= Vector(users)
    })
    val workspacesButton = WorkspaceStyle.button("Workspaces", () => if (canLeaveWorkspace) {
      val workspaces = new WorkspaceAdminPanel(width, session)
      workspaceAdministration = Some(workspaces)
      area @= WorkspaceArea.Workspaces
      activeLabel.content @= "Workspaces"
      page.children @= Vector(workspaces)
    })
    workspacesButton.visible @= session.platformAdmin
    workspacesButton.backgroundColor := (if (area() == WorkspaceArea.Workspaces) WorkspaceStyle.Soft else Color.White)
    workspaceButton.backgroundColor := (if (area() == WorkspaceArea.Deals) WorkspaceStyle.Soft else Color.White)
    usersButton.backgroundColor := (if (area() == WorkspaceArea.Users) WorkspaceStyle.Soft else Color.White)
    val navigation = Row(10.0)(workspaceButton, usersButton, workspacesButton)
    navigation.visible @= session.role == UserRole.Admin
    val signOut = WorkspaceStyle.button("Sign out", () => if (canLeaveWorkspace) {
      busy @= true
      ApiClient.logout { error =>
        busy @= false
        error match {
          case Some(value) => message @= value
          case None => loginForm("")
        }
      }
    })
    signOut.position.left := header.size.width() - signOut.size.width() - 24.0
    signOut.position.top @= 19.0
    header.children ++= Vector(tenant, identity, signOut)
    signOut.enabled := !busy() && (area() match {
      case WorkspaceArea.Deals => !panel.processing
      case WorkspaceArea.Users => administration.forall(!_.processing)
      case WorkspaceArea.Workspaces => workspaceAdministration.forall(!_.processing)
    })
    val content = Column(14.0)(status, navigation, page)
    content.position.center := scroll.size.center()
    content.position.top @= 20.0
    scroll.content.children += content
    root.children @= Vector(sidebar, header, scroll)
  }
}
