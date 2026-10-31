resource "google_monitoring_uptime_check_config" "ready" {
  count        = var.monitoring_enabled && var.public_url != "" ? 1 : 0
  display_name = "${local.name} readiness"
  timeout      = "10s"
  period       = "60s"
  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = trimprefix(var.public_url, "https://")
    }
  }
  http_check {
    path         = "/api/ready"
    port         = 443
    use_ssl      = true
    validate_ssl = true
  }
  depends_on = [google_project_service.required]
}

resource "google_monitoring_alert_policy" "unavailable" {
  count                 = length(google_monitoring_uptime_check_config.ready)
  display_name          = "${local.name} readiness failure"
  combiner              = "OR"
  notification_channels = var.monitoring_notification_channels
  conditions {
    display_name = "Readiness checks failing for five minutes"
    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.type=\"uptime_url\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.ready[0].uptime_check_id}\""
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      duration        = "300s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_FRACTION_TRUE"
      }
      trigger { count = 1 }
    }
  }
  documentation {
    content   = "Check Cloud Run readiness, Cloud SQL connectivity and the current revision. Consult deploy/PRODUCTION.md before rollback."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "server_errors" {
  count                 = var.monitoring_enabled ? 1 : 0
  display_name          = "${local.name} sustained server errors"
  combiner              = "OR"
  notification_channels = var.monitoring_notification_channels
  conditions {
    display_name = "Server errors exceed three per minute for five minutes"
    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${local.name}\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0.05
      duration        = "300s"
      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
      }
      trigger { count = 1 }
    }
  }
  depends_on = [google_project_service.required]
}
