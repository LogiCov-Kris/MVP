package logicov.edgar

import fabric.rw.*

case class SourceManifest(cik: Long, filings: List[PackFiling]) derives RW {
  def one(role: FilingRole): PackFiling = filings.find(_.role == role).getOrElse(throw new NoSuchElementException(s"Source pack has no $role"))

  def all(role: FilingRole): List[PackFiling] = filings.filter(_.role == role)
}
