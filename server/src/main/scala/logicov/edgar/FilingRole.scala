package logicov.edgar

import fabric.rw.*

enum FilingRole {
  case AnnualReport, AnnualXbrl, AnnualLabels, InterimXbrl, InterimLabels, EarningsRelease, Proxy, PeerAnnualXbrl, PeerAnnualReport, PeerRatingsOffering
}

object FilingRole {
  given RW[FilingRole] = RW.enumeration(values.toList, _.toString)
}
