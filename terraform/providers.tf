provider "google" {
  project = var.project_id
  region  = var.region

  default_labels = {
    app         = "ecabs-booking"
    environment = var.environment
    managed-by  = "terraform"
  }
}

# Credentials come from MONGODB_ATLAS_CLIENT_ID / MONGODB_ATLAS_CLIENT_SECRET (an Atlas service account).
provider "mongodbatlas" {}
