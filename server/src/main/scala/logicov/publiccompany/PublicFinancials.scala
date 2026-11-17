package logicov.publiccompany

import fabric.Json
import logicov.edgar.CompanyFacts
import logicov.grid.{GoldenGrid, GridBuilder, Period}

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object PublicFinancials {
  def grid(raw: Json, annualEnd: LocalDate): GoldenGrid = {
    require(raw("facts").get("us-gaap").nonEmpty, "This issuer does not supply supported US GAAP facts. A reviewed statement import is required.")
    val facts = CompanyFacts.parse(raw)
    val durations = facts.facts.filter(f => f.start.exists(s => {
      val days = ChronoUnit.DAYS.between(s, f.end)
      days >= 330 && days <= 380
    }) && f.end.compareTo(annualEnd) <= 0 && Set("10-K", "10-K/A").contains(f.form))
      .groupBy(_.end.getYear).toList.sortBy(-_._1).take(3).sortBy(_._1).map { (year, values) =>
        val dates = values.groupBy(f => f.start -> f.end).toList.sortBy { (dates, entries) =>
          (-entries.map(_.tag).distinct.size, -dates._2.toEpochDay)
        }.head._1
        Period(s"FY$year", dates._1, dates._2)
      }
    require(durations.nonEmpty, "No annual USD financial periods were found in the retained SEC facts.")
    val interim = facts.facts.filter(f => f.form == "10-Q" && f.end.isAfter(annualEnd)).map(_.end).maxOption.toList.flatMap { end =>
      val starts = facts.facts.filter(f => f.form == "10-Q" && f.end == end).flatMap(_.start).filter(_.isAfter(annualEnd))
      starts.minOption.map(start => Period.interim(s"YTD ${end.toString}", start, end))
    }
    val periods = (durations ++ interim).flatMap { period =>
      val balance = if (period.end.getMonthValue == 12 && period.end.getDayOfMonth == 31) Period.yearEnd(period.end.getYear)
        else Period.balance(period.end.toString, period.end)
      List(period, balance)
    }
    val grid = GridBuilder.fromXbrl(facts, periods)
    require(grid.values.nonEmpty, "No supported USD financial values were found. Retain this issuer for methodology review rather than creating an empty credit file.")
    grid
  }
}
