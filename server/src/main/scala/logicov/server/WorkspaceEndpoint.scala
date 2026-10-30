package logicov.server

import fabric.io.JsonParser
import fabric.rw.*
import logicov.auth.AuthService
import logicov.model.{SessionView, UserRole}
import logicov.workspace.*
import rapid.Task
import spice.http.{HttpExchange, HttpMethod, HttpStatus}
import spice.http.content.Content
import spice.http.server.dsl.{ConnectionFilter, FilterResponse}
import spice.net.ContentType

import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import scala.util.{Failure, Success, Try}

object WorkspaceEndpoint extends ConnectionFilter {
  private val jobs = new Semaphore(1)
  private val writes = new Semaphore(2)

  override def apply(exchange: HttpExchange)(using scribe.mdc.MDC): Task[FilterResponse] = {
    val parts = exchange.request.url.path.decoded.stripPrefix("/api/deals").split('/').filter(_.nonEmpty).toList
    AuthService.current(WebEndpoint.token(exchange)).flatMap {
      case None => WebEndpoint.error(exchange, 401, "Please sign in.")
      case Some(session) if exchange.request.method == HttpMethod.Post && !WebEndpoint.mutationAllowed(exchange) =>
        WebEndpoint.error(exchange, 403, "Request not permitted.")
      case Some(session) => (exchange.request.method, parts) match {
        case (HttpMethod.Get, Nil) => respond(exchange)(WorkspaceService.store.list(session.tenantId))
        case (HttpMethod.Get, List(id)) => respond(exchange)(WorkspaceService.store.get(session.tenantId, id))
        case (HttpMethod.Get, List(id, "review")) => respond(exchange)(WorkspaceDraft.prepare(WorkspaceService.store.get(session.tenantId, id)).review)
        case (HttpMethod.Get, List(id, "files", file)) => download(exchange, session, id, file)
        case (HttpMethod.Post, Nil) => body[CreateDealRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.create(session, r)))
        case (HttpMethod.Post, List(id, "identity")) => body[BorrowerRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.borrower(session, id, r)))
        case (HttpMethod.Post, List(id, "documents")) => body[DocumentRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.attach(session, id, r)))
        case (HttpMethod.Post, List(id, "profile")) => body[CreditSetupRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.setup(session, id, r)))
        case (HttpMethod.Post, List(id, "corrections")) => body[DealCorrectionRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.correct(session, id, r)))
        case (HttpMethod.Post, List(id, "issues")) => body[IssueResolutionRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.resolve(session, id, r)))
        case (HttpMethod.Post, List(_, "policy" | "policy-clear")) if session.role != UserRole.Admin =>
          WebEndpoint.error(exchange, 403, "Administrator access required to change policy.")
        case (HttpMethod.Post, List(id, "policy-clear")) => body[DealActionRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.clearPolicy(session, id, r)))
        case (HttpMethod.Post, List(id, "policy")) => body[PolicyRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.policy(session, id, r)))
        case (HttpMethod.Post, List(id, "judgments")) => body[JudgmentRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.judgments(session, id, r)))
        case (HttpMethod.Post, List(id, "recommendation")) => body[RecommendationRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.recommendation(session, id, r)))
        case (HttpMethod.Post, List(id, "narratives")) => body[NarrativeRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.narrative(session, id, r)))
        case (HttpMethod.Post, List(id, "section-review")) => body[SectionReviewRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.reviewSection(session, id, r)))
        case (HttpMethod.Post, List(id, "submit")) => body[SubmissionRequest](exchange)(r => generate(exchange, session, WorkspaceService.workflow.submit(session, id, r)))
        case (HttpMethod.Post, List(id, "checks")) => body[StopReviewRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.reviewStops(session, id, r)))
        case (HttpMethod.Post, List(id, "approve")) => body[DealActionRequest](exchange)(r => respond(exchange)(WorkspaceService.workflow.approve(session, id, r)))
        case (HttpMethod.Post, List(id, "resume")) => body[DealActionRequest](exchange)(r => resume(exchange, session, id, r))
        case _ => WebEndpoint.error(exchange, 404, "Not found.")
      }
    }.handleError {
      case _: NoSuchElementException => WebEndpoint.error(exchange, 404, "Not found.")
      case e: WorkspaceConflict => WebEndpoint.error(exchange, 409, e.getMessage)
      case e: IllegalArgumentException => WebEndpoint.error(exchange, 400, e.getMessage.take(500))
      case cause =>
        scribe.error("Workspace request failed", cause)
        WebEndpoint.error(exchange, 500, "The workspace could not complete this request.")
    }
  }

  private def respond[A: RW](exchange: HttpExchange)(value: => A): Task[FilterResponse] =
    Task(value).flatMap(v => WebEndpoint.json(exchange, 200, v))

  private def body[A: RW](exchange: HttpExchange)(run: A => Task[FilterResponse]): Task[FilterResponse] = Task.defer {
    if (!writes.tryAcquire()) WebEndpoint.error(exchange, 429, "The workspace is busy. Try again shortly.")
    else (exchange.request.content match {
    case None => WebEndpoint.error(exchange, 400, "Request body required.")
    case Some(content) => content.asStream.take(7 * 1024 * 1024 + 1).toList.flatMap { bytes =>
      if (bytes.size > 7 * 1024 * 1024) WebEndpoint.error(exchange, 413, "Request too large.")
      else Try(JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[A]).toOption match {
        case None => WebEndpoint.error(exchange, 400, "Invalid request format.")
        case Some(value) => run(value)
      }
      }
    }).guarantee(Task(writes.release()).unit)
  }

  private def resume(exchange: HttpExchange, session: SessionView, id: String, request: DealActionRequest): Task[FilterResponse] =
    generate(exchange, session, WorkspaceService.workflow.claim(session, id, request))

  private def generate(exchange: HttpExchange, session: SessionView, claim: => WorkspaceDeal): Task[FilterResponse] = Task.defer {
    if (!jobs.tryAcquire()) WebEndpoint.error(exchange, 429, "A memo is being generated. Try again shortly.")
    else Task(claim).attempt.flatMap {
      case Failure(error) => jobs.release(); Task.error(error)
      case Success(claimed) =>
        var active = claimed
        Task {
          val files = WorkspaceMemo.generate(session, claimed, progress = p => active = WorkspaceService.workflow.generationProgress(session, active, p))
          val pdf = files.find(_._1.contentType == "application/pdf").getOrElse(throw new IllegalStateException("PDF missing"))
          WorkspaceService.workflow.complete(session, active, files, pdf._1.id)
        }.unit.handleError { cause => Task {
          scribe.error("Memo generation failed", cause)
          try WorkspaceService.workflow.fail(session, active)
          catch { case conflict: WorkspaceConflict => scribe.warn("Generation lease superseded; newer revision retained", conflict) }
          ()
        }}.guarantee(Task(jobs.release()).unit).start.flatMap(_ => WebEndpoint.json(exchange, 202, claimed))
    }
  }

  private def download(exchange: HttpExchange, session: SessionView, id: String, file: String): Task[FilterResponse] =
    Task(WorkspaceService.store.file(session.tenantId, id, file)).flatMap { (metadata, bytes) =>
      val name = metadata.name.split('/').last.replaceAll("[^A-Za-z0-9._-]", "_")
      exchange.withContent(Content.bytes(bytes, ContentType.parse(metadata.contentType))).flatMap(_.modify { response =>
        Task.pure(response.withStatus(HttpStatus.OK).withHeader("Cache-Control", "no-store")
          .withHeader("Content-Disposition", s"attachment; filename=\"$name\"")
          .withHeader("X-Content-Type-Options", "nosniff"))
      }).map(stop)
    }
}
