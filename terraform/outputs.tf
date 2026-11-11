output "cluster_name" {
  description = "GKE cluster name (for `gcloud container clusters get-credentials --dns-endpoint`)."
  value       = google_container_cluster.main.name
}

output "cluster_location" {
  value = google_container_cluster.main.location
}

output "image_repository" {
  description = "Artifact Registry path that CI pushes images to."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "api_ip_address" {
  description = "Static IP of the public load balancer; point the api_domain A record here."
  value       = google_compute_global_address.api.address
}

output "certificate_dns_authorization" {
  description = "DNS record to create so Google can issue the API certificate."
  value = {
    name = google_certificate_manager_dns_authorization.api.dns_resource_record[0].name
    type = google_certificate_manager_dns_authorization.api.dns_resource_record[0].type
    data = google_certificate_manager_dns_authorization.api.dns_resource_record[0].data
  }
}

output "gateway_resource_names" {
  description = "Names the Kubernetes Gateway manifests reference."
  value = {
    static_ip       = google_compute_global_address.api.name
    certificate_map = google_certificate_manager_certificate_map.api.name
    ssl_policy      = google_compute_ssl_policy.modern.name
    security_policy = google_compute_security_policy.api.name
  }
}

output "booking_consumer_service_account" {
  description = "Google service account the booking-consumer Kubernetes service account is annotated with."
  value       = google_service_account.booking_consumer.email
}

output "github_workload_identity_provider" {
  description = "Value for `workload_identity_provider` in google-github-actions/auth."
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "ci_service_account" {
  description = "Service account GitHub Actions impersonates."
  value       = google_service_account.ci_deployer.email
}
