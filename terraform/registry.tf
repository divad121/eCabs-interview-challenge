resource "google_artifact_registry_repository" "images" {
  repository_id = "ecabs"
  location      = var.region
  format        = "DOCKER"
  description   = "Booking service container images"

  # CI tags images with the commit SHA; immutable tags guarantee a tag always means the same image.
  docker_config {
    immutable_tags = true
  }

  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "keep-recent-releases"
    action = "KEEP"
    most_recent_versions {
      keep_count = 20
    }
  }

  cleanup_policies {
    id     = "delete-old"
    action = "DELETE"
    condition {
      tag_state  = "ANY"
      older_than = "90d"
    }
  }

  depends_on = [google_project_service.this]
}

resource "google_artifact_registry_repository_iam_member" "nodes_pull" {
  repository = google_artifact_registry_repository.images.name
  location   = google_artifact_registry_repository.images.location
  role       = "roles/artifactregistry.reader"
  member     = google_service_account.gke_nodes.member
}

resource "google_artifact_registry_repository_iam_member" "ci_push" {
  repository = google_artifact_registry_repository.images.name
  location   = google_artifact_registry_repository.images.location
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.ci_deployer.member
}
