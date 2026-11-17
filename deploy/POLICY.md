# Policy and analyst review

LogiCov applies its financial definitions and risk-rating methodology in deterministic code. Institution policy is a separate, versioned rule set entered in the Policy screen. Each rule records its source, comparison and threshold. Supported metrics are net leverage, interest coverage, liquidity cover, debt to capitalization, composite rating, net debt and EBITDA.

| Requirement | Application behavior |
|---|---|
| Financial spreads, ratios, covenant arithmetic and risk rating | Calculated from the cited record; model output cannot change the calculations |
| Missing or inconsistent extracted values | Review issues require a documented analyst disposition before approval |
| Rating override | Explicit analyst review, one-notch limit, retained explanation and approval invalidation after relevant edits |
| Institution policy | Pass, breach, unavailable or unconfigured results; exceptions require an explanation and appear in the memo |
| Sanctions screening | Required analyst clearance with evidence; unresolved findings stop approval and generation |
| Going-concern opinion | Required analyst review of the applicable opinion; unresolved concerns stop approval and generation |
| Financial-statement currency | Required analyst review against the applicable freshness requirement; no universal age threshold is assumed |
| Borrower and holding-company identity | Required identification and source evidence before approval |
| Dividend use of proceeds | Required review and documented resolution before approval |
| Final credit recommendation | Explicit analyst decision and rationale, separate from approving the financial inputs |
| Narrative drafting | Hosted drafts use independently retrieved public SEC evidence only; private inputs remain within the workspace |

The required checks are human attestations, not automated sanctions searches or legal/compliance determinations. Pending and Stop checks cannot be bypassed by a financial-policy exception. Clearing a finding requires the analyst's evidence and is audited. Source changes invalidate relevant reviews and require renewed approval. Previously issued artifacts remain historical records.

No institution policy or numeric limits are invented. Automatic conversion of arbitrary policy documents into executable rules is not currently provided. Analysts enter supported numerical rules through the reviewed form; qualitative stop conditions use the required checks. Additional policy concepts need explicit definitions and implementation before they can be evaluated automatically.

## Golden Source baseline

For a pilot without an institution-specific playbook, an administrator can explicitly select **Use Golden Source only** and record the reason. The deterministic financial and rating methodology and required analyst checks remain in effect. The memo reports that no institution overlay applies, rather than claiming that an institution's rules passed. Selecting the baseline clears any prior overlay and invalidates approval and generated output. Removing a policy without choosing the baseline returns the deal to an unconfigured policy state requiring review. Existing deals are not silently converted.
