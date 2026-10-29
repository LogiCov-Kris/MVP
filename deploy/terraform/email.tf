resource "google_secret_manager_secret" "email_key" {
  secret_id = "${local.name}-email-key"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}
resource "google_secret_manager_secret_iam_member" "email_key" {
  secret_id = google_secret_manager_secret.email_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
