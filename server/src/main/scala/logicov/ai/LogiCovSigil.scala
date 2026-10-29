package logicov.ai

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.{DefaultSigilDB, Model}
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.{GenerationSettings, ModelResolver, OneShotRequest, OutputTokenCap, Provider, ProviderEvent, StopReason}
import sigil.storage.FileStorage

object LogiCovSigil extends Sigil {
  override type DB = DefaultSigilDB

  /** The provider/model id for narratives, from LOGICOV_NARRATIVE_MODEL, so the choice of model is deployment configuration. */
  lazy val NarrativeModel: Id[Model] = Model.id(sys.env.getOrElse("LOGICOV_NARRATIVE_MODEL",
    throw new IllegalStateException("Set LOGICOV_NARRATIVE_MODEL to the provider/model id used for narratives")))

  private val NarrativeTokenCap = 16000
  private val TextBlock = "Text"

  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new DefaultSigilDB(directory, storeManager, appUpgrades)

  override def fileStorage: FileStorage = FileStorage.Disabled

  private lazy val anthropic: Provider = AnthropicProvider.create(this, apiKey).sync()

  override lazy val modelResolver: ModelResolver = anthropic

  def complete(system: String, user: String): Task[String] = Task.defer {
    val model = cache.find(NarrativeModel).getOrElse(throw new IllegalStateException(s"$NarrativeModel is not in the model registry"))
    val settings = GenerationSettings(outputTokenCap = OutputTokenCap.Below(NarrativeTokenCap))
    val text = new java.lang.StringBuilder
    val textBlocks = scala.collection.mutable.Set.empty[sigil.provider.CallId]
    anthropic(OneShotRequest(model, system, user, generationSettings = settings)).evalMap {
      case ProviderEvent.ContentBlockStart(id, TextBlock, _) => Task(textBlocks += id).unit
      case ProviderEvent.ContentBlockDelta(id, t) if textBlocks.contains(id) => Task(text.append(t)).unit
      case ProviderEvent.TextDelta(t) => Task(text.append(t)).unit
      case ProviderEvent.Done(StopReason.MaxTokens) => Task.error(new IllegalStateException(s"Narrative truncated at $NarrativeTokenCap tokens"))
      case ProviderEvent.Error(message) => Task.error(new IllegalStateException(s"Narrative request failed: $message"))
      case _ => Task.unit
    }.drain.map { _ =>
      val reply = text.toString.trim
      if (reply.isEmpty) throw new IllegalStateException(s"$NarrativeModel returned no text")
      reply
    }
  }

  private def apiKey: String = sys.env.getOrElse("ANTHROPIC_API_KEY", throw new IllegalStateException("ANTHROPIC_API_KEY is not set"))
}
