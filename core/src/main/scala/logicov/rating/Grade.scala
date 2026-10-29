package logicov.rating

case class Grade(label: String, sp: String, moodys: String, pd: BigDecimal, compositeFrom: BigDecimal) {
  def mapping: String = s"$label ($sp / $moodys)"
}
