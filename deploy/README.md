# Deployment

The runtime image contains the EveryUI frontend, Spice API, Java 25 and Chromium. PostgreSQL holds provisioned users, tenants and hashed sessions. The browser and API use the same origin. Accounts are provisioned by an operator; there is no public registration endpoint.

This release includes saved deal uploads, sourced credit profiles, grid corrections, policy results, analyst approval and resumable memo generation. Tenant membership is derived from the authenticated account, never a browser-supplied tenant ID. Workspace data uses forced PostgreSQL row-level security and tenant-specific encryption keys. Read [workspace operations](WORKSPACE.md) before deployment, especially migration, master-key retention and provider configuration.

## Build and local verification

Requirements: Java 25, sbt 2.0.9, Docker, Python 3; Terraform 1.14.0 for infrastructure checks. Node and Playwright 1.64.1 are needed for browser smoke tests. Some framework snapshots currently exist only in the local build repository. CI installs a reviewed binary dependency bundle from a private repository release before resolving the remaining dependencies. A GitHub Packages read token in `GITHUB_TOKEN` supports packages outside that bundle; the optional CI secret `OUTR_PACKAGES_TOKEN` must have access to those packages if the default repository token does not.

```bash
./deploy/dev-postgres.sh
./deploy/package.sh
./deploy/image.sh logicov:local
```

Packaging always builds the frontend and places the complete runtime in `target/runtime`. Docker receives only that directory as its context, not the repository, source packs, secrets or internal notes. `sbt --server` starts a fresh foreground process so changed environment variables do not get lost through a previously running sbt server.

Configuration:

| Variable | Purpose |
|---|---|
| `PORT` | HTTP port, defaults to 8300 locally; image defaults to 8080 |
| `LOGICOV_ENV` | Set to `production` on Cloud Run; requires explicit DB settings and secure cookies |
| `LOGICOV_DB_URL` | JDBC URL |
| `LOGICOV_DB_USER` | PostgreSQL user |
| `LOGICOV_DB_PASSWORD` | PostgreSQL password; use Secret Manager in GCP |
| `LOGICOV_MASTER_KEY` | Base64-encoded 32-byte encryption key; required in production |
| `LOGICOV_PUBLIC_URL` | HTTPS application origin for authenticated source links in memos |
| `LOGICOV_DB_POOL_SIZE` | Per-instance pool size, defaults to 8 |
| `LOGICOV_WEB_DIRECTORY` | Frontend directory, defaults to `app/out`; image uses `/opt/logicov/web` |

The local defaults connect to PostgreSQL at port 25432 with the development credentials in `config.json`. Those defaults are refused in Cloud Run unless explicit production configuration is supplied. Do not use development database credentials for a hosted environment.

Create the first account interactively, with the database environment configured:

```bash
target/runtime/bin/logicov-server -main logicov.auth.ProvisionApp 'Organization name' 'analyst@example.com'
```

The password prompt hides input. Noninteractive execution reads one line from stdin; do not pass a password as a CLI argument. Passwords must be 14 to 256 characters. The command creates a tenant and user atomically and prints the tenant ID. To add another user to that tenant:

```bash
target/runtime/bin/logicov-server -main logicov.auth.ProvisionApp '--tenant-id=TENANT_ID' 'second@example.com'
```

Provisioning refuses existing email addresses and never resets an existing account's password. Tenant membership changes and account lifecycle administration are not exposed through public APIs in this release.

Run the server with `target/runtime/bin/logicov-server`. For a Linux host using the local PostgreSQL container, run the image with `--network host`, an explicit JDBC URL at `127.0.0.1:25432`, and the desired `PORT`. Do not publish the development instance on the public internet.

Authentication uses Argon2id password hashes and random 256-bit session cookies. Only SHA-256 token digests are stored. Sessions expire after twelve hours and are revoked on logout. Disabled users and tenants cannot access an existing session. Cookies are HttpOnly, SameSite=Strict, and Secure in production. Mutation endpoints require JSON plus `X-LogiCov-Request: 1`; cross-origin CORS is not enabled. Password hashing concurrency and repeated attempts per email are bounded per instance. These limits are not a distributed abuse-prevention system.

`GET /api/health` is the process liveness endpoint. `GET /api/ready` verifies a database connection. `GET /api/session` requires a valid session and returns only that account's tenant membership. Responses containing authentication information are not cached. Runtime logs go to stdout/stderr.

Run database tests only against a dedicated database named `logicov_m1_test`:

```bash
LOGICOV_AUTH_TESTS=1 \
LOGICOV_DB_URL=jdbc:postgresql://127.0.0.1:25432/logicov_m1_test \
sbt --server -batch 'coreJs/compile; server/testFull; app/compile'
```

The auth integration tests are canceled when explicitly not enabled. CI enables them and provides an isolated PostgreSQL service. The other tests remain usable without a database. Do not report canceled auth tests as successful authentication validation.

Against a running test instance with a provisioned test account:

```bash
export LOGICOV_TEST_URL=http://127.0.0.1:18300
export LOGICOV_TEST_EMAIL=smoke@example.test
# Supply LOGICOV_TEST_PASSWORD through the local test environment.
python3 deploy/smoke.py
npm install --prefix target/browser playwright@1.64.1
NODE_PATH=./target/browser/node_modules CHROMIUM_PATH=/usr/bin/chromium node deploy/browser-smoke.cjs
```

The image's `chromium` wrapper disables the nested Chromium sandbox because the container runtime supplies the process boundary. Use it only to print trusted application-rendered memo HTML. It is not an untrusted browsing service. The workspace stores generated artifacts in encrypted PostgreSQL records. Standalone CLI outputs must be copied out of the ephemeral container if they need to be retained.

## Infrastructure preparation

### Reproducible build dependencies

Prepare the bundle on the machine with the working local dependencies:

```bash
python3 deploy/prepare-dependencies.py
```

This writes `target/build-dependencies/logicov-build-dependencies.tar.gz` and its SHA-256. It exports the binary artifacts and dependency metadata reachable from the build, including the build plugin. Source archives, documentation archives and dependency test binaries are excluded. Sigil remains object-code-only under its platform license. Treat the bundle as a private build artifact; do not publish it to a public release or include it in a memo review bundle.

After review, upload the bundle to the private build-dependencies bucket under its SHA-256 filename and pin that hash in `.github/workflows/ci.yml`. GitHub Actions authenticates through workload identity federation, downloads the archive, verifies its hash and every payload hash, and installs it into the runner's Ivy repository. The public source repository must not contain dependency archives or runtime image artifacts. Tested images are published only to private Artifact Registry.

Rebuild and review the bundle when framework dependencies change. The current snapshot names are mutable, so retain the exact archive and checksum used for a release rather than assuming its version strings identify the same binaries forever. Use a fresh Ivy directory and `sbt --server --no-global --ivy PATH/` to check bundle sufficiency independently of the developer's Ivy installation.

Terraform creates, per environment:

- Artifact Registry and separate runtime/deployment service accounts.
- PostgreSQL 17 in Cloud SQL, backups and point-in-time recovery, with deletion protection enabled by default.
- A database password in Secret Manager, readable only by the runtime identity through its dedicated grant.
- A private, versioned document bucket reserved for future ingestion. The runtime is not granted object access yet.
- GitHub workload identity federation restricted to the configured repository, numeric repository-owner ID, `master` branch and selected GitHub environment.
- Cloud Run when an initial immutable image is supplied. Minimum one instance, maximum two, eight DB connections per instance by default. Production uses regional Cloud SQL availability; development uses zonal.
- An optional project-filtered billing budget. Alerts go to the billing account's default recipients; they do not cap spending. Configure this for one environment if both share a project to avoid duplicate project alerts.

The initial database password is sensitive Terraform state. Use a private, versioned state bucket, tightly restrict state access, and never commit state, plans, real tfvars or secrets. Cloud SQL has no authorized network allowlist; the JDBC connector uses runtime IAM and encrypted connections. Review any organization policies that prohibit public IP or public Cloud Run invocation before applying.

Credential-free checks:

```bash
terraform -chdir=deploy/terraform init -backend=false -lockfile=readonly
terraform -chdir=deploy/terraform fmt -check
terraform -chdir=deploy/terraform validate
terraform -chdir=deploy/terraform test
```

The Terraform tests mock providers. They verify configuration properties, not actual GCP provisioning, quotas, IAM propagation or network connectivity.

## First deployment after access is available

1. Confirm the client-owned project, enabled billing, region, administrator access and organization policies. Choose `dev` or `prod`; each has separate state and named resources. The default region is `us-central1`, configurable before deployment.
2. Authenticate locally with the authorized account, including Application Default Credentials for Terraform. Run `./deploy/bootstrap-state.sh PROJECT_ID REGION` once to create the private versioned state bucket. If it already exists, inspect ownership and controls instead of recreating it.
3. Copy `deploy/terraform/environment.tfvars.example` to an ignored environment file and set the project, environment and numeric GitHub organization ID. Obtain the numeric ID from the actual repository owner, not its display name. Leave `image` empty for the first apply.
4. Initialize the backend with `terraform -chdir=deploy/terraform init -reconfigure -backend-config=bucket=PROJECT_ID-logicov-tfstate -backend-config=prefix=ENVIRONMENT`. When switching environments use the matching state prefix and tfvars together, or separate working directories. Never apply one environment's variables to another environment's state.
5. Run `terraform plan -var-file=... -out=...` and review the saved plan, then apply that plan. Run Terraform from `deploy/terraform` or use `-chdir` consistently. The first apply creates the foundation without deploying a placeholder service.
6. Build and test the image locally or through CI, configure Docker authentication for the regional Artifact Registry hostname, and push to the `image_repository` output. Set `image` in the environment's ignored tfvars to its `@sha256:...` digest. Follow the migration-job bootstrap in [workspace operations](WORKSPACE.md), execute the migration, then plan/review/apply the full configuration to create Cloud Run. Keep that variable nonempty in subsequent plans; clearing it would request removal of the service.
7. Provision the initial account using the packaged CLI through the Cloud SQL Auth Proxy bound to localhost. Retrieve the database secret into the operator's environment without printing it or putting it in shell history. Use the Terraform connection-name and secret-name outputs. The operator needs the relevant Cloud SQL/Secret Manager access. Stop the proxy and unset credentials after provisioning.
8. Open the Cloud Run URL, sign in, reload to confirm the session, and sign out. Verify readiness and an unauthenticated 401 from `/api/session`. Cloud Run public invocation is intentional so a browser can reach the login page; application authentication protects session data. The service disables the Cloud Run invoker IAM check instead of granting `allUsers` an IAM role, allowing the login page to work with domain-restricted sharing. Verify saved source access, an isolated second tenant, approval and memo generation. Set `public_url` to the observed service URL and apply it before generating memos if the final domain is not yet available.
9. The development workflow runs on pushes to `master` and by manual dispatch. It uses the `dev` environment, and federation permits only this repository, numeric owner, branch and environment. Deployment identifiers and the reviewed dependency hash are explicit in the workflow; no long-lived cloud credential is stored in GitHub.
10. Confirm successful compile/tests, browser workflows, private image publication, database migration, candidate readiness and traffic promotion. Production rollout is configured separately after client approval; the current workflow deploys development only.
11. Decide the application domain and configure DNS once its owner and desired hostname are confirmed. The Cloud Run service URL is sufficient for initial hosted validation.

Production model access is not needed to start this M1 application. Do not put a personal model key in Cloud Run. Before exposing narrative generation for client data, configure the client-owned Anthropic organization and confirm its zero-data-retention arrangement.

## Releases and rollback

The build workflow runs compilation, regression and PostgreSQL auth tests, Terraform validation/tests, image construction, and HTTP/browser smoke tests. It exports the tested image as a short-lived artifact. The deployment workflow runs that verification, loads the same artifact and pushes it. No rebuild occurs after verification.

`deploy/release.sh` updates an existing service with an immutable digest and a `candidate` tag without changing production traffic. It checks the candidate's readiness, frontend and unauthenticated session boundary before assigning traffic to that exact revision. Failure before promotion leaves existing traffic in place. GitHub serializes releases per environment. The temporary candidate URL is protected by the same application authentication as the main service.

Terraform owns service configuration. It ignores subsequent image changes so a normal infrastructure apply does not undo a workflow release. The first image still comes from the initial Terraform apply. Later releases require an existing service; the deployment identity cannot bootstrap the project or alter IAM.

For rollback, inspect the service's revision/traffic history and restore traffic to the previously verified revision:

```bash
gcloud run services update-traffic SERVICE --project PROJECT_ID --region REGION --to-revisions PREVIOUS_REVISION=100
```

Retain the prior revision and image until the new release is accepted. Database changes must remain compatible with the previous release; test backup restoration and migration rollback before introducing destructive schema changes. Do not destroy or recreate the database to roll back an application release.

## Sources

- [Cloud Run and PostgreSQL connection guidance](https://docs.cloud.google.com/sql/docs/postgres/connect-run)
- [Google Terraform Cloud Run v2 resource](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/cloud_run_v2_service)
- [GitHub federation authentication action](https://github.com/google-github-actions/auth)

## Email sign-in and workspace administrators

Email sign-in is restricted to existing, active accounts in active tenants. It does not register users or assign tenants. Requests return the same confirmation for unknown addresses and throttled requests. Ten-minute links contain 256-bit random tokens; only their SHA-256 digests are stored. Confirmation requires the matching email address and an explicit button press, then atomically consumes the link. The token is carried in a URL fragment and removed from the address bar before confirmation. Limits are shared in PostgreSQL: five requests per email per hour, one per minute, and 100 overall per hour.

The delivery adapter uses Mailgun. Configure an authorized sending domain, sender and sending credential before enabling it. Set `LOGICOV_EMAIL_SIGNIN_ENABLED=true`, `LOGICOV_EMAIL_FROM`, `LOGICOV_EMAIL_DOMAIN`, `LOGICOV_EMAIL_API_KEY`, and `LOGICOV_PUBLIC_URL`. On GCP, upload the credential to `logicov-ENVIRONMENT-email-key`, then set `email_signin_enabled`, `email_from`, `email_domain`, and the numeric `email_key_version` in the ignored Terraform environment variables. Run the migration job before releasing this version. Password sign-in remains available. Delivery failures are logged without email content or tokens, and issued links are revoked on failure.

For local browser tests only, `LOGICOV_EMAIL_API_URL` can point to an HTTP capture server on localhost or 127.0.0.1 at `/emails`. Production rejects such overrides. Do not use a real provider key for capture tests or retain captured links beyond the test.

Accounts default to Analyst. Workspace admins can inspect the tenant's user list through `/api/admin/users` and change policy rules. Roles are resolved from the authenticated server-side account on each request; a browser cannot assign itself a role. An authorized database operator can use `AccountAdminApp <email> <Admin|Analyst|remove>` to assign a role or remove an account and its sessions. Provisioning remains an operator action; there is no public signup. Administrator status never grants cross-tenant access.

Password reset uses separate single-use, ten-minute links from the Forgot password action. Passwords must contain 14 to 256 characters. Resetting invalidates all prior sessions and outstanding email links; the user then signs in normally. Link tracking is disabled. Mailgun defaults to the US region; set `LOGICOV_EMAIL_REGION=EU` (`email_region` in Terraform) for an EU domain. Changing the domain, sender and pinned credential requires no code change.
