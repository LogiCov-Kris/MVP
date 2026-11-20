# Policy and analyst review

Financial definitions, ratios, covenant arithmetic and risk ratings are deterministic. Institution policy is a separate versioned rule set configured once per workspace under **Admin → Underwriting policy**. Analysts do not select policy on each deal.

Supported policy metrics are net leverage, interest coverage, liquidity cover, debt to capitalization, composite rating, net debt and EBITDA. Each rule records its source, comparison and threshold. Breaches and unavailable metrics require an approval explanation. No institution-specific limits are invented; Golden Source guidance applies without an institutional overlay until one is configured. Legacy deal-specific policies remain effective until an administrator configures the workspace policy.

Approval snapshots the applicable policy and its workspace revision. A later policy change requires renewed input approval before generation. Existing artifacts remain historical records. Policy changes are tenant-isolated, encrypted, revision-checked and audited.

Routine bank-side onboarding and screening are outside this underwriting workflow. Missing screening attestations do not block a memo, and the application does not claim these checks have been completed. Previously recorded Stop conditions remain visible and block approval or generation until resolved with evidence. Extraction discrepancies, unsupported methodology and unreviewed rating overrides still prevent approval. Committee submission still requires review of every memo section and an explicit analyst recommendation.

Public-company financial inputs and supported facility disclosures retain citations. Unadjusted EBITDA suggestions identify their calculation and must be reviewed for the appropriate basis. Partial credit-profile drafts may be saved without approval. Unsupported values, facility choices and subjective judgments are never silently filled with zero or treated as approved.

Arbitrary policy-document conversion is not provided. Administrators enter supported numerical rules in the reviewed form; additional concepts require explicit definitions and implementation.
