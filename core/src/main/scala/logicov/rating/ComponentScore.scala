package logicov.rating

case class ComponentScore(component: RatingComponent, score: Int, basis: String) {
  def weighted: BigDecimal = component.weight * score
}
