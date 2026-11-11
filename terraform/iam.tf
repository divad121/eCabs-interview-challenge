locals {
  app_namespace = "ecabs"
}

# --- Workloads ----------------------------------------------------------------
# Only the consumer calls Google APIs (it reads its MongoDB connection string from Secret Manager).
# The producer needs no Google identity; RabbitMQ credentials are generated and kept inside the cluster.

resource "google_service_account" "booking_consumer" {
  account_id   = "booking-consumer"
  display_name = "booking-consumer workload"

  depends_on = [google_project_service.this]
}

# Lets the consumer's Kubernetes service account act as this Google service account (Workload Identity).
# The identity pool exists only once the cluster does, hence the dependency.
resource "google_service_account_iam_member" "booking_consumer_workload_identity" {
  service_account_id = google_service_account.booking_consumer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${local.app_namespace}/booking-consumer]"

  depends_on = [google_container_cluster.main]
}

# --- CI/CD (GitHub Actions, keyless) -------------------------------------------
# GitHub's OIDC token is exchanged for short-lived Google credentials: no JSON key exists to leak.

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"

  depends_on = [google_project_service.this]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-oidc"
  display_name                       = "GitHub OIDC"

  # Only workflows from this repository's main branch can authenticate; pull requests (including forks) cannot.
  attribute_condition = "assertion.repository == \"${var.github_repository}\" && assertion.ref == \"refs/heads/main\""

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "ci_deployer" {
  account_id   = "ci-deployer"
  display_name = "GitHub Actions deployer"
  description  = "Pushes images and applies Kubernetes manifests from the main branch."

  depends_on = [google_project_service.this]
}

resource "google_service_account_iam_member" "ci_deployer_github" {
  service_account_id = google_service_account.ci_deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

# Deploys namespaced workloads; cannot change cluster configuration or RBAC.
resource "google_project_iam_member" "ci_deployer_gke" {
  project = var.project_id
  role    = "roles/container.developer"
  member  = google_service_account.ci_deployer.member
}
