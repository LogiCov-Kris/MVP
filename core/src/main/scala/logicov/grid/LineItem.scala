package logicov.grid

import fabric.rw.RW

enum LineItem(val flow: Boolean, val tags: List[XbrlTag]) {
  case CostOfRevenue extends LineItem(true, List(XbrlTag("CostOfRevenue"), XbrlTag("CostOfGoodsAndServicesSold")))
  case OperatingIncome extends LineItem(true, List(XbrlTag("OperatingIncomeLoss")))
  case PretaxIncome extends LineItem(true, List(XbrlTag("IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"), XbrlTag("IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments")))
  case IncomeTax extends LineItem(true, List(XbrlTag("IncomeTaxExpenseBenefit")))
  case ContinuingIncome extends LineItem(true, List(XbrlTag("IncomeLossFromContinuingOperations")))
  case NetIncomeConsolidated extends LineItem(true, List(XbrlTag("ProfitLoss")))
  case CommonIncome extends LineItem(true, List(XbrlTag("NetIncomeLossAvailableToCommonStockholdersBasic")))
  case Depreciation extends LineItem(true, List(XbrlTag("DepreciationDepletionAndAmortization"), XbrlTag("DepreciationDepletionAndAmortizationPropertyPlantAndEquipment")))
  case Receivables extends LineItem(false, List(XbrlTag("AccountsReceivableNetCurrent"), XbrlTag("AccountsNotesAndOtherReceivablesNetCurrent"), XbrlTag("ReceivablesNetCurrent")))
  case Inventory extends LineItem(false, List(XbrlTag("InventoryNet")))
  case CurrentAssets extends LineItem(false, List(XbrlTag("AssetsCurrent")))
  case PropertyPlantEquipment extends LineItem(false, List(XbrlTag("PropertyPlantAndEquipmentNet")))
  case Goodwill extends LineItem(false, List(XbrlTag("Goodwill")))
  case OtherNoncurrentAssets extends LineItem(false, List(XbrlTag("OtherAssetsNoncurrent")))
  case CurrentLiabilities extends LineItem(false, List(XbrlTag("LiabilitiesCurrent")))
  case AccountsPayable extends LineItem(false, List(XbrlTag("AccountsPayableCurrent")))
  case DeferredTaxes extends LineItem(false, List(XbrlTag("DeferredIncomeTaxLiabilitiesNet"), XbrlTag("DeferredTaxLiabilitiesNoncurrent")))
  case OtherNoncurrentLiabilities extends LineItem(false, List(XbrlTag("OtherLiabilitiesNoncurrent")))
  case TotalLiabilities extends LineItem(false, List(XbrlTag("Liabilities"), XbrlTag("Assets", less = List("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"))))
  case PreferredEquity extends LineItem(false, List(XbrlTag("PreferredStockValue")))
  case NoncontrollingInterest extends LineItem(false, List(XbrlTag("MinorityInterest")))
  case InvestingCashFlow extends LineItem(true, List(XbrlTag("NetCashProvidedByUsedInInvestingActivities")))
  case FinancingCashFlow extends LineItem(true, List(XbrlTag("NetCashProvidedByUsedInFinancingActivities")))
  case AvailableForSaleSecurities extends LineItem(false, List(XbrlTag("AvailableForSaleSecuritiesCurrent"), XbrlTag("AvailableForSaleSecuritiesDebtSecuritiesCurrent")))
  case Revenue extends LineItem(true, List(XbrlTag("Revenues"), XbrlTag("RevenueFromContractWithCustomerExcludingAssessedTax")))
  case NetIncomeAttributable extends LineItem(true, List(
    XbrlTag("NetIncomeLoss"),
    XbrlTag("ProfitLoss", less = List("NetIncomeLossAttributableToNoncontrollingInterest")),
    XbrlTag("ProfitLoss")
  ))
  case InterestExpense extends LineItem(true, List(
    XbrlTag("InterestExpense"),
    XbrlTag("InterestExpenseNonoperating"),
    XbrlTag("InterestAndDebtExpense"),
    XbrlTag("InterestIncomeExpenseNonoperatingNet", negate = true)
  ))
  case ContinuingOperatingCashFlow extends LineItem(true, List(XbrlTag("NetCashProvidedByUsedInOperatingActivitiesContinuingOperations")))
  case DiscontinuedOperatingCashFlow extends LineItem(true, List(XbrlTag("CashProvidedByUsedInOperatingActivitiesDiscontinuedOperations")))
  case OperatingCashFlow extends LineItem(true, List(XbrlTag("NetCashProvidedByUsedInOperatingActivities")))
  case CashCapex extends LineItem(true, List(
    XbrlTag("PaymentsToAcquirePropertyPlantAndEquipment"),
    XbrlTag("PaymentsToAcquireOilAndGasProperty"),
    XbrlTag("PaymentsToAcquireProductiveAssets")
  ))
  case DividendsPaid extends LineItem(true, List(
    XbrlTag("PaymentsOfDividendsCommonStock"),
    XbrlTag("PaymentsOfDividends"),
    XbrlTag("PaymentsOfOrdinaryDividends")
  ))
  case Cash extends LineItem(false, List(
    XbrlTag("CashAndCashEquivalentsAtCarryingValue"),
    XbrlTag("CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents", less = List("RestrictedCashCurrent", "RestrictedCashNoncurrent")),
    XbrlTag("CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents", less = List("RestrictedCashCurrent")),
    XbrlTag("CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents")
  ))
  case CurrentDebt extends LineItem(false, List(
    XbrlTag("DebtCurrent"),
    XbrlTag("LongTermDebtCurrent"),
    XbrlTag("LongTermDebtAndCapitalLeaseObligationsCurrent")
  ))
  case LongTermDebtCarrying extends LineItem(false, List(XbrlTag("LongTermDebtNoncurrent"), XbrlTag("LongTermDebtAndCapitalLeaseObligations")))
  case DebtPrincipal extends LineItem(false, List(XbrlTag("DebtInstrumentFaceAmount"), XbrlTag("DebtInstrumentCarryingAmount")))
  case FinanceLeases extends LineItem(false, List(
    XbrlTag("FinanceLeaseLiability"),
    XbrlTag("FinanceLeaseLiabilityCurrent", plus = List("FinanceLeaseLiabilityNoncurrent"))
  ))
  case TotalAssets extends LineItem(false, List(XbrlTag("Assets")))
  case TotalEquity extends LineItem(false, List(
    XbrlTag("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
    XbrlTag("StockholdersEquity")
  ))
  case ReportedTotalDebt extends LineItem(false, List(XbrlTag("DebtLongtermAndShorttermCombinedAmount")))
}

object LineItem {
  given RW[LineItem] = RW.enumeration(values.toList, _.toString)
}
