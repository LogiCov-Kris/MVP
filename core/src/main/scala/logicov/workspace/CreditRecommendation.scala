package logicov.workspace

import fabric.rw.*

enum CreditRecommendation(val label: String) derives RW {
  case Pending extends CreditRecommendation("Pending analyst recommendation")
  case Approve extends CreditRecommendation("Recommended for approval")
  case Conditional extends CreditRecommendation("Recommended for approval with conditions")
  case Defer extends CreditRecommendation("Defer pending further review")
  case Decline extends CreditRecommendation("Recommended to decline")
}
