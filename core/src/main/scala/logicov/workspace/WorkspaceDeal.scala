package logicov.workspace

import fabric.rw.*
import logicov.grid.GoldenGrid
import logicov.deal.DealRecord
import logicov.ingest.ImportIssue
import logicov.memo.NarrativeText

case class WorkspaceDeal(id: String, title: String, stage: DealStage, revision: Long,
  grid: GoldenGrid, record: Option[DealRecord], issues: List[ImportIssue],
  documents: List[WorkspaceDocument], events: List[WorkspaceEvent],
  dispositions: Map[String, String] = Map.empty, policy: Option[PolicySet] = None,
  policyResults: List[PolicyResult] = Nil, approval: Option[ApprovalRecord] = None,
  lease: Option[JobLease] = None, memo: Option[String] = None, failure: Option[String] = None,
  narratives: Map[String, String] = Map.empty,
                         recommendation: CreditRecommendation = CreditRecommendation.Pending, recommendationBasis: String = "", stopReviews: List[StopReview] = Nil, sectionReviews: List[SectionReview] = Nil, submission: Option[SubmissionRecord] = None, progress: GenerationProgress = GenerationProgress(), generatedNarratives: Map[String, NarrativeText] = Map.empty) derives RW
