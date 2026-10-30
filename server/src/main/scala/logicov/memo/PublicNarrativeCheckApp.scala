package logicov.memo

import logicov.ai.LogiCovSigil
import logicov.config.LogiCovConfig
import spice.http.client.HttpClient

object PublicNarrativeCheckApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Supply a public SEC CIK")
    val cik = args(0).toLong
    require(cik > 0, "A positive SEC CIK is required")
    require(sys.env.get("LOGICOV_PROVIDER_APPROVED").contains("true"), "Provider approval is required")
    LogiCovConfig.load()
    LogiCovSigil.instance.sync()
    try {
      val drafts = PublicNarratives.generate(cik, Set(PublicNarratives.Industry)).sync()
      require(drafts.contains(PublicNarratives.Industry), "Public narrative was not generated")
      val draft = drafts(PublicNarratives.Industry)
      require(draft.publicSources.nonEmpty && draft.promptHash.nonEmpty, "Public evidence was not retained")
      println(s"Public narrative verified: ${draft.publicSources.size} public source, prompt ${draft.promptHash}")
    } finally {
      try LogiCovSigil.shutdown.sync()
      finally HttpClient.dispose().sync()
    }
  }
}
