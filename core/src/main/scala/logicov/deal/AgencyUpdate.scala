package logicov.deal

import fabric.rw.*

/**
 * An agency's outlook or rating date that the discovery call states outside its facts table, entered by the analyst
 * from the transcript or source pack and cited as `source`.
 */
case class AgencyUpdate(house: String, outlook: Option[String], asOf: Option[String], source: String) derives RW
