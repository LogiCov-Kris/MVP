locals {
  name = "logicov-${var.environment}"
  apis = toset(["run.googleapis.com", "sqladmin.googleapis.com", "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com",
  "sts.googleapis.com", "cloudresourcemanager.googleapis.com", "storage.googleapis.com", "billingbudgets.googleapis.com"])
}
resource "google_project_service" "required" {
  for_each           = local.apis
  service            = each.value
  disable_on_destroy = false
}
data "google_project" "current" { project_id = var.project_id }
resource "google_service_account" "runtime" {
  account_id   = local.name
  display_name = "LogiCov ${var.environment} runtime"
  depends_on   = [google_project_service.required]
}
resource "google_service_account" "deploy" {
  account_id   = "${local.name}-deploy"
  display_name = "LogiCov ${var.environment} deployment"
  depends_on   = [google_project_service.required]
}
resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = local.name
  format        = "DOCKER"
  depends_on    = [google_project_service.required]
}
resource "google_artifact_registry_repository_iam_member" "push" {
  location   = var.region
  repository = google_artifact_registry_repository.images.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.deploy.email}"
}
resource "google_project_iam_member" "sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}
resource "google_sql_database_instance" "main" {
  name                = local.name
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = var.deletion_protection
  settings {
    tier              = var.database_tier
    edition           = "ENTERPRISE"
    availability_type = var.environment == "prod" ? "REGIONAL" : "ZONAL"
    disk_size         = 10
    disk_autoresize   = true
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7
    }
    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
    }
    deletion_protection_enabled = var.deletion_protection
  }
  depends_on = [google_project_service.required]
}
resource "google_sql_database" "app" {
  name     = "logicov"
  instance = google_sql_database_instance.main.name
}
resource "random_password" "database" {
  length  = 40
  special = false
}
resource "google_sql_user" "app" {
  name     = "logicov"
  instance = google_sql_database_instance.main.name
  password = random_password.database.result
}
resource "google_secret_manager_secret" "database" {
  secret_id = "${local.name}-database-password"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}
resource "google_secret_manager_secret_version" "database" {
  secret      = google_secret_manager_secret.database.id
  secret_data = random_password.database.result
}
resource "google_secret_manager_secret_iam_member" "database" {
  secret_id = google_secret_manager_secret.database.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
resource "random_id" "master_key" {
  byte_length = 32
  lifecycle { prevent_destroy = true }
}
resource "google_secret_manager_secret" "master_key" {
  secret_id = "${local.name}-master-key"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}
resource "google_secret_manager_secret_version" "master_key" {
  secret      = google_secret_manager_secret.master_key.id
  secret_data = random_id.master_key.b64_std
  lifecycle { prevent_destroy = true }
}
resource "google_secret_manager_secret_iam_member" "master_key" {
  secret_id = google_secret_manager_secret.master_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
resource "google_storage_bucket" "documents" {
  name                        = "${var.project_id}-${local.name}-documents"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  versioning { enabled = true }
  depends_on = [google_project_service.required]
}
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "${local.name}-github"
  depends_on                = [google_project_service.required]
}
resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
  }
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.repository_owner_id == '${var.github_owner_id}' && assertion.ref == 'refs/heads/master' && assertion.sub == 'repo:${split("/", var.github_repository)[0]}@${var.github_owner_id}/${split("/", var.github_repository)[1]}@${var.github_repository_id}:environment:${var.environment}'"
  oidc { issuer_uri = "https://token.actions.githubusercontent.com" }
}
resource "google_service_account_iam_member" "federation" {
  service_account_id = google_service_account.deploy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}
resource "google_service_account_iam_member" "act_as" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deploy.email}"
}
resource "google_cloud_run_v2_service" "app" {
  count                = var.image == "" ? 0 : 1
  name                 = local.name
  location             = var.region
  deletion_protection  = var.deletion_protection
  ingress              = "INGRESS_TRAFFIC_ALL"
  invoker_iam_disabled = true
  template {
    service_account                  = google_service_account.runtime.email
    max_instance_request_concurrency = 40
    timeout                          = "60s"
    scaling {
      min_instance_count = 1
      max_instance_count = 2
    }
    containers {
      image = var.image
      ports { container_port = 8080 }
      resources {
        limits            = { cpu = "1", memory = "1Gi" }
        cpu_idle          = false
        startup_cpu_boost = true
      }
      env {
        name  = "LOGICOV_ENV"
        value = "production"
      }
      env {
        name  = "LOGICOV_DB_URL"
        value = "jdbc:postgresql:///logicov?cloudSqlInstance=${google_sql_database_instance.main.connection_name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy"
      }
      env {
        name  = "LOGICOV_DB_USER"
        value = google_sql_user.app.name
      }
      env {
        name = "LOGICOV_DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database.secret_id
            version = google_secret_manager_secret_version.database.version
          }
        }
      }
      env {
        name  = "LOGICOV_PUBLIC_URL"
        value = var.public_url
      }
      env {
        name = "LOGICOV_MASTER_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.master_key.secret_id
            version = google_secret_manager_secret_version.master_key.version
          }
        }
      }
      dynamic "env" {
        for_each = var.narratives_enabled ? {
          LOGICOV_NARRATIVES_ENABLED = "true"
          LOGICOV_PROVIDER_APPROVED  = "true"
          LOGICOV_NARRATIVE_MODEL    = var.narrative_model
          EDGAR_USER_AGENT           = var.edgar_user_agent
        } : {}
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = var.narratives_enabled ? [1] : []
        content {
          name = "ANTHROPIC_API_KEY"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.provider_key.secret_id
              version = var.provider_key_version
            }
          }
        }
      }
      dynamic "env" {
        for_each = var.email_signin_enabled ? {
          LOGICOV_EMAIL_SIGNIN_ENABLED = "true"
          LOGICOV_EMAIL_FROM           = var.email_from
          LOGICOV_EMAIL_DOMAIN         = var.email_domain
          LOGICOV_EMAIL_REGION         = var.email_region
        } : {}
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = var.email_signin_enabled ? [1] : []
        content {
          name = "LOGICOV_EMAIL_API_KEY"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.email_key.secret_id
              version = var.email_key_version
            }
          }
        }
      }
      startup_probe {
        initial_delay_seconds = 10
        period_seconds        = 10
        timeout_seconds       = 5
        failure_threshold     = 24
        http_get { path = "/api/ready" }
      }
      liveness_probe {
        period_seconds    = 30
        timeout_seconds   = 5
        failure_threshold = 3
        http_get { path = "/api/health" }
      }
    }
  }
  lifecycle {
    ignore_changes = [template[0].containers[0].image, client, client_version]
    precondition {
      condition     = !var.email_signin_enabled || (var.public_url != "" && var.email_from != "" && var.email_domain != "" && can(regex("^[1-9][0-9]*$", var.email_key_version)))
      error_message = "Email sign-in requires an application origin, verified sender and pinned email-key version."
    }
    precondition {
      condition     = !var.narratives_enabled || (var.narrative_model != "" && var.edgar_user_agent != "" && can(regex("^[1-9][0-9]*$", var.provider_key_version)))
      error_message = "Enabling narratives requires a model identifier, SEC contact and a pinned, client-owned provider-key secret version."
    }
    precondition {
      condition     = var.public_url == "" || can(regex("^https://[^/]+$", var.public_url))
      error_message = "public_url must be empty for initial bootstrap or an HTTPS application origin. Configure it before generating memos."
    }
  }
  depends_on = [google_sql_database.app, google_secret_manager_secret_iam_member.database,
  google_project_iam_member.sql_client, google_secret_manager_secret_iam_member.master_key, google_secret_manager_secret_iam_member.provider_key, google_secret_manager_secret_iam_member.email_key]
}
resource "google_cloud_run_v2_service_iam_member" "release" {
  count    = length(google_cloud_run_v2_service.app)
  location = var.region
  name     = google_cloud_run_v2_service.app[0].name
  role     = "roles/run.developer"
  member   = "serviceAccount:${google_service_account.deploy.email}"
}
resource "google_billing_budget" "project" {
  count           = var.billing_account_id == "" ? 0 : 1
  billing_account = var.billing_account_id
  display_name    = "${local.name} monthly budget"
  budget_filter { projects = ["projects/${data.google_project.current.number}"] }
  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.monthly_budget_usd)
    }
  }
  threshold_rules { threshold_percent = 0.5 }
  threshold_rules { threshold_percent = 0.9 }
  threshold_rules { threshold_percent = 1.0 }
}
