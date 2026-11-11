resource "google_service_account" "gke_nodes" {
  account_id   = "gke-nodes"
  display_name = "GKE Autopilot nodes"
  description  = "Least-privilege node identity (logging, metrics, image pulls) instead of the Compute Engine default account, which has Editor."

  depends_on = [google_project_service.this]
}

resource "google_project_iam_member" "gke_nodes" {
  project = var.project_id
  role    = "roles/container.defaultNodeServiceAccount"
  member  = google_service_account.gke_nodes.member
}

# Authorized networks restrict the control plane's IP endpoint, which is disabled below; access is through
# the IAM-authorised DNS endpoint only.
#trivy:ignore:GCP-0061
resource "google_container_cluster" "main" {
  name     = "ecabs-gke"
  location = var.region

  enable_autopilot    = true
  deletion_protection = var.cluster_deletion_protection

  network    = google_compute_network.main.id
  subnetwork = google_compute_subnetwork.gke.id

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # Nodes get no public IPs.
  private_cluster_config {
    enable_private_nodes = true
  }

  # The control plane is reached only through its DNS endpoint, authorised by IAM, so CI runners and
  # engineers need no IP allow-list or bastion. The IP endpoint is switched off entirely.
  control_plane_endpoints_config {
    dns_endpoint_config {
      allow_external_traffic = true
    }
    ip_endpoints_config {
      enabled = false
    }
  }

  release_channel {
    channel = "REGULAR"
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  # Mounts Secret Manager secrets into pods as files (CSI), read with each workload's own identity.
  secret_manager_config {
    enabled = true
  }

  gateway_api_config {
    channel = "CHANNEL_STANDARD"
  }

  cluster_autoscaling {
    auto_provisioning_defaults {
      service_account = google_service_account.gke_nodes.email
      oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    }
  }

  # Automatic upgrades only Tuesday-Thursday 02:00-06:00 UTC (03:00-08:00 in Malta), never near a Friday surge.
  maintenance_policy {
    recurring_window {
      start_time = "2026-01-06T02:00:00Z"
      end_time   = "2026-01-06T06:00:00Z"
      recurrence = "FREQ=WEEKLY;BYDAY=TU,WE,TH"
    }
  }

  depends_on = [google_project_iam_member.gke_nodes]
}
