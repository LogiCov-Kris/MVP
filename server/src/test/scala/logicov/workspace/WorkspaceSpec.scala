package logicov.workspace

import logicov.config.DBConfig
import logicov.analysis.SampleDeals
import logicov.db.ManagedConnections
import logicov.grid.GridCorrection
import logicov.ingest.{AmountUnit, ImportColumn, ImportOptions, ImportRequest}
import logicov.model.SessionView
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate
import java.util.{Base64, UUID}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import javax.crypto.AEADBadTagException

class WorkspaceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val enabled = sys.env.get("LOGICOV_AUTH_TESTS").contains("1")
  private val connections = new ManagedConnections(DBConfig(sys.env.getOrElse("LOGICOV_DB_URL", ""), sys.env.getOrElse("LOGICOV_DB_USER", "logicov"), sys.env.getOrElse("LOGICOV_DB_PASSWORD", "logicov-dev"), 2))
  private val database = new WorkspaceDatabase(connections)
  private val cipher = new TenantCipher(Array.fill[Byte](32)(17))
  private val store = new WorkspaceStore(database, cipher)
  private val workflow = new WorkspaceWorkflow(store)
  private def session(): SessionView = SessionView("analyst@example.test", UUID.randomUUID().toString, "Test tenant")
  private def create(user: SessionView): WorkspaceDeal = workflow.create(user, CreateDealRequest("Private borrower", Some(ImportRequest(
    "financials.csv", Base64.getEncoder.encodeToString("Revenue,120\nCash,10\n".getBytes("UTF-8")),
    ImportOptions("Private borrower", AmountUnit.Millions, List(ImportColumn(2, "FY2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))))))))

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (enabled) {
      require(sys.env.get("LOGICOV_DB_URL").exists(_.contains("logicov_m1_test")))
      database.initialize()
      database.verify()
    }
  }
  override def afterAll(): Unit = {
    try { if (enabled) connections.dispose.sync() }
    finally super.afterAll()
  }

  "Tenant encryption" should "bind keys and ciphertext to their tenant and document identities" in {
    val key = cipher.newKey()
    val wrapped = cipher.wrap("first", key)
    cipher.unwrap("first", wrapped).toSeq should be(key.toSeq)
    intercept[AEADBadTagException](cipher.unwrap("second", wrapped))
    val payload = cipher.encrypt(key, "document:first", "confidential".getBytes("UTF-8"))
    intercept[AEADBadTagException](cipher.decrypt(key, "document:second", payload))
    payload(payload.length - 1) = (payload.last ^ 1).toByte
    intercept[AEADBadTagException](cipher.decrypt(key, "document:first", payload))
  }

  "Saved workspaces" should "isolate records and files with database policies and encrypted payloads" in {
    assume(enabled)
    val first = session()
    val second = session()
    val deal = create(first)
    store.list(first.tenantId).map(_.id) should contain(deal.id)
    store.list(second.tenantId) should be(empty)
    intercept[NoSuchElementException](store.get(second.tenantId, deal.id))
    intercept[NoSuchElementException](store.file(second.tenantId, deal.id, deal.documents.head.id))
    new String(store.file(first.tenantId, deal.id, deal.documents.head.id)._2, "UTF-8") should include("Revenue,120")
    database.tenant(second.tenantId) { connection =>
      val query = connection.createStatement()
      try {
        val rows = query.executeQuery("SELECT count(*) FROM workspace_deals")
        try { rows.next(); rows.getInt(1) should be(0) } finally rows.close()
        intercept[java.sql.SQLException](query.executeUpdate(s"INSERT INTO workspace_keys VALUES ('${first.tenantId}', decode('00','hex'))"))
      } finally query.close()
    }
    database.tenant(first.tenantId) { connection =>
      val query = connection.createStatement()
      try {
        val rows = query.executeQuery("SELECT payload FROM workspace_deals")
        try { rows.next(); new String(rows.getBytes(1), "UTF-8") should not include "Private borrower" } finally rows.close()
      } finally query.close()
    }
    connections.jdbc { connection =>
      val query = connection.createStatement()
      try {
        query.execute("SET LOCAL ROLE logicov_workspace")
        val rows = query.executeQuery("SELECT count(*) FROM workspace_deals")
        try { rows.next(); rows.getInt(1) should be(0) } finally rows.close()
      } finally query.close()
    }
  }

  it should "require explicit approval, reject stale edits and revoke approval after a correction" in {
    assume(enabled)
    val user = session()
    val deal = create(user)
    intercept[IllegalArgumentException](workflow.claim(user, deal.id, DealActionRequest(deal.revision)))
    intercept[IllegalArgumentException](workflow.approve(user, deal.id, DealActionRequest(deal.revision)))
    val approved = workflow.approve(user, deal.id, DealActionRequest(deal.revision, "Policy not yet configured; analyst reviewed the source"))
    val cell = approved.grid.values.head
    val correction = GridCorrection(cell.item, cell.period, cell.value, cell.value + 1, cell.source, "Source correction", "forged", "forged")
    intercept[WorkspaceConflict](workflow.correct(user, deal.id, DealCorrectionRequest(deal.revision, correction)))
    val changed = workflow.correct(user, deal.id, DealCorrectionRequest(approved.revision, correction))
    changed.approval should be(None)
    changed.stage should be(DealStage.Review)
    changed.events.last.actor should be(user.email)
    changed.events.last.detail should not include "forged"
  }

  it should "recover an expired generation lease and preserve the approved snapshot across store instances" in {
    assume(enabled)
    val user = session()
    val deal = create(user)
    val approved = workflow.approve(user, deal.id, DealActionRequest(deal.revision, "Reviewed without institution policy"))
    val claimed = workflow.claim(user, deal.id, DealActionRequest(approved.revision))
    intercept[IllegalArgumentException](workflow.claim(user, deal.id, DealActionRequest(claimed.revision)))
    val expired = store.change(user.tenantId, deal.id, claimed.revision)(_.copy(lease = claimed.lease.map(_.copy(expiresAt = 0))))
    val restored = new WorkspaceWorkflow(new WorkspaceStore(database, cipher))
    val resumed = restored.claim(user, deal.id, DealActionRequest(expired.revision))
    resumed.approval should be(approved.approval)
    resumed.lease should not be claimed.lease
    intercept[WorkspaceConflict](workflow.fail(user, claimed))
    restored.fail(user, resumed)
    store.get(user.tenantId, deal.id).stage should be(DealStage.Failed)
  }
  it should "require an explicit override review rather than accepting a cleared warning from the client" in {
    assume(enabled)
    val user = session()
    val record = SampleDeals.occidental.copy(inputs = SampleDeals.occidental.inputs.copy(ratingReview = Some("Review existing override")))
    val deal = workflow.create(user, CreateDealRequest("Override review", record = Some(record)))
    intercept[IllegalArgumentException](workflow.approve(user, deal.id, DealActionRequest(deal.revision, "Reviewed without policy")))
    val unreviewed = workflow.judgments(user, deal.id, JudgmentRequest(deal.revision, record.inputs.copy(ratingReview = None), record.profile, "Changed explanation"))
    unreviewed.record.flatMap(_.inputs.ratingReview) should not be empty
    val reviewed = workflow.judgments(user, deal.id, JudgmentRequest(unreviewed.revision, record.inputs.copy(ratingReview = None), record.profile, "Confirmed existing override", overrideReviewed = true))
    reviewed.record.flatMap(_.inputs.ratingReview) should be(None)
    reviewed.recommendation should be(CreditRecommendation.Pending)
    val recommended = workflow.recommendation(user, deal.id, RecommendationRequest(reviewed.revision, CreditRecommendation.Conditional, "Analyst's documented conditions"))
    recommended.recommendation should be(CreditRecommendation.Conditional)
    recommended.stage should be(DealStage.Review)
    recommended.approval should be(None)
  }

  it should "coordinate repeated migration checks with active workspace transactions" in {
    assume(enabled)
    val user = session()
    val deal = create(user)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val reads = executor.submit(new Callable[Unit] {
        override def call(): Unit = (1 to 20).foreach(_ => store.get(user.tenantId, deal.id).id should be(deal.id))
      })
      val migrations = executor.submit(new Callable[Unit] {
        override def call(): Unit = (1 to 20).foreach(_ => database.initialize())
      })
      reads.get(30, TimeUnit.SECONDS)
      migrations.get(30, TimeUnit.SECONDS)
    } finally executor.shutdownNow()
  }

}
