# Financial imports and correction review

Sign in, enter the borrower and calendar years in the order shown in the statement, confirm the USD units, choose a file and select **Preview Golden Grid**. Review the extracted amounts and issues, then download the cited grid. Imports are temporary previews: files and results are not saved to the server or shared with other users. Keep the original document with the downloaded result. Persistent deal storage and approval are separate workflow features.

## Supported layouts

- CSV: UTF-8, comma-delimited, with the line-item label in column 1. Quote amounts containing commas. Optional first-row label `Account`, `Description` or `Line item`; selected headers are checked against the configured periods.
- XLS and XLSX: line-item labels in column A and selected numeric columns to the right. All sheets are read. Citations retain the sheet and cell. Formulas are evaluated without fetching external workbooks; failures are review issues, never replaced by cached values.
- PDF: unencrypted text-layer financial tables with one complete labelled row per text line and amounts ordered left to right after the label. Each extracted value retains its page, text-line location and excerpt. Select only statement pages through advanced settings when needed. Wrapped rows, footnote-number columns and unusual table arrangements require review or a normalized spreadsheet. Scanned PDFs require OCR, which is outside this import path.

The limits are 5 MB per file, 2,000 rows total, 40 columns, 20 workbook sheets, 100 PDF pages and 10 selected periods. These are supported-layout imports, not a claim of automatic coverage for every financial document. Private-company sample statements are still needed to validate their actual layouts.

All grid amounts are USD millions. Currency is explicitly restricted to USD; foreign-currency translation is not performed. Choose Dollars, Thousands or Millions according to the source. Values preserve their reported sign unless advanced settings explicitly reverse a named line item. For example, cash capex and dividends paid are positive outflow magnitudes in the calculation model, so a statement showing them as negatives requires `negate`. Operating, investing and financing cash flows retain their signed amounts.

Unknown labels, missing values, formula failures, duplicate cells and balance-sheet reconciliation differences remain visible as issues. A dash is missing, not zero. A grid preview is not approval, and an incomplete grid cannot establish complete credit ratios. Ratios also require the sourced deal profile, EBITDA basis and actual facility covenant.

## Advanced settings

Open **Advanced options** and select **Download settings template**, or use:

```json
{
  "entity": "Example Company",
  "unit": "Thousands",
  "columns": [
    {"column": 2, "label": "FY2025", "start": "2025-01-01", "end": "2025-12-31"}
  ],
  "mappings": {"Net income": "NetIncomeConsolidated"},
  "pdfPages": [2, 3],
  "negate": ["CashCapex", "DividendsPaid"]
}
```

Column numbers start at 1, including the label column. PDF numeric columns use the same convention. Instant balance-sheet values use the period end; income and cash-flow values use both dates. Explicit label mappings resolve ambiguity without guessing whether a net-income figure is consolidated or attributable. Loaded advanced settings replace and lock the basic form. Select **Use form settings** to return to the basic form.

For a repeatable local import:

```bash
sbt --server -batch "server/runMain logicov.ingest.ImportApp statement.xls options.json new-import-directory"
```

The new directory contains the original source, settings, cited grid, SHA-256 document identity and review issues. The command refuses an existing output directory.

## Corrections and recalculation

`CorrectionApp` applies an explicit list of grid corrections to a copy of a complete deal record. Each correction identifies `item`, `period`, `expected` original value, new `value`, complete `source`, `reason`, `analyst` and ISO UTC `recordedAt`. The expected-value check prevents accidentally replaying an old correction. Values remain USD millions.

```bash
sbt --server -batch "server/runMain logicov.ingest.CorrectionApp deal.json corrections.json new-correction-directory"
sbt --server -batch "server/runMain logicov.ingest.AnalysisApp new-correction-directory/deal.json new-analysis.json"
```

The output preserves the original record, revision history and citations, recalculates ratios and covenant results, and reports balance-sheet issues. Corrections to simple annual EBITDA spread components update the annual EBITDA basis. Ambiguous composite components require rebuilding the source pack instead of guessing the dependency. Independently sourced LTM EBITDA, segment data and debt schedules retain their own source records. Review those records if the correction changes their underlying evidence. An existing risk-rating override is retained and flagged for review if the calculated rating changes.

Regenerate a memo from the corrected record after review. Existing PDFs and source documents are never overwritten by these commands. Corrections are developer-assisted at this stage; persistent analyst editing and approval remain separate workflow work.
