package logicov.auth

import lightdb.id.Id
import logicov.config.LogiCovConfig
import logicov.db.DB
import logicov.model.UserRole
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

}
