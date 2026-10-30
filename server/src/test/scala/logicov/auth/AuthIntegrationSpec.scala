package logicov.auth

import lightdb.id.Id
import logicov.config.LogiCovConfig
import logicov.db.DB
import logicov.model.{UserRole, UserAction, UserRequest, SessionView, WorkspaceAction, WorkspaceRequest}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.{Callable, Executors}

class AuthIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val enabled = sys.env.get("LOGICOV_AUTH_TESTS").contains("1")
  private val password = "Integration-password-123!"
  private def address(): String = s"${UUID.randomUUID()}@example.test"
  private def token(email: String): String = AuthService.signIn(email, password).sync()
    .getOrElse(fail("Expected successful sign-in"))

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (enabled) {
      require(sys.env.get("LOGICOV_DB_URL").exists(_.contains("logicov_m1_test")), "Use a dedicated logicov_m1_test database")
      LogiCovConfig.load()
      DB.init.sync()
    }
  }

  override def afterAll(): Unit = {
    try { if (enabled) DB.dispose.sync() }
    finally super.afterAll()
  }

  "Provisioned authentication" should "bind sessions to server-owned tenants and revoke them on logout" in {
    assume(enabled, "Set LOGICOV_AUTH_TESTS=1 with a dedicated PostgreSQL database")
    val first = AuthService.provision("First tenant", address(), password).sync()
    val second = AuthService.provision("Second tenant", address(), password).sync()
    val firstToken = token(first.email)
    val secondToken = token(second.email)
    AuthService.current(firstToken).sync().map(_.tenantId) should be(Some(first.tenantId.value))
    AuthService.current(secondToken).sync().map(_.tenantId) should be(Some(second.tenantId.value))
    first.tenantId should not be second.tenantId
    DB.sessions.transaction(_.get(Id[Session](firstToken))).sync() should be(None)
    DB.sessions.transaction(_.get(Id[Session](SessionTokens.digest(firstToken)))).sync().map(_.userId) should be(Some(first._id))
    AuthService.signOut(firstToken).sync()
    AuthService.current(firstToken).sync() should be(None)
    AuthService.current(secondToken).sync().nonEmpty should be(true)
  }

  it should "reject bad passwords, forged tokens, expired sessions and disabled users" in {
    assume(enabled)
    val user = AuthService.provision("Expiry tenant", address(), password).sync()
    AuthService.signIn(user.email, "wrong-password").sync() should be(None)
    AuthService.signIn(address(), password).sync() should be(None)
    AuthService.current(SessionTokens.create()).sync() should be(None)
    val expired = SessionTokens.create()
    DB.sessions.transaction(_.insert(Session(user._id, 0, _id = Id[Session](SessionTokens.digest(expired))))).sync()
    AuthService.current(expired).sync() should be(None)
    val live = token(user.email)
    DB.users.transaction(_.upsert(user.copy(active = false))).sync()
    AuthService.current(live).sync() should be(None)
    AuthService.signIn(user.email, password).sync() should be(None)
  }

  it should "refuse duplicate accounts instead of resetting credentials" in {
    assume(enabled)
    val user = AuthService.provision("Original tenant", address(), password).sync()
    intercept[IllegalArgumentException](AuthService.provision("Other tenant", user.email.toUpperCase, password).sync())
    AuthService.current(token(user.email)).sync().map(_.tenantId) should be(Some(user.tenantId.value))
  }

  it should "support multiple provisioned users in a tenant and reject a disabled tenant" in {
    assume(enabled)
    val owner = AuthService.provision("Shared tenant", address(), password).sync()
    val colleague = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    val session = token(colleague.email)
    AuthService.current(session).sync().map(_.tenantId) should be(Some(owner.tenantId.value))
    val tenant = DB.tenants.transaction(_.get(owner.tenantId)).sync().getOrElse(fail("Missing tenant"))
    DB.tenants.transaction(_.upsert(tenant.copy(active = false))).sync()
    AuthService.current(session).sync() should be(None)
    AuthService.signIn(owner.email, password).sync() should be(None)
    intercept[IllegalArgumentException](AuthService.addUser("nonexistent", address(), password).sync())
  }
  it should "consume email links once, bind their email and reject expired or disabled identities" in {
    assume(enabled)
    EmailSignIn.migrate()
    val user = AuthService.provision("Email tenant", address(), password).sync()
    var time = System.currentTimeMillis()
    var delivered = ""
    val links = new EmailLinks((_, link) => delivered = link.split("#signin=").last, "https://example.test", () => time)
    links.request(user.email).sync()
    delivered.length should be(43)
    links.confirm(address(), delivered).sync() should be(None)
    val signedIn = links.confirm(user.email.toUpperCase, delivered).sync().getOrElse(fail("Expected email sign-in"))
    AuthService.current(signedIn).sync().map(_.tenantId) should be(Some(user.tenantId.value))
    links.confirm(user.email, delivered).sync() should be(None)
    val first = delivered
    links.request(user.email).sync()
    delivered should be(first)
    time += 61000
    links.request(user.email).sync()
    delivered should not be first
    time += 600001
    links.confirm(user.email, delivered).sync() should be(None)
    links.request(user.email).sync()
    DB.users.transaction(_.upsert(user.copy(active = false))).sync()
    links.confirm(user.email, delivered).sync() should be(None)
  }

  it should "send no links for unknown accounts and invalidate links after delivery failure" in {
    assume(enabled)
    EmailSignIn.migrate()
    var attempts = 0
    val links = new EmailLinks((_, _) => attempts += 1, "https://example.test")
    links.request(address()).sync()
    attempts should be(0)
    val user = AuthService.provision("Delivery tenant", address(), password).sync()
    var failedToken = ""
    val failing = new EmailLinks((_, link) => {
      failedToken = link.split("#signin=").last
      throw new IllegalStateException("Transport unavailable")
    }, "https://example.test")
    intercept[IllegalStateException](failing.request(user.email).sync())
    failing.confirm(user.email, failedToken).sync() should be(None)
  }

  it should "atomically prevent concurrent link reuse and expose current administrator roles" in {
    assume(enabled)
    EmailSignIn.migrate()
    val user = AuthService.provision("Concurrent tenant", address(), password).sync()
    var linkToken = ""
    val links = new EmailLinks((_, link) => linkToken = link.split("#signin=").last, "https://example.test")
    links.request(user.email).sync()
    val pool = Executors.newFixedThreadPool(2)
    try {
      val first = pool.submit(new Callable[Option[String]] { def call(): Option[String] = links.confirm(user.email, linkToken).sync() })
      val second = pool.submit(new Callable[Option[String]] { def call(): Option[String] = links.confirm(user.email, linkToken).sync() })
      val tokens = List(first.get(), second.get()).flatten
      tokens.size should be(1)
      DB.users.transaction(_.upsert(user.copy(role = UserRole.Admin))).sync()
      AuthService.current(tokens.head).sync().map(_.role) should be(Some(UserRole.Admin))
    } finally pool.shutdownNow()
  }
  it should "reject a link issued before an account is removed and recreated" in {
    assume(enabled)
    EmailSignIn.migrate()
    val user = AuthService.provision("Recreated tenant", address(), password).sync()
    var delivered = ""
    val links = new EmailLinks((_, link) => delivered = link.split("#signin=").last, "https://example.test")
    links.request(user.email).sync()
    DB.users.transaction(_.delete(user._id)).sync()
    AuthService.addUser(user.tenantId.value, user.email, password).sync()
    links.confirm(user.email, delivered).sync() should be(None)
  }
  it should "reset passwords with purpose-bound links and revoke sessions and outstanding links" in {
    assume(enabled)
    EmailSignIn.migrate()
    val user = AuthService.provision("Reset tenant", address(), password).sync()
    val session = token(user.email)
    var time = System.currentTimeMillis()
    var resetToken = ""
    var signInToken = ""
    val reset = new EmailLinks((_, link) => resetToken = link.split("#reset=").last,
      "https://example.test", () => time, EmailPurpose.Reset)
    val login = new EmailLinks((_, link) => signInToken = link.split("#signin=").last,
      "https://example.test", () => time)
    login.request(user.email).sync()
    time += 61000
    reset.request(user.email).sync()
    login.confirm(user.email, resetToken).sync() should be(None)
    reset.reset(user.email, signInToken, "Replacement-password-123!").sync() should be(false)
    reset.reset(address(), resetToken, "Replacement-password-123!").sync() should be(false)
    intercept[IllegalArgumentException](reset.reset(user.email, resetToken, "short").sync())
    reset.reset(user.email, resetToken, "Replacement-password-123!").sync() should be(true)
    AuthService.current(session).sync() should be(None)
    AuthService.signIn(user.email, password).sync() should be(None)
    AuthService.signIn(user.email, "Replacement-password-123!").sync().nonEmpty should be(true)
    reset.reset(user.email, resetToken, "Another-password-123!").sync() should be(false)
    login.confirm(user.email, signInToken).sync() should be(None)
    time += 61000
    reset.request(user.email).sync()
    time += 600001
    reset.reset(user.email, resetToken, "Another-password-123!").sync() should be(false)
  }

  private def admin(): (User, SessionView) = {
    val user = AuthService.provision("Managed workspace", address(), password).sync().copy(role = UserRole.Admin)
    DB.users.transaction(_.upsert(user)).sync()
    user -> AuthService.current(token(user.email)).sync().getOrElse(fail("Missing administrator session"))
  }

  "User administration" should "scope accounts, reject analysts and retain management evidence" in {
    assume(enabled)
    val (owner, actor) = admin()
    val (other, outsider) = admin()
    val email = address()
    UserAdministration.change(actor, UserRequest(UserAction.Create, email)).sync()
    val created = UserAdministration.list(actor).sync().find(_.email == email).getOrElse(fail("Missing created user"))
    created.role should be(UserRole.Analyst)
    created.history.head.actor should be(owner.email)
    UserAdministration.list(outsider).sync().exists(_.email == email) should be(false)
    intercept[NoSuchElementException](UserAdministration.change(outsider, UserRequest(UserAction.Update, email, revision = created.revision)).sync())
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Create, other.email)).sync())
    val analyst = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    val analystSession = AuthService.current(token(analyst.email)).sync().getOrElse(fail("Missing analyst"))
    intercept[IllegalArgumentException](UserAdministration.list(analystSession).sync())
    intercept[IllegalArgumentException](UserAdministration.change(analystSession, UserRequest(UserAction.Create, address())).sync())
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Update, email, revision = 0)).sync())
  }

  it should "revoke sessions and links permanently across suspension and reactivation" in {
    assume(enabled)
    EmailSignIn.migrate()
    val (owner, actor) = admin()
    val user = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    val live = token(user.email)
    var linkToken = ""
    val links = new EmailLinks((_, link) => linkToken = link.split("#signin=").last, "https://example.test")
    links.request(user.email).sync()
    UserAdministration.list(actor).sync().find(_.email == user.email).map(_.activeSessions) should be(Some(1))
    UserAdministration.change(actor, UserRequest(UserAction.Update, user.email, active = false)).sync()
    AuthService.current(live).sync() should be(None)
    UserAdministration.change(actor, UserRequest(UserAction.Update, user.email, active = true, revision = 1)).sync()
    AuthService.current(live).sync() should be(None)
    links.confirm(user.email, linkToken).sync() should be(None)
    val fresh = token(user.email)
    UserAdministration.change(actor, UserRequest(UserAction.RevokeSessions, user.email, revision = 2)).sync()
    AuthService.current(fresh).sync() should be(None)
    UserAdministration.list(actor).sync().find(_.email == user.email).exists(_.lastSignIn.nonEmpty) should be(true)
  }

  it should "prevent self lockout and reject stale concurrent administrator edits" in {
    assume(enabled)
    val (owner, actor) = admin()
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Update, owner.email, active = false)).sync())
    val user = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    val pool = Executors.newFixedThreadPool(2)
    try {
      val calls = (1 to 2).map { _ => pool.submit(new Callable[Boolean] {
        def call(): Boolean = try {
          UserAdministration.change(actor, UserRequest(UserAction.Update, user.email, role = UserRole.Admin)).sync()
          true
        } catch { case _: IllegalArgumentException => false }
      }) }
      calls.count(_.get()) should be(1)
    } finally pool.shutdownNow()
  }

  it should "retain an administrator during concurrent reciprocal suspensions" in {
    assume(enabled)
    val (owner, actor) = admin()
    val second = AuthService.addUser(owner.tenantId.value, address(), password).sync().copy(role = UserRole.Admin)
    DB.users.transaction(_.upsert(second)).sync()
    val colleague = AuthService.current(token(second.email)).sync().getOrElse(fail("Missing administrator"))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val calls = List(actor -> second.email, colleague -> owner.email).map { (acting, target) =>
        pool.submit(new Callable[Boolean] {
          def call(): Boolean = try {
            UserAdministration.change(acting, UserRequest(UserAction.Update, target, active = false)).sync()
            true
          } catch { case _: IllegalArgumentException => false }
        })
      }
      calls.count(_.get()) should be(1)
      DB.users.transaction(_.stream.toList).sync().count(u => u.tenantId == owner.tenantId && u.active && u.role == UserRole.Admin) should be(1)
    } finally pool.shutdownNow()
  }

  it should "report invitation failures honestly and preserve created accounts for retry" in {
    assume(enabled)
    val (owner, actor) = admin()
    val user = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    var delivered = ""
    UserAdministration.change(actor, UserRequest(UserAction.Invite, user.email), (email, _) => delivered = email, emailEnabled = true).sync()
    delivered should be(user.email)
    UserAdministration.list(actor).sync().find(_.email == user.email).map(_.history.last.action) should be(Some(UserAction.InvitationSent))
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Invite, user.email, revision = 2), (_, _) => (), emailEnabled = true).sync())
    val failed = AuthService.addUser(owner.tenantId.value, address(), password).sync()
    intercept[IllegalStateException](UserAdministration.change(actor, UserRequest(UserAction.Invite, failed.email), (_, _) => throw new IllegalStateException("Unavailable"), emailEnabled = true).sync())
    UserAdministration.list(actor).sync().find(_.email == failed.email).map(_.history.last.action) should be(Some(UserAction.InvitationFailed))
    UserAdministration.list(actor).sync().exists(_.email == failed.email) should be(true)
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Invite, owner.email), (_, _) => (), emailEnabled = false).sync())
  }

  private def platform(): (User, SessionView) = {
    val (user, _) = admin()
    val elevated = user.copy(platformAdmin = true)
    DB.users.transaction(_.upsert(elevated)).sync()
    elevated -> AuthService.current(token(user.email)).sync().getOrElse(fail("Missing platform session"))
  }

  "Workspace administration" should "create isolated workspaces with an ordinary first administrator" in {
    assume(enabled)
    val (_, actor) = platform()
    val name = "Customer " + UUID.randomUUID()
    val email = address()
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Create, name, administratorEmail = email)).sync()
    val workspace = WorkspaceAdministration.list(actor).sync().find(_.name == name).getOrElse(fail("Missing workspace"))
    workspace.users should be(1)
    workspace.administrators should be(1)
    val members = UserAdministration.list(actor, workspace.id).sync()
    members.map(_.email) should be(List(email))
    members.head.platformAdmin should be(false)
    val account = DB.users.transaction(_.get(Id[User](SessionTokens.digest(email)))).sync().getOrElse(fail("Missing first administrator"))
    val session = AuthService.current(AuthService.createSession(account).sync().getOrElse(fail("Missing token"))).sync().getOrElse(fail("Missing session"))
    session.tenantId should be(workspace.id)
    session.platformAdmin should be(false)
    intercept[IllegalArgumentException](WorkspaceAdministration.list(session).sync())
    intercept[IllegalArgumentException](UserAdministration.list(session, actor.tenantId).sync())
    UserAdministration.change(actor, UserRequest(UserAction.Create, address()), workspaceId = workspace.id).sync()
    UserAdministration.list(actor, workspace.id).sync().size should be(2)
    UserAdministration.list(actor).sync().exists(_.email == email) should be(false)
  }

  it should "permanently revoke customer sessions and links when a workspace is suspended" in {
    assume(enabled)
    EmailSignIn.migrate()
    val (_, actor) = platform()
    val name = "Suspend " + UUID.randomUUID()
    val email = address()
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Create, name, administratorEmail = email)).sync()
    val workspace = WorkspaceAdministration.list(actor).sync().find(_.name == name).getOrElse(fail("Missing workspace"))
    val user = DB.users.transaction(_.get(Id[User](SessionTokens.digest(email)))).sync().getOrElse(fail("Missing user"))
    val live = AuthService.createSession(user).sync().getOrElse(fail("Missing token"))
    var pendingLink = ""
    val links = new EmailLinks((_, link) => pendingLink = link.split("#signin=").last, "https://example.test")
    links.request(email).sync()
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Update, name, workspace.id, active = false, revision = 1)).sync()
    AuthService.current(live).sync() should be(None)
    intercept[IllegalArgumentException](UserAdministration.change(actor, UserRequest(UserAction.Create, address()), workspaceId = workspace.id).sync())
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Update, name, workspace.id, active = true, revision = 2)).sync()
    AuthService.current(live).sync() should be(None)
    links.confirm(email, pendingLink).sync() should be(None)
    val latest = DB.users.transaction(_.get(user._id)).sync().getOrElse(fail("Missing user"))
    AuthService.createSession(latest).sync().nonEmpty should be(true)
    WorkspaceAdministration.list(actor).sync().find(_.id == workspace.id).map(_.history.size) should be(Some(3))
  }

  it should "reject stale updates and atomically reject a duplicate initial administrator" in {
    assume(enabled)
    val (_, actor) = platform()
    val before = WorkspaceAdministration.list(actor).sync().size
    intercept[IllegalArgumentException](WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Create, "Duplicate " + UUID.randomUUID(), administratorEmail = actor.email)).sync())
    WorkspaceAdministration.list(actor).sync().size should be(before)
    val name = "Revision " + UUID.randomUUID()
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Create, name, administratorEmail = address())).sync()
    val workspace = WorkspaceAdministration.list(actor).sync().find(_.name == name).getOrElse(fail("Missing workspace"))
    WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Update, name + " renamed", workspace.id, revision = 1)).sync()
    intercept[IllegalArgumentException](WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Update, name, workspace.id, revision = 1)).sync())
    val home = WorkspaceAdministration.list(actor).sync().find(_.id == actor.tenantId).getOrElse(fail("Missing home"))
    intercept[IllegalArgumentException](WorkspaceAdministration.change(actor, WorkspaceRequest(WorkspaceAction.Update, home.name, home.id, active = false, revision = home.revision)).sync())
  }

  it should "protect platform accounts from customer administrators and recheck platform authority" in {
    assume(enabled)
    val (owner, actor) = platform()
    val colleague = AuthService.addUser(owner.tenantId.value, address(), password).sync().copy(role = UserRole.Admin)
    DB.users.transaction(_.upsert(colleague)).sync()
    val localAdmin = AuthService.current(token(colleague.email)).sync().getOrElse(fail("Missing local admin"))
    intercept[IllegalArgumentException](UserAdministration.change(localAdmin, UserRequest(UserAction.Update, owner.email, active = false)).sync())
    intercept[IllegalArgumentException](WorkspaceAdministration.change(localAdmin, WorkspaceRequest(WorkspaceAction.Create, "Unauthorized", administratorEmail = address())).sync())
    DB.users.transaction(_.upsert(owner.copy(platformAdmin = false))).sync()
    intercept[IllegalArgumentException](WorkspaceAdministration.list(actor).sync())
  }

}
