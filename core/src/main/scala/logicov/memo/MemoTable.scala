package logicov.memo

case class MemoTable(title: String, headers: List[String], rows: List[List[String]], note: Option[String] = None,
                     links: Map[(Int, Int), String] = Map.empty,
                     rowIds: Map[Int, String] = Map.empty,
                     boldCells: Set[(Int, Int)] = Set.empty)
