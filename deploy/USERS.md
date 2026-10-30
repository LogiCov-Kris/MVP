# Workspace user administration

Administrators open **Users** from the workspace navigation. Analysts cannot access this screen or its API. User management is scoped to the signed-in administrator's workspace; a request cannot select another tenant. Platform administrators also have a Workspaces screen for customer onboarding and workspace management. Ordinary workspace administrators cannot access it.

Create an account with its email and Analyst or Administrator role, then send an invitation from its detail view. The system creates an unknown random password; no password is displayed or shared. The invitation directs the recipient to request a fresh email sign-in link, or use Forgot password to set their own password. The welcome email itself grants no access and contains no authentication token.

Email must be configured using the existing mail settings. Invitations are explicit administrator actions, limited to one attempt per account per minute. An accepted provider request is not confirmation of inbox delivery. Failed attempts retain the account and appear in its access history; refresh and retry when delivery is available.

Administrators may change roles, suspend/reactivate access, and revoke all sessions and outstanding authentication links. Existing records and authored deal history are retained. Suspension/reactivation does not restore old sessions or links. The system rejects stale account edits and prevents self-removal of administrator access. An active workspace must retain an active administrator.

Monitoring includes last successful sign-in, valid unexpired session count, invitation attempts and administrative history. Session count is not an online-presence indicator. Sign-in timestamps are recorded from this release onward; older accounts show Not recorded until their next successful sign-in. Administrators can refresh the list to obtain current information.

Schema additions use the standard database initialization/migration path. Run the migration job with the release image before deploying it. Existing sessions use access version zero and continue until expiration or explicit revocation. No existing account role or access is changed by this migration.

Validation covers tenant isolation, analyst denial, concurrent and stale changes, self-lockout protection, permanent session/link revocation, invitation success/failure evidence, and an end-to-end browser workflow with a local email capture. The browser test sends no real email and provisions only test accounts.

## Customer workspaces

Platform administrators can create a workspace with its first administrator, rename it, suspend/reactivate access, manage its users, and review membership counts, latest successful sign-in and administrative history. Creation is atomic: an invalid or already assigned first-administrator email leaves no partial workspace behind. Each email currently belongs to one workspace. Invitations are sent explicitly from the workspace's user detail screen after creation.

Workspace suspension revokes every member's existing sessions and outstanding authentication links. Reactivation restores eligibility to sign in, but does not revive old credentials. Customer records are retained. A workspace containing platform administrators cannot be suspended through the interface. Every active workspace must retain an active administrator. Concurrent management actions are serialized and stale revisions are rejected.

Platform access is a separate operator-provisioned flag, not a role selectable by customer administrators. After the schema migration and application deployment, an operator can enable it for an existing administrator with:

```bash
target/runtime/bin/logicov-server -main logicov.auth.AccountAdminApp '<administrator email>' platform
```

Use `workspace` instead of `platform` to remove platform access while retaining the workspace Administrator role. The operation revokes existing sessions; the administrator must sign in again. Existing accounts default to no platform access. The account-management API cannot set this flag, and customer administrators cannot change platform accounts.

The platform interface manages workspace configuration and accounts. It does not switch the operator's deal context or grant implicit access to customer deal records or source documents. Those endpoints continue to use the signed-in user's own tenant.

## Analyst review and submission

The analyst starts a New Credit File with the borrower legal name and a source statement. Extraction saves the source and cited values together. Data Verification supports audited borrower-name and financial corrections. Credit-profile inputs, institution policy and required checks remain prerequisites to approval and generation.

Memo Review presents one continuous document with section navigation and a linked source appendix. Inline narrative edits support shared values and references to another section's narrative; changing the underlying record updates those references throughout the document. Literal prose remains the analyst's responsibility. Financial corrections recalculate the deterministic analysis. Narrative, source, judgment and policy changes invalidate input approval, generated output and section review completion.

Every configured memo section must be explicitly marked reviewed before the server accepts a committee recommendation. Submit to Committee records the recommendation and its rationale, produces the final PDF, and changes the deal to Submitted for Approval only after generation succeeds. This is a submission record, not committee approval or voting. Failed generation retains the approved snapshot for retry. Concurrent and stale edits are rejected.

Progress reports completed rendering sections and actual PDF/packaging phases. During extraction, progress is indeterminate until the saved result is returned. No timed simulation is used. Standalone XBRL/XML intake supports undimensioned US GAAP USD facts for the selected periods; inline XBRL, custom taxonomy inference, and OCR are not implied.

Administration is a separate section at `/admin`, entered through **Admin** in the account header. Users and Workspaces navigation appears only there; the analyst workspace contains credit-file functions only. **Exit Admin** returns to the analyst workspace. Opening `/admin` directly requires an administrator account, and its APIs continue to enforce workspace and platform permissions independently.
