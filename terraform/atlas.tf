locals {
  atlas_count = var.enable_atlas ? 1 : 0
}

resource "mongodbatlas_project" "main" {
  count = local.atlas_count

  name   = "ecabs-${var.environment}"
  org_id = var.atlas_org_id
}

resource "mongodbatlas_advanced_cluster" "main" {
  count = local.atlas_count

  project_id                     = mongodbatlas_project.main[0].id
  name                           = "bookings"
  cluster_type                   = "REPLICASET"
  mongo_db_major_version         = "8.0"
  backup_enabled                 = true
  pit_enabled                    = true
  termination_protection_enabled = var.cluster_deletion_protection
  # Auto-scaling changes the live tier; effective fields stop Terraform from reverting it on the next apply.
  use_effective_fields = true

  replication_specs = [
    {
      region_configs = [
        {
          provider_name = "GCP"
          region_name   = var.atlas_region
          priority      = 7
          electable_specs = {
            instance_size = var.atlas_instance_size
            node_count    = 3
          }
          auto_scaling = {
            compute_enabled            = true
            compute_scale_down_enabled = true
            compute_min_instance_size  = var.atlas_instance_size
            compute_max_instance_size  = var.atlas_max_instance_size
            disk_gb_enabled            = true
          }
        }
      ]
    }
  ]

  depends_on = [mongodbatlas_privatelink_endpoint_service.gcp]
}

# --- Private connectivity (Private Service Connect, port-mapped) ----------------
# The cluster is reachable only from inside the VPC through one internal IP; there is no public access list.

resource "mongodbatlas_privatelink_endpoint" "gcp" {
  count = local.atlas_count

  project_id           = mongodbatlas_project.main[0].id
  provider_name        = "GCP"
  region               = var.region
  port_mapping_enabled = true
}

resource "google_compute_address" "atlas_psc" {
  count = local.atlas_count

  name         = "atlas-psc"
  region       = var.region
  subnetwork   = google_compute_subnetwork.gke.id
  address_type = "INTERNAL"
  address      = var.atlas_psc_ip
}

resource "google_compute_forwarding_rule" "atlas_psc" {
  count = local.atlas_count

  name                  = "atlas-psc"
  region                = var.region
  network               = google_compute_network.main.id
  ip_address            = google_compute_address.atlas_psc[0].id
  target                = mongodbatlas_privatelink_endpoint.gcp[0].service_attachment_names[0]
  load_balancing_scheme = ""
}

resource "mongodbatlas_privatelink_endpoint_service" "gcp" {
  count = local.atlas_count

  project_id                  = mongodbatlas_project.main[0].id
  private_link_id             = mongodbatlas_privatelink_endpoint.gcp[0].private_link_id
  provider_name               = "GCP"
  endpoint_service_id         = google_compute_forwarding_rule.atlas_psc[0].name
  private_endpoint_ip_address = google_compute_address.atlas_psc[0].address
  gcp_project_id              = var.project_id
}

# --- Application user and connection string -------------------------------------
# The password is ephemeral and passed only through write-only arguments, so it never appears in
# Terraform state, plan files or CI logs. Bump mongodb_password_version to rotate it.

ephemeral "random_password" "mongodb" {
  count = local.atlas_count

  length  = 32
  special = false
}

resource "mongodbatlas_database_user" "booking_consumer" {
  count = local.atlas_count

  project_id          = mongodbatlas_project.main[0].id
  username            = "booking-consumer"
  auth_database_name  = "admin"
  password_wo         = ephemeral.random_password.mongodb[0].result
  password_wo_version = var.mongodb_password_version

  roles {
    role_name     = "readWrite"
    database_name = "ecabs"
  }

  scopes {
    name = mongodbatlas_advanced_cluster.main[0].name
    type = "CLUSTER"
  }
}

locals {
  atlas_private_srv = try(mongodbatlas_advanced_cluster.main[0].connection_strings.private_endpoint[0].srv_connection_string, "")
}

resource "google_secret_manager_secret_version" "mongodb_uri" {
  count = local.atlas_count

  secret                 = google_secret_manager_secret.mongodb_uri.id
  secret_data_wo_version = tostring(var.mongodb_password_version)
  secret_data_wo = format(
    "mongodb+srv://booking-consumer:%s@%s/ecabs?retryWrites=true&w=majority",
    ephemeral.random_password.mongodb[0].result,
    trimprefix(local.atlas_private_srv, "mongodb+srv://"),
  )

  lifecycle {
    precondition {
      condition     = local.atlas_private_srv != ""
      error_message = "Atlas has not published the private endpoint connection string yet; run `terraform apply` again once the endpoint is AVAILABLE."
    }
  }

  depends_on = [mongodbatlas_database_user.booking_consumer]
}
