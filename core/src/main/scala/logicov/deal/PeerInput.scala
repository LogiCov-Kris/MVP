package logicov.deal

import fabric.rw.*

/** A peer company; `cik` pins the SEC registrant when the ticker alone is ambiguous, as after a holding-company reorganization. */
case class PeerInput(ticker: String, ratings: String, cik: Option[Long] = None, ratingsSource: Option[String] = None) derives RW
