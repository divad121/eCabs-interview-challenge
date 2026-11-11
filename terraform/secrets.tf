# The consumer's MongoDB connection string. Mounted into the pod at /etc/ecabs/secrets/spring.mongodb.uri
# by the GKE Secret Manager add-on, where Spring's configtree import picks it up.
resource "google_secret_manager_secret" "mongodb_uri" {
  secret_id = "booking-consumer-mongodb-uri"

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.this]
}

# Access is granted on this one secret only, not project-wide.
resource "google_secret_manager_secret_iam_member" "mongodb_uri_consumer" {
  secret_id = google_secret_manager_secret.mongodb_uri.id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.booking_consumer.member
}
