variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "us-central1"
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "Use dev or prod with separate state prefixes."
  }
}
variable "github_repository" {
  type    = string
  default = "LogiCov-Kris/MVP"
}
variable "github_owner_id" {
  type        = string
  description = "Numeric GitHub organization ID, used to bind federation to the repository owner."
}
variable "image" {
  type        = string
  default     = ""
  description = "Initial Artifact Registry image by digest; empty creates the foundation only."
  validation {
    condition     = var.image == "" || can(regex("@sha256:[a-f0-9]{64}$", var.image))
    error_message = "Deploy an immutable image digest."
  }
}
variable "deletion_protection" {
  type    = bool
  default = true
}
variable "database_tier" {
  type    = string
  default = "db-custom-1-3840"
}
variable "billing_account_id" {
  type        = string
  default     = ""
  description = "Optional billing account for a project-filtered budget alert."
}
variable "monthly_budget_usd" {
  type    = number
  default = 150
}

variable "public_url" {
  type        = string
  default     = ""
  description = "HTTPS application origin used for authenticated source links in exported memos."
}
variable "narratives_enabled" {
  type        = bool
  default     = false
  description = "Enable only after the client-owned provider account and zero-retention arrangement are verified."
}
variable "narrative_model" {
  type        = string
  default     = ""
  description = "Provider/model identifier for narrative generation."
}
variable "provider_key_version" {
  type        = string
  default     = ""
  description = "Existing Secret Manager version number for the client-owned provider key. Upload the secret outside Terraform."
}

variable "email_signin_enabled" {
  type    = bool
  default = false
}
variable "email_from" {
  type        = string
  default     = ""
  description = "Verified sender for sign-in emails."
}
variable "email_key_version" {
  type        = string
  default     = ""
  description = "Pinned version of the email provider credential in Secret Manager."
}

variable "email_domain" {
  type    = string
  default = ""
}

variable "email_region" {
  type    = string
  default = "US"
  validation {
    condition     = contains(["US", "EU"], var.email_region)
    error_message = "Email region must be US or EU."
  }
}

variable "github_repository_id" {
  type        = string
  default     = "1406452220"
  description = "Numeric repository ID used by immutable GitHub identity subjects."
}

variable "edgar_user_agent" {
  type        = string
  default     = ""
  description = "Operator name and contact email for independently retrieved public SEC evidence."
}
