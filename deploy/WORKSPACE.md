# Saved workspace operations

The workspace persists original statements, cited grids, complete credit records, review decisions and generated artifacts in PostgreSQL. Every payload is encrypted with a separate tenant key. Tenant keys are wrapped with an application master key. Each workspace transaction switches to a non-bypass role and sets a transaction-local tenant identifier; forced row-level security applies to all three workspace tables.

## Local startup

Use the same PostgreSQL settings as the authentication service. Set `LOGICOV_MASTER_KEY` to a base64-encoded 32-byte random key, or persist the local file selected by `LOGICOV_MASTER_KEY_FILE` (default `.logicov/master.key`). Production requires the environment secret and never creates a local replacement. Keep the key across restarts. Loss of the key makes retained documents and records unreadable.

Set `LOGICOV_PUBLIC_URL` to the application origin so exported PDFs link back to authenticated evidence. Local development accepts a loopback HTTP origin. Production requires HTTPS. Set `LOGICOV_CHROMIUM` when the browser executable differs from the container default `/usr/local/bin/chromium`.

Schema initialization runs locally on startup under a migration lock. Production startup verifies the installed schema and role, then refuses to start if required isolation is absent.

## First GCP deployment

1. Apply the infrastructure foundation with an empty image. Protect the Terraform state bucket: it contains database credentials and the master key. Secret Manager access is limited to the runtime service account.
2. Build and publish the reviewed image. Set `image` to its immutable digest and `public_url` to the final HTTPS origin. If the hostname is not yet known, leave `public_url` empty for the initial service bootstrap; memo generation requires a configured origin.
3. Create the migration job before the web service: `terraform apply -target=google_cloud_run_v2_job.migrate`. Review the plan first. This is a deliberate first-deployment bootstrap step.
4. Execute the job with `gcloud run jobs execute logicov-dev-migrate --region=us-central1 --project=PROJECT_ID --wait` (substitute the selected environment and region).
5. Apply the complete plan to create the service. If needed, set `public_url` to the resulting `service_url` output and apply again before generating memos. Provision tenant accounts using `ProvisionApp` and verify login, cross-tenant denial, saved edits, approval and PDF download in the deployed environment.

The migration job needs the database owner's schema/role privileges. Workspace requests explicitly reduce their database role inside each transaction. Authentication uses its existing application tables. This is application tenant isolation, not a claim that a compromised database-owner credential is restricted to one tenant.

Run migrations before deploying future schema-dependent versions. The deployment script changes traffic only after candidate readiness checks pass. Cloud Run keeps CPU allocated for background jobs. A persisted 15-minute lease prevents duplicate memo work and allows a user to resume an interrupted job after lease expiry. A superseded worker cannot overwrite a newer revision. A failed job retains its approved snapshot for retry.

## Source-pack onboarding

An administrator can load an existing source pack into an existing analyst's tenant:

```sh
target/runtime/bin/logicov-server -main logicov.workspace.WorkspaceLoadApp analyst@example.com /path/to/source-pack
```

The importer builds the record deterministically, retains source files, and leaves the deal awaiting review. It does not fetch new public filings, contact a model provider or grant credit approval. A pack may contain up to 200 MB, with individual files limited to 50 MB. Symlinks outside the pack are rejected.

Users can import PDF/CSV/XLS/XLSX statements through the browser, save the original and extracted grid, explain extraction issues, correct cited values and approve a financial review. Users can attach supporting documents and complete the credit-profile form. It requires three fiscal years of cash flow, capital expenditures and sourced annual EBITDA, plus the latest required balance-sheet and income-statement facts. Missing values are listed rather than filled with zero. Complete profiles support risk rating, actual-covenant analysis, analyst judgments, sensitivities and committee memo sections. Zero-debt and zero-interest cases require a separate methodology review.

## Policy and narratives

No institution policy is invented. Configure versioned rules with a source reference. Missing financial inputs are reported as unavailable. Breaches, unavailable rules and an unconfigured policy require an explicit approval explanation. Edits revoke the previous approval. A changed calculated risk rating with an existing override requires review of the override before approval. The analyst records the credit recommendation separately; approving inputs does not automatically recommend lending.

Analysts can write section narratives directly. Without generated narratives, the memo uses deterministic summaries and the analyst's saved prose. Production requires a verified client-owned Anthropic organization and zero-retention arrangement. With explicit client approval, development may enable public-source-only testing while retention confirmation is pending. Uploaded financials and analyst notes are excluded by the public narrative boundary. To enable approved generated narrative drafts, configure `ANTHROPIC_API_KEY`, `LOGICOV_NARRATIVE_MODEL`, `LOGICOV_PROVIDER_APPROVED=true` and `LOGICOV_NARRATIVES_ENABLED=true`. Do not enable these flags with a personal provider account for private-company documents. Terraform creates the provider-key secret container without a value. Add the client credential through Secret Manager, then set `provider_key_version` to its numeric version, `narrative_model` to the configured model identifier, and `narratives_enabled=true`; the key is not supplied through Terraform variables. Figures are checked against the record and retained source context; analyst review remains necessary.

Generated PDFs link to authenticated audit documents. The downloadable ZIP also includes portable PDF/HTML, audit history, retained sources, the approved snapshot and SHA-256 hashes. Source documents remain unchanged by grid corrections. Older memo artifacts remain available after later edits.

## Backup and recovery

Cloud SQL automated backups and point-in-time recovery are enabled. Preserve the master-key secret version and the encrypted tenant-key table along with the database. Protect the Terraform state separately. A database backup without the matching master key is insufficient.

For a recovery drill, restore a backup into an isolated database, configure the matching master key, run schema verification, and confirm that an authorized analyst can download a retained source and that another tenant receives 404. Compare the source's SHA-256 and the approved snapshot before shifting traffic. Never test a restore by overwriting the live database. Master-key rotation requires rewrapping tenant keys; replacing the environment key alone is not a rotation procedure.

## Verification

Run `LOGICOV_AUTH_TESTS=1` with a dedicated database whose URL contains `logicov_m1_test`, then run `server/testFull`. Integration tests cover encrypted payloads, tenant-bound keys, forced RLS, transaction scope reset, source isolation, stale writes, approval invalidation and lease recovery. `deploy/workspace-smoke.cjs` exercises browser save/correct/approve/generate/download. `deploy/profile-ui-smoke.cjs` covers a statement-to-credit-profile workflow; `deploy/reference-ui-smoke.cjs` covers reference-deal judgments, policy and narrative ordering. `deploy/verify-restore.py` checks an isolated restore using authorized and second-tenant accounts. `deploy/workspace-api-smoke.py` verifies the three onboarded reference borrowers and portable artifact hashes; it records local test approvals, not client acceptance.

## Required analyst checks

Before approval or generation, each deal requires documented clearance of sanctions screening, going-concern opinion, financial-statement currency, borrower/holding-company identity and dividend use of proceeds. The Memo section saves Pending, Clear or Stop with supporting evidence. A policy exception cannot bypass these checks. They are analyst attestations; the application does not perform sanctions screening or infer absent opinions. Financial corrections, new sources and replacement credit profiles clear the prior checks. Saving checks invalidates approval and is recorded in history; the PDF includes the approved evidence. Existing approvals without these checks require review before further generation.

## Public narrative boundary

Hosted model requests contain only issuer identity, industry classification and available executive-officer excerpts independently fetched from SEC endpoints using a numeric CIK. Only industry and leadership sections are drafted. Uploaded financials, record figures, transcripts, analyst notes, institution policies, recommendations and manual narrative text are excluded. Private-company records without a public CIK make no model request. Manual section text takes precedence. The public sources, supplied evidence and prompt hash appear in the audit companion. Financial/rating/recommendation text remains deterministic or analyst-written. Public drafts use current SEC sources and require analyst review against the deal's as-of date.

Enable only with the approved client account, existing provider flags and `EDGAR_USER_AGENT` identifying the operator. Source or provider failures stop generation and preserve the approved snapshot for retry. No personal provider account is used by hosted generation. The standalone public-data demonstration CLI remains a separate operator tool; its broader prompt interface must not be used with confidential material.

Production preparation and rollback are in [PRODUCTION.md](PRODUCTION.md).
