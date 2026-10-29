package logicov.rating

case class RatingResult(components: List[ComponentScore],
                        composite: BigDecimal,
                        machineGrade: Grade,
                        ratingOverride: Option[RatingOverride],
                        exposure: BigDecimal,
                        lgd: BigDecimal,
                        inputs: RatingInputs) {
  def grade: Grade = ratingOverride.fold(machineGrade)(o => MasterScale.adjust(machineGrade, o.notches))

  def expectedLoss: BigDecimal = exposure * grade.pd * lgd

  def notchesVersus(agencyRating: String): Int = MasterScale.notch(grade) - MasterScale.notch(agencyRating)
}
