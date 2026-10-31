# Analyst guide

## Sign in and appearance

Use the Email field to request a sign-in link, or choose the password option. Delivery may take a few minutes; check spam before requesting another link. Forgot password starts the reset process. Access is invitation-only.

Appearance follows the device by default. Choose Light, Dark or Device theme to change it. The preference is saved in this browser. Exported PDFs keep their print layout.

## Prepare a credit file

1. Select **New Credit File**, enter the borrower legal name and choose a statement. PDF, CSV, XLS, XLSX and supported standalone XBRL/XML are accepted. Scanned documents require OCR, which is outside the supported workflow.
2. Check the statement years and amount units before choosing **Ingest & Analyze**. Advanced settings allow explicit column/label mapping and sign handling. Missing values remain missing; they are not silently replaced with zero.
3. In **Data Verification**, inspect each financial line and its citation. Enter a corrected amount and a reason when needed. Corrections retain the original source and record who changed the value.
4. Resolve extraction issues. In **Judgments**, attach supporting documents and complete the sourced credit profile when the statement alone does not establish facility terms, EBITDA basis or other credit inputs. Review sensitivities and any rating override.
5. An administrator configures applicable institution policy in **Policy**. A missing policy, unavailable metric or breach is visible and requires a documented exception where permitted. The demonstration policy is not an institution's approved policy.

The public-company onboarding button is intentionally unavailable for this pilot. Existing reference packages can be loaded through the documented operator workflow. Supported upload layouts and limits are described in [IMPORTS.md](IMPORTS.md).

## Review and submit the memo

1. In **Memo**, complete the required analyst checks with evidence: sanctions, going concern, financial currency, borrower identity and use of proceeds. These are human attestations, not automated screening. Pending or unresolved checks prevent generation.
2. Approve the reviewed inputs, recording an explanation where required, then generate the memo. Progress reflects actual processing. A failed generation retains the approved input snapshot for retry.
3. Use the left-hand navigation to read the continuous memo. Follow citations to the source appendix. Edit prose inline with a reason. Shared-value chips and shared narrative references update from the underlying record; arbitrary literal sentences do not automatically propagate.
4. Relevant edits invalidate prior approval, generated output and section review. Regenerate and review the updated document before submitting.
5. Mark every section reviewed. Only then choose Approve, Conditional or Decline, explain the recommendation, and select **Submit to Committee**.
6. The status becomes **Submitted for Approval** after the final PDF is generated. This records submission, not committee approval or voting. Download the PDF, source audit or review ZIP as needed.

Current generated drafts use public SEC evidence for industry and leadership only. Other prose is deterministic or analyst-authored. Private uploads and analyst notes are not sent to the narrative provider. Production provider activation remains subject to the approved data-retention arrangement.

## Administration and support

**Admin** opens a separate administration section. Workspace administrators manage users in their workspace. Platform administrators also manage customer workspaces. **Exit Admin** returns to credit work. Details are in [USERS.md](USERS.md).

If a save reports a revision conflict, reload the current record before applying the change again. If generation fails, retain the deal identifier and failure time for support. Do not repeatedly submit the same action or create a replacement deal merely to bypass an unresolved review issue.
