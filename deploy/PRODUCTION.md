# Production release procedure

Production starts after client approval of the development release. The current GitHub workflow deploys development only. This checklist and the example variables do not create production resources.

1. Record the approved Git commit and tested image digest. Recheck development CI and retain the prior working revision.
2. Create a separate Terraform working directory using the checked-in configuration. Initialize the existing private state bucket with prefix `prod`; never switch the active development directory to a production backend. Copy `production.tfvars.example` to an ignored environment file and review the project, region, resource names and deletion protections.
3. Plan and review the foundation with `image` empty. Confirm every proposed resource is production-scoped and that no development resource is changed or destroyed. Apply only the reviewed plan after approval. Production must have its own database, service identities, key and secrets.
4. Grant the production build identity read access to the reviewed private dependency object if rebuilding. Configure a separate protected GitHub `prod` environment and exact repository/owner/branch/environment federation. Do not widen development federation or change the existing push workflow into an automatic production release.
5. Pin the approved image digest, review the migration job configuration, run the schema migration and deploy the initial service. Set its public HTTPS origin before generating memos. Configure DNS only after the hostname is agreed.
6. Provision only approved users as administrators. Retain generated credentials securely; never copy test credentials or database content. Configure the approved sender/domain and pinned email-key version. Verify sign-in and password reset using the production origin.
7. Leave generated narratives disabled until the client-owned Anthropic credential, model identifier, retention approval and SEC contact are configured. Hosted generation uses independently retrieved public SEC data only; confidential uploads and analyst text stay local. Verify provider failure handling before activation.
8. Run readiness, authentication, role and reference-workflow checks against the candidate. Promote only the verified revision. Record the deployment, image digest and checks.
9. After production deployment, perform the agreed Cloud SQL restore drill in an isolated environment, verify source hashes/decryption and tenant boundaries, remove the drill resources, and complete operational handoff. Retain the production master key and restricted state backups.

## Rollback

Keep migrations additive and compatible with the prior image. Restore traffic to the recorded prior revision if application checks fail; this does not roll back data. Do not delete or recreate Cloud SQL, overwrite encryption keys, or apply a development Terraform plan to production. For a data incident, stop the affected writes, identify the restore point and restore into an isolated instance before deciding on a cutover.

## Approved image promotion

The manual `Promote approved production release` workflow promotes a previously verified development image without rebuilding it. Supply the full approved commit SHA and immutable development image digest. It rejects a commit without successful development CI and checks that the commit's image tag resolves to the supplied digest. The production copy must retain the same digest. Migration runs before candidate health checks and traffic promotion; failed candidate checks leave existing traffic unchanged.

Before the first production release, configure the GitHub `prod` environment with required reviewers and no self-approval, limit deployment branches to `master`, and set its `PRODUCTION_RELEASE_ENABLED` variable to `true` only after client approval. Until then the workflow fails closed. Bootstrap the production foundation/service using the steps above. Set `release_source_repository = "logicov-dev"` to grant the production deploy identity read access to the tested images. No production resources or environment approval settings are created by committing this workflow.

## Monitoring

`monitoring_enabled` provisions HTTPS readiness checks and sustained server-error incidents. Readiness failure alerts after five minutes; server-error alerts require more than three errors per minute for five minutes. Configure existing approved notification channel names in `monitoring_notification_channels` when recipients are agreed. Without channels, incidents remain in Cloud Monitoring and no messages are sent. These checks are operational indicators, not a guarantee that financial outputs are correct.
