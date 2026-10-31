mock_provider "google" {}
mock_provider "random" {}

variables {
  project_id      = "logicov-test-project"
  environment     = "dev"
  github_owner_id = "12345678"
  public_url      = "https://credit.example.test"
}

run "foundation_without_image" {
  command = plan
  assert {
    condition     = length(google_cloud_run_v2_service.app) == 0
    error_message = "The foundation must not deploy a placeholder image."
  }
  assert {
    condition     = google_sql_database_instance.main.deletion_protection
    error_message = "Database deletion protection must default on."
  }
  assert {
    condition     = google_storage_bucket.documents.public_access_prevention == "enforced"
    error_message = "Document storage must not become public."
  }
}

run "service_with_immutable_image" {
  command = plan
  variables {
    image = "us-central1-docker.pkg.dev/logicov-test-project/logicov-dev/server@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  assert {
    condition     = length(google_cloud_run_v2_service.app) == 1
    error_message = "An image must produce one service."
  }
  assert {
    condition     = google_cloud_run_v2_service.app[0].template[0].scaling[0].max_instance_count == 2
    error_message = "Runtime scaling must stay bounded."
  }
  assert {
    condition     = google_cloud_run_v2_service.app[0].template[0].containers[0].resources[0].limits["memory"] == "2Gi"
    error_message = "Narrative initialization and PDF rendering need the verified memory allocation."
  }
  assert {
    condition     = google_cloud_run_v2_service.app[0].template[0].containers[0].startup_probe[0].http_get[0].path == "/api/ready"
    error_message = "Startup must verify database readiness."
  }
}

run "narratives_require_provider_configuration" {
  command = plan
  variables {
    image              = "us-central1-docker.pkg.dev/logicov-test-project/logicov-dev/server@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    narratives_enabled = true
  }
  expect_failures = [google_cloud_run_v2_service.app]
}

run "narratives_use_pinned_client_secret" {
  command = plan
  variables {
    image                = "us-central1-docker.pkg.dev/logicov-test-project/logicov-dev/server@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    narratives_enabled   = true
    narrative_model      = "anthropic/configured-model"
    provider_key_version = "1"
    edgar_user_agent     = "Example Operator contact@example.test"
  }
  assert {
    condition     = length([for e in google_cloud_run_v2_service.app[0].template[0].containers[0].env : e if e.name == "ANTHROPIC_API_KEY"]) == 1
    error_message = "Enabled narratives must bind the client provider secret."
  }
}

run "email_requires_delivery_configuration" {
  command = plan
  variables {
    image                = "us-central1-docker.pkg.dev/logicov-test-project/logicov-dev/server@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    email_signin_enabled = true
  }
  expect_failures = [google_cloud_run_v2_service.app]
}

run "production_foundation_is_separate" {
  command = plan
  variables {
    environment = "prod"
    image       = ""
  }
  assert {
    condition     = google_sql_database_instance.main.name == "logicov-prod" && google_service_account.runtime.account_id == "logicov-prod"
    error_message = "Production must have separate database and runtime identities."
  }
  assert {
    condition     = length(google_cloud_run_v2_service.app) == 0 && google_sql_database_instance.main.deletion_protection
    error_message = "Production bootstrap must preserve data and not release a placeholder service."
  }
}

run "monitoring_and_promotion_are_opt_in" {
  command = plan
  assert {
    condition     = length(google_monitoring_alert_policy.server_errors) == 0 && length(google_artifact_registry_repository_iam_member.release_source) == 0
    error_message = "Monitoring and cross-repository promotion must remain opt-in."
  }
}

run "approved_production_release_preparation" {
  command = plan
  variables {
    environment               = "prod"
    release_source_repository = "logicov-dev"
    monitoring_enabled        = true
  }
  assert {
    condition     = google_artifact_registry_repository_iam_member.release_source[0].role == "roles/artifactregistry.reader"
    error_message = "Promotion needs read-only access to the source repository."
  }
  assert {
    condition     = length(google_monitoring_uptime_check_config.ready) == 1 && length(google_monitoring_alert_policy.server_errors) == 1 && length(google_monitoring_alert_policy.unavailable[0].notification_channels) == 0
    error_message = "Prepared monitoring must cover availability and errors without unapproved recipients."
  }
}
