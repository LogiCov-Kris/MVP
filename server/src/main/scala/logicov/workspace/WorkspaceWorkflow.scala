package logicov.workspace

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.analysis.CreditAnalysis
import logicov.deal.{DealCorrections, DealRecord}
import logicov.grid.{GridValidation, GridValue}
import logicov.ingest.{ImportIssueKind, StatementImporter}
import logicov.model.SessionView
import logicov.memo.NarrativeText
import logicov.source.Source

import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, HexFormat, UUID}

class WorkspaceWorkflow(val store: WorkspaceStore) {
  def now: String = Instant.now().toString
  def identifier(): String = UUID.randomUUID().toString
  def sha(bytes: Array[Byte]): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  def document(name: String, contentType: String, bytes: Array[Byte]): WorkspaceDocument =
    WorkspaceDocument(identifier(), name, contentType, sha(bytes), bytes.length.toLong, now)

  def create(session: SessionView, request: CreateDealRequest, retained: List[(WorkspaceDocument, Array[Byte])] = Nil): WorkspaceDeal = {
    require(request.title.trim.nonEmpty && request.title.length <= 200, "Deal name required (up to 200 characters)")
    require(request.upload.isDefined != request.record.isDefined, "Provide one statement or complete deal record")
    val (grid, record, issues, files) = request.upload match {
      case Some(upload) =>
        val bytes = Base64.getDecoder.decode(upload.data)
        val result = StatementImporter.run(upload.filename, bytes, upload.options)
        val original = document(upload.filename, "application/octet-stream", bytes)
        (result.grid, Option.empty[DealRecord], result.issues, List(original -> bytes))
      case None =>
        val value = request.record.getOrElse(throw new IllegalArgumentException("Deal record required"))
        CreditAnalysis.run(value)
        require(value.grid.values.size <= 10000, "Deal record exceeds 10000 values")
        val bytes = JsonFormatter.Compact(value.json).getBytes("UTF-8")
        val original = document("original-deal.json", "application/json", bytes)
        (value.grid, Some(value), GridValidation.check(value.grid), List(original -> bytes))
    }
    val deal = WorkspaceDeal(identifier(), request.title.trim, DealStage.Review, 1, grid, record, issues,
      (files ++ retained).map(_._1), List(WorkspaceEvent(now, session.email, "Created", "Source retained; awaiting analyst review", 1)))
    store.create(session.tenantId, deal, (files ++ retained).map((meta, bytes) => meta.id -> bytes))
  }

  private def reason(value: String): Unit = require(value.trim.nonEmpty && value.length <= 2000, "A review reason is required (up to 2000 characters)")

  private def editable(deal: WorkspaceDeal): Unit =
    require(deal.stage != DealStage.Generating, "Wait for memo generation to finish or resume an expired job before editing")

  private def event(deal: WorkspaceDeal, session: SessionView, action: String, detail: String): List[WorkspaceEvent] =
    deal.events :+ WorkspaceEvent(now, session.email, action, detail, deal.revision + 1)

  private def revised(deal: WorkspaceDeal): WorkspaceDeal = deal.copy(stage = DealStage.Review, approval = None, lease = None, memo = None, failure = None, progress = GenerationProgress(), generatedNarratives = Map.empty, sectionReviews = Nil, submission = None,
    policyResults = PolicyEngine.evaluate(deal.policy, deal.record))

  def borrower(session: SessionView, id: String, request: BorrowerRequest): WorkspaceDeal = {
    reason(request.reason)
    require(request.name.trim.nonEmpty && request.name.length <= 200, "Borrower legal name is required (up to 200 characters)")
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      val name = request.name.trim
      val grid = before.grid.copy(entity = name)
      revised(before.copy(title = name, grid = grid, record = before.record.map(_.copy(borrower = name, grid = grid)), stopReviews = Nil,
        events = event(before, session, "Borrower name corrected", s"${before.title} -> $name. ${request.reason}")))
    }
  }

  def correct(session: SessionView, id: String, request: DealCorrectionRequest): WorkspaceDeal = {
    reason(request.correction.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      request.correction.source match {
        case Source.Upload(name, hash, location, excerpt) =>
          require(before.documents.exists(d => d.name == name && d.sha256 == hash) && location.trim.nonEmpty && excerpt.trim.nonEmpty,
            "Correction must cite a retained source document and location")
        case Source.Derived(_) => throw new IllegalArgumentException("A correction requires a source citation")
        case source => require(source.describe.trim.nonEmpty, "A source citation is required")
      }
      val correction = request.correction.copy(analyst = session.email, recordedAt = now)
      val matching = before.grid.values.filter(v => v.item == correction.item && v.period == correction.period)
      require(matching.size == 1 && matching.head.value == correction.expected, "The selected cell changed; reload before correcting")
      val record = before.record.map(r => DealCorrections(r, List(correction)))
      val grid = record.fold(before.grid.copy(values = before.grid.values.map { v =>
        if (v == matching.head) GridValue(v.item, v.period, correction.value, correction.source) else v
      }))(_.grid)
      val retainedIssues = before.issues.filterNot(i => Set(ImportIssueKind.Duplicate, ImportIssueKind.Reconciliation).contains(i.kind))
      revised(before.copy(stopReviews = Nil, grid = grid, record = record, issues = retainedIssues ++ GridValidation.check(grid), dispositions = Map.empty,
        events = event(before, session, "Grid corrected", JsonFormatter.Compact(correction.json))))
    }
  }

  def resolve(session: SessionView, id: String, request: IssueResolutionRequest): WorkspaceDeal = {
    reason(request.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      require(before.issues.indices.contains(request.issue), "Review issue not found")
      revised(before.copy(dispositions = before.dispositions.updated(request.issue.toString, request.reason),
        events = event(before, session, "Issue reviewed", s"${request.issue}: ${request.reason}")))
    }
  }

  def policy(session: SessionView, id: String, request: PolicyRequest): WorkspaceDeal = {
    request.policy.validate()
    reason(request.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      revised(before.copy(policy = Some(request.policy), events = event(before, session, "Policy changed",
        JsonFormatter.Compact(request.json))))
    }
  }

  def attach(session: SessionView, id: String, request: DocumentRequest): WorkspaceDeal = {
    require(request.filename.nonEmpty && request.filename.length <= 180 && !request.filename.exists(c => c == '/' || c == '\\') &&
      !request.filename.startsWith("generated-") && request.filename != "." && request.filename != "..", "Invalid source filename")
    val bytes = Base64.getDecoder.decode(request.data)
    require(bytes.nonEmpty && bytes.length <= 5 * 1024 * 1024, "Source document must be between 1 byte and 5 MB")
    val metadata = document(request.filename, "application/octet-stream", bytes)
    store.change(session.tenantId, id, request.revision, List(metadata.id -> bytes)) { before =>
      editable(before)
      require(before.documents.count(d => !d.name.startsWith("generated-")) < 100, "This deal already has 100 source documents")
      revised(before.copy(stopReviews = Nil, documents = before.documents :+ metadata,
        events = event(before, session, "Source attached", s"${metadata.name}; SHA-256 ${metadata.sha256}")))
    }
  }

  def setup(session: SessionView, id: String, request: CreditSetupRequest): WorkspaceDeal = {
    reason(request.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      val record = CreditSetup.build(before, request)
      revised(before.copy(stopReviews = Nil, record = Some(record), events = event(before, session, "Credit profile added", JsonFormatter.Compact(request.json))))
    }
  }

  def clearPolicy(session: SessionView, id: String, request: DealActionRequest): WorkspaceDeal = {
    reason(request.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      revised(before.copy(policy = None, events = event(before, session, "Policy removed",
        s"${request.reason}; prior policy: ${JsonFormatter.Compact(before.policy.json)}")))
    }
  }

  def judgments(session: SessionView, id: String, request: JudgmentRequest): WorkspaceDeal = {
    reason(request.reason)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      val record = before.record.getOrElse(throw new IllegalArgumentException("A complete sourced deal record is required for credit judgments"))
      require(request.inputs.ticker == record.inputs.ticker && request.inputs.cik == record.inputs.cik, "Borrower identity cannot be changed here")
      require(request.inputs.support == request.profile.support && request.inputs.ratingOverride == request.profile.ratingOverride &&
        request.inputs.commodityModifier == request.profile.commodityModifier, "Analyst inputs and credit profile must use the same judgments")
      require(request.inputs.customerOverTenPercent == request.profile.customerOverTenPercent &&
        request.inputs.stressScenarios == request.profile.stressScenarios && request.inputs.proForma == request.profile.proForma &&
        request.inputs.covenantIndicative == request.profile.covenant.indicative, "Analyst inputs and credit profile must agree")
      require(request.profile.largestShare.value >= 0 && request.profile.largestShare.value <= 1 &&
        request.profile.priceLinkedShare.value >= 0 && request.profile.priceLinkedShare.value <= 1, "Concentration shares must be between zero and one")
      val updated = record.copy(inputs = request.inputs, profile = request.profile)
      val previousRating = CreditAnalysis.run(record).rating.machineGrade
      val updatedRating = CreditAnalysis.run(updated).rating.machineGrade
      val pending = if (request.overrideReviewed) None
        else if (updated.profile.ratingOverride.nonEmpty && previousRating != updatedRating)
          Some("Judgment changes affected the calculated risk rating; review the existing override before approval.")
        else record.inputs.ratingReview
      val reviewed = updated.copy(inputs = updated.inputs.copy(ratingReview = pending))
      revised(before.copy(stopReviews = Nil, record = Some(reviewed), events = event(before, session, "Analyst judgments changed", JsonFormatter.Compact(request.json))))
    }
  }

  def recommendation(session: SessionView, id: String, request: RecommendationRequest): WorkspaceDeal = {
    reason(request.rationale)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      require(before.record.nonEmpty, "A complete credit record is required for a credit recommendation")
      requireReviewed(before)
      revised(before.copy(recommendation = request.recommendation, recommendationBasis = request.rationale,
        events = event(before, session, "Credit recommendation changed", JsonFormatter.Compact(request.json))))
    }
  }

  def narrative(session: SessionView, id: String, request: NarrativeRequest): WorkspaceDeal = {
    reason(request.reason)
    require(WorkspaceDraft.sectionTitles.contains(request.section) && request.text.length <= 20000, "Invalid narrative section")
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      WorkspaceDraft.validateText(before, request.section, request.text)
      revised(before.copy(narratives = before.narratives.updated(request.section, request.text),
        events = event(before, session, "Narrative edited", JsonFormatter.Compact(request.json))))
    }
  }

  private def requireReviewed(deal: WorkspaceDeal): Unit = {
    require(deal.stage == DealStage.Completed && deal.memo.nonEmpty, "Generate the current memo before committee review")
    require(WorkspaceDraft.sectionTitles.forall(title => deal.sectionReviews.exists(_.section == title)), "Review every memo section before choosing a recommendation or submitting")
    StopReview.requireClear(deal.stopReviews)
  }

  def reviewSection(session: SessionView, id: String, request: SectionReviewRequest): WorkspaceDeal =
    store.change(session.tenantId, id, request.revision) { before =>
      require(before.stage == DealStage.Completed && before.memo.nonEmpty, "Generate the current memo before marking sections reviewed")
      require(WorkspaceDraft.sectionTitles.contains(request.section), "Unknown memo section")
      val retained = before.sectionReviews.filterNot(_.section == request.section)
      before.copy(sectionReviews = if (request.reviewed) retained :+ SectionReview(request.section, session.email, now) else retained,
        events = event(before, session, "Memo section reviewed", s"${request.section}: ${request.reviewed}"))
    }

  def submit(session: SessionView, id: String, request: SubmissionRequest): WorkspaceDeal = {
    reason(request.rationale)
    require(Set(CreditRecommendation.Approve, CreditRecommendation.Conditional, CreditRecommendation.Decline).contains(request.recommendation), "Choose Approve, Conditional or Decline")
    store.change(session.tenantId, id, request.revision) { before =>
      requireReviewed(before)
      require(before.record.nonEmpty && before.approval.nonEmpty, "A complete approved credit record is required for committee submission")
      before.copy(stage = DealStage.Generating, lease = Some(JobLease(identifier(), System.currentTimeMillis() + 15 * 60 * 1000)),
        recommendation = request.recommendation, recommendationBasis = request.rationale, failure = None, progress = GenerationProgress(),
        submission = Some(SubmissionRecord(session.email, now, request.recommendation, request.rationale)),
        events = event(before, session, "Committee submission requested", JsonFormatter.Compact(request.json)))
    }
  }

  def reviewStops(session: SessionView, id: String, request: StopReviewRequest): WorkspaceDeal = {
    StopReview.validate(request.reviews)
    store.change(session.tenantId, id, request.revision) { before =>
      editable(before)
      revised(before.copy(stopReviews = request.reviews,
        events = event(before, session, "Required checks reviewed", JsonFormatter.Compact(request.reviews.json))))
    }
  }

  def approve(session: SessionView, id: String, request: DealActionRequest): WorkspaceDeal =
    store.change(session.tenantId, id, request.revision) { before =>
      require(before.stage == DealStage.Review || before.stage == DealStage.Failed, "This revision is not awaiting approval")
      require(request.reason.length <= 2000, "Approval explanation exceeds 2000 characters")
      require(before.record.forall(_.inputs.ratingReview.isEmpty), "Review the existing rating override after the financial correction before approval")
      require(before.grid.values.nonEmpty, "An empty grid cannot be approved")
      require(before.issues.indices.forall(i => before.dispositions.contains(i.toString)), "Review and document every extraction issue before approval")
      StopReview.requireClear(before.stopReviews)
      val results = PolicyEngine.evaluate(before.policy, before.record)
      if (results.exists(_.status != PolicyStatus.Pass)) reason(request.reason)
      before.copy(stage = DealStage.Approved, approval = Some(ApprovalRecord(session.email, now, before.revision, request.reason)),
        policyResults = results, failure = None, lease = None,
        events = event(before, session, "Approved", s"Approved revision ${before.revision}. Policy exception: ${request.reason}"))
    }

  def claim(session: SessionView, id: String, request: DealActionRequest): WorkspaceDeal =
    store.change(session.tenantId, id, request.revision) { before =>
      StopReview.requireClear(before.stopReviews)
      require(before.approval.nonEmpty, "Analyst approval is required before generation")
      require(before.stage == DealStage.Approved || before.stage == DealStage.Failed ||
        (before.stage == DealStage.Generating && before.lease.exists(_.expiresAt < System.currentTimeMillis())), "Generation is already active or this revision is complete")
      before.copy(stage = DealStage.Generating, lease = Some(JobLease(identifier(), System.currentTimeMillis() + 15 * 60 * 1000)),
        failure = None, progress = GenerationProgress(), events = event(before, session, "Generation started", "Approved snapshot claimed for memo generation"))
    }

  def generationProgress(session: SessionView, claimed: WorkspaceDeal, progress: GenerationProgress): WorkspaceDeal =
    store.change(session.tenantId, claimed.id, claimed.revision) { current =>
      require(current.stage == DealStage.Generating && current.lease == claimed.lease, "Generation lease changed")
      current.copy(progress = progress)
    }

  def complete(session: SessionView, claimed: WorkspaceDeal, files: List[(WorkspaceDocument, Array[Byte])], pdf: String): WorkspaceDeal =
    store.change(session.tenantId, claimed.id, claimed.revision, files.map((m, bytes) => m.id -> bytes)) { current =>
      require(current.stage == DealStage.Generating && current.lease == claimed.lease, "Generation lease changed")
      current.copy(stage = if (current.submission.nonEmpty) DealStage.Submitted else DealStage.Completed, lease = None, memo = Some(pdf), generatedNarratives = files.find(_._1.name == "generated-narrative-evidence.json").fold(current.generatedNarratives)(f => JsonParser(new String(f._2, "UTF-8")).as[Map[String, NarrativeText]]), documents = current.documents ++ files.map(_._1),
        events = event(current, session, "Memo generated", s"PDF $pdf; approved revision ${current.approval.map(_.revision).getOrElse(0L)}"))
    }

  def fail(session: SessionView, claimed: WorkspaceDeal): Unit = {
    store.change(session.tenantId, claimed.id, claimed.revision) { current =>
      require(current.lease == claimed.lease, "Generation lease changed")
      current.copy(stage = DealStage.Failed, lease = None, failure = Some("Memo generation failed. The approved revision is retained; retry generation."),
        events = event(current, session, "Generation failed", "Approved snapshot retained for retry"))
    }
    ()
  }
}
