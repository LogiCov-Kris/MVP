package logicov.source

import fabric.rw.*

enum Source derives RW {
  case Xbrl(tag: String, form: String, accession: String)
  case CallScript(field: String)
  case Filing(document: String)
  case Exhibit(form: String, accession: String, document: String, item: String)
  case Derived(formula: String)
  case Upload(document: String, sha256: String, location: String, excerpt: String)

  def filing: Option[String] = this match {
    case Xbrl(_, _, accession) => Some(accession)
    case Exhibit(_, accession, _, _) => Some(accession)
    case _ => None
  }

  def describe: String = this match {
    case Xbrl(tag, form, accession) => s"$form $accession, XBRL $tag"
    case CallScript(field) => s"Discovery call: $field"
    case Filing(document) => document
    case Exhibit(form, accession, document, item) => s"$form $accession, $document: $item"
    case Derived(formula) => s"Calculated: $formula"
    case Upload(document, sha256, location, excerpt) => s"$document, $location: $excerpt (SHA-256 $sha256)"
  }
}
