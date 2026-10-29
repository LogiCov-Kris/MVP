package logicov.memo

/** A generated narrative: figures not in the record (`unverified`), and figures found only in the discovery transcript. */
case class NarrativeText(text: String, unverified: List[String], fromTranscript: List[String] = Nil)
