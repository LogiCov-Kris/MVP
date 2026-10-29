output "image_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}/server"
}
output "workload_identity_provider" { value = google_iam_workload_identity_pool_provider.github.name }
output "deployment_service_account" { value = google_service_account.deploy.email }
output "database_connection" { value = google_sql_database_instance.main.connection_name }
output "database_password_secret" { value = google_secret_manager_secret.database.secret_id }
output "service_name" { value = local.name }
output "service_url" { value = try(google_cloud_run_v2_service.app[0].uri, "") }

output "master_key_secret" { value = google_secret_manager_secret.master_key.secret_id }
output "provider_key_secret" { value = google_secret_manager_secret.provider_key.secret_id }
output "migration_job" { value = try(google_cloud_run_v2_job.migrate[0].name, "") }
