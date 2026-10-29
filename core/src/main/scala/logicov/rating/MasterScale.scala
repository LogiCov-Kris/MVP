package logicov.rating

object MasterScale {
  val grades: List[Grade] = List(
    Grade("1", "AAA", "Aaa", 0.0002, 1.00),
    Grade("2+", "AA+", "Aa1", 0.00025, 1.50),
    Grade("2", "AA", "Aa2", 0.0003, 1.84),
    Grade("2-", "AA-", "Aa3", 0.0004, 2.17),
    Grade("3+", "A+", "A1", 0.0005, 2.50),
    Grade("3", "A", "A2", 0.0006, 2.84),
    Grade("3-", "A-", "A3", 0.0008, 3.17),
    Grade("4+", "BBB+", "Baa1", 0.0010, 3.50),
    Grade("4", "BBB", "Baa2", 0.0014, 3.84),
    Grade("4-", "BBB-", "Baa3", 0.0020, 4.17),
    Grade("5+", "BB+", "Ba1", 0.0050, 4.50),
    Grade("5", "BB", "Ba2", 0.0072, 4.84),
    Grade("5-", "BB-", "Ba3", 0.0104, 5.17),
    Grade("6+", "B+", "B1", 0.0150, 5.50),
    Grade("6", "B", "B2", 0.0208, 5.84),
    Grade("6-", "B-", "B3", 0.0289, 6.17),
    Grade("7+", "CCC+", "Caa1", 0.0400, 6.50),
    Grade("7", "CCC", "Caa2", 0.0543, 6.84),
    Grade("7-", "CCC-", "Caa3", 0.0737, 7.17),
    Grade("8", "CC", "Ca", 0.10, 7.50),
    Grade("9", "C", "C", 0.20, 8.50)
  )

  def fromComposite(composite: BigDecimal): Grade = grades.filter(_.compositeFrom <= composite).last

  def notch(grade: Grade): Int = grades.indexOf(grade)

  def adjust(grade: Grade, notchesStronger: Int): Grade = grades(notch(grade) - notchesStronger)

  def notch(agencyRating: String): Int = grades.indexWhere(g => g.sp == agencyRating || g.moodys == agencyRating) match {
    case -1 => throw new IllegalArgumentException(s"Unknown agency rating '$agencyRating'")
    case index => index
  }
}
