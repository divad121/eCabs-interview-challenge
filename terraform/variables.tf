variable "project_id" {
  description = "GCP project that hosts this environment (one project per environment)."
  type        = string
}

variable "region" {
  description = "GCP region for the cluster, registry and network."
  type        = string
  default     = "europe-west1"
}

variable "environment" {
  description = "Environment name, used in labels and resource descriptions."
  type        = string
}

variable "cluster_deletion_protection" {
  description = "Block `terraform destroy` of the GKE cluster. Keep true outside throwaway environments."
  type        = bool
  default     = true
}

variable "api_domain" {
  description = "Public hostname of the booking API; a Google-managed certificate is issued for it."
  type        = string
}

variable "github_repository" {
  description = "GitHub repository (owner/name) whose main-branch workflows may push images and deploy."
  type        = string
}

variable "api_rate_limit_per_minute" {
  description = "Requests per minute allowed from one client IP before Cloud Armor answers 429."
  type        = number
  default     = 1200
}

variable "subnet_cidr" {
  description = "Primary range of the cluster subnet (nodes and internal endpoints)."
  type        = string
  default     = "10.10.0.0/20"
}

variable "pods_cidr" {
  description = "Secondary range for pod IPs."
  type        = string
  default     = "10.20.0.0/14"
}

variable "services_cidr" {
  description = "Secondary range for Service IPs."
  type        = string
  default     = "10.24.0.0/20"
}

variable "atlas_psc_ip" {
  description = "Internal IP (in subnet_cidr) for the Private Service Connect endpoint to MongoDB Atlas."
  type        = string
  default     = "10.10.15.250"
}

variable "enable_atlas" {
  description = "Manage the MongoDB Atlas project and cluster. Requires Atlas service-account credentials in the environment."
  type        = bool
  default     = true
}

variable "atlas_org_id" {
  description = "MongoDB Atlas organisation ID."
  type        = string
  default     = ""

  validation {
    condition     = !var.enable_atlas || can(regex("^[0-9a-f]{24}$", var.atlas_org_id))
    error_message = "atlas_org_id must be a 24-character hex Atlas organisation ID when enable_atlas is true."
  }
}

variable "atlas_region" {
  description = "Atlas name of the GCP region for the cluster (WESTERN_EUROPE is europe-west1)."
  type        = string
  default     = "WESTERN_EUROPE"
}

variable "atlas_instance_size" {
  description = "Baseline Atlas tier; compute auto-scaling may raise it up to atlas_max_instance_size."
  type        = string
  default     = "M30"
}

variable "atlas_max_instance_size" {
  description = "Upper bound for Atlas compute auto-scaling."
  type        = string
  default     = "M50"
}

variable "mongodb_password_version" {
  description = "Increment to rotate the application's MongoDB password (regenerates it in Atlas and Secret Manager)."
  type        = number
  default     = 1
}
