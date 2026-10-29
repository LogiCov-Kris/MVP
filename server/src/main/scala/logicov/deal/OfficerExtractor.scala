package logicov.deal

import fabric.io.JsonParser
import logicov.ai.LogiCovSigil
import logicov.source.Source
import rapid.Task

/**
 * Reads the executive officers from a located filing section. The model selects; code accepts an officer only when the
 * name, title and background sentence appear verbatim in the section and the age appears beside the name.
 */
object OfficerExtractor {
  private val System =
    """You extract executive officers from an SEC filing excerpt. Return only a JSON array, no prose.
      |Each element: {"name": ..., "title": ..., "age": number or null, "background": ...}.
      |Copy name and title exactly as written in the excerpt. For background, copy one sentence verbatim from the excerpt
      |that best summarizes the person's experience (prior roles, tenure or education); never paraphrase. If the excerpt
      |has no biography for a person, use an empty string for background.""".stripMargin

  def extract(section: String, form: String, accession: String): Task[List[ExecutiveOfficer]] =
    LogiCovSigil.complete(System, section).map { reply =>
      val json = JsonParser(reply.substring(reply.indexOf('['), reply.lastIndexOf(']') + 1))
      val source = Source.Exhibit(form, accession, "executive officers", "names, titles, ages and backgrounds as written")
      json.asVector.toList.map { o =>
        ExecutiveOfficer(o("name").asString, o("title").asString, o.get("age").filterNot(_.isNull).map(_.asInt),
          o("background").asString, source)
      }.filter(verified(section, _))
    }

  def verified(section: String, officer: ExecutiveOfficer): Boolean = {
    val text = normalize(section)
    val at = text.indexOf(normalize(officer.name))
    at >= 0 && text.contains(normalize(officer.title)) && text.contains(normalize(officer.background)) &&
      officer.age.forall(age => text.slice(at, at + officer.name.length + 400).contains(age.toString))
  }

  private def normalize(text: String): String = text.replace('’', '\'').replaceAll("\\s+", " ").trim
}
