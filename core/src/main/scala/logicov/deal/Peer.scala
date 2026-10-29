package logicov.deal

import fabric.rw.*
import logicov.grid.GoldenGrid

import logicov.analysis.AgencyRating

/** A peer company: its grid, the analyst's ratings entry (with source), and ratings from retained company filings. */
case class Peer(ticker: String,
                cik: Long,
                grid: GoldenGrid,
                ratings: String,
                ratingsSource: Option[String] = None,
                disclosedRatings: List[AgencyRating] = Nil) derives RW
