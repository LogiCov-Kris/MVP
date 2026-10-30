package logicov.workspace

import fabric.rw.*

enum ReviewTopic(val label: String) derives RW {
  case Sanctions extends ReviewTopic("Sanctions screening")
  case GoingConcern extends ReviewTopic("Going-concern opinion")
  case FinancialCurrency extends ReviewTopic("Financial statement currency")
  case BorrowerIdentity extends ReviewTopic("Borrower and holding-company identity")
  case UseOfProceeds extends ReviewTopic("Dividend use of proceeds")
}
