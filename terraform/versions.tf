terraform {
  # 1.11+ for write-only arguments: database passwords never reach state or plan files.
  required_version = ">= 1.11"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.11"
    }
    mongodbatlas = {
      source  = "mongodb/mongodbatlas"
      version = "~> 2.21"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
