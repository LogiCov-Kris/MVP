package logicov.publiccompany

import logicov.grid.{GoldenGrid, GridValidation}
import logicov.ingest.{ImportIssue, ImportIssueKind}
import logicov.model.SessionView
import logicov.workspace.*

import java.util.UUID

class PublicIntake(workflow: WorkspaceWorkflow) {
  def begin(session: SessionView, request: PublicIntakeRequest, issuer: PublicIssuer): (WorkspaceDeal, Boolean) = {
    require(UUID.fromString(request.requestId).toString == request.requestId, "Invalid import request identifier")
    require(issuer.cik == request.cik, "Issuer confirmation changed")
    val existing = try Some(workflow.store.get(session.tenantId, request.requestId)) catch { case _: NoSuchElementException => None }
    existing match {
      case Some(deal) =>
        require(deal.publicIssuer.exists(_.cik == request.cik), "Import identifier already belongs to another credit file")
        deal -> false
      case None =>
        val deal = WorkspaceDeal(request.requestId, issuer.name, DealStage.Importing, 1, GoldenGrid(issuer.name, Nil), None, Nil, Nil,
          List(WorkspaceEvent(workflow.now, session.email, "Public import requested", s"SEC CIK ${issuer.cik}; ${issuer.ticker}", 1)),
          publicIssuer = Some(issuer), lease = Some(JobLease(workflow.identifier(), System.currentTimeMillis() + 15 * 60 * 1000)))
        workflow.store.create(session.tenantId, deal, Nil) -> true
    }
  }

  def retry(session: SessionView, id: String, revision: Long): WorkspaceDeal = workflow.store.change(session.tenantId, id, revision) { before =>
    require(before.publicIssuer.nonEmpty && (before.stage == DealStage.ImportFailed ||
      (before.stage == DealStage.Importing && before.lease.exists(_.expiresAt < System.currentTimeMillis()))), "Import is active or already complete")
    before.copy(stage = DealStage.Importing, failure = None, lease = Some(JobLease(workflow.identifier(), System.currentTimeMillis() + 15 * 60 * 1000)),
      events = before.events :+ WorkspaceEvent(workflow.now, session.email, "Public import retried", "Retrying SEC source retrieval", before.revision + 1))
  }

  def complete(session: SessionView, claimed: WorkspaceDeal, snapshot: PublicSnapshot): WorkspaceDeal = {
    require(claimed.publicIssuer.exists(_.cik == snapshot.issuer.cik), "Downloaded issuer differs from the confirmed company")
    val files = snapshot.sources.map((name, contentType, bytes) => workflow.document(name, contentType, bytes) -> bytes)
    val issues = GridValidation.check(snapshot.grid) ++ snapshot.issuer.methodologyIssue.toList.map(ImportIssue(ImportIssueKind.Header, "Credit methodology", _))
    workflow.store.change(session.tenantId, claimed.id, claimed.revision, files.map((meta, bytes) => meta.id -> bytes)) { before =>
      require(before.stage == DealStage.Importing && before.lease == claimed.lease, "Import lease changed")
      before.copy(stage = DealStage.Review, grid = snapshot.grid, title = snapshot.issuer.name, publicIssuer = Some(snapshot.issuer),
        issues = issues, documents = files.map(_._1), lease = None, failure = None,
        events = before.events :+ WorkspaceEvent(workflow.now, session.email, "Public financials imported",
          s"${snapshot.grid.values.size} cited values; ${files.size} retained sources. Facility terms and analyst judgments remain pending.", before.revision + 1))
    }
  }

  def fail(session: SessionView, claimed: WorkspaceDeal, detail: Option[String] = None): WorkspaceDeal = workflow.store.change(session.tenantId, claimed.id, claimed.revision) { before =>
    require(before.stage == DealStage.Importing && before.lease == claimed.lease, "Import lease changed")
    before.copy(stage = DealStage.ImportFailed, lease = None,
      failure = Some(detail.getOrElse("SEC retrieval could not complete. No partial financials were saved. Retry the import; contact support if it continues to fail.").take(500)),
      events = before.events :+ WorkspaceEvent(workflow.now, session.email, "Public import failed", "Source retrieval incomplete; retry available", before.revision + 1))
  }
}
