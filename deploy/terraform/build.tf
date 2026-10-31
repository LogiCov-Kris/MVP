resource "google_storage_bucket" "build_dependencies" {
  name                        = "${var.project_id}-${local.name}-build-dependencies"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  versioning { enabled = true }
}

resource "google_storage_bucket_iam_member" "build_dependencies" {
  bucket = google_storage_bucket.build_dependencies.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.deploy.email}"
}

resource "google_cloud_run_v2_job_iam_member" "migrate" {
  count    = length(google_cloud_run_v2_job.migrate)
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_job.migrate[0].name
  role     = "roles/run.developer"
  member   = "serviceAccount:${google_service_account.deploy.email}"
}

resource "google_artifact_registry_repository_iam_member" "release_source" {
  count      = var.release_source_repository == "" ? 0 : 1
  location   = var.region
  repository = var.release_source_repository
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.deploy.email}"
}
