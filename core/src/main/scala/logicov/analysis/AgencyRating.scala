package logicov.analysis

import fabric.rw.*
import logicov.source.Source

case class AgencyRating(house: String, rating: String, outlook: String, asOf: String, source: Source) derives RW
