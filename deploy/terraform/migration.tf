resource "google_cloud_run_v2_job" "migrate" {
  count               = var.image == "" ? 0 : 1
  name                = "${local.name}-migrate"
  location            = var.region
  deletion_protection = var.deletion_protection
  template {
    template {
      service_account = google_service_account.runtime.email
      max_retries     = 0
      timeout         = "300s"
      containers {
        image   = var.image
        command = ["java"]
        args    = ["-cp", "/opt/logicov/lib/*", "logicov.workspace.WorkspaceMigrateApp"]
        resources { limits = { cpu = "1", memory = "512Mi" } }
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
      }
    }
  }
  depends_on = [google_sql_database.app, google_secret_manager_secret_iam_member.database, google_project_iam_member.sql_client]
}
