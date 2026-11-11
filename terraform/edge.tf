# Public entry point for the booking API. The GKE Gateway (k8s/) builds a global external Application Load
# Balancer on this IP, using the certificate map, TLS policy and Cloud Armor policy defined here by name.

resource "google_compute_global_address" "api" {
  name = "ecabs-api"

  depends_on = [google_project_service.this]
}

# Google-managed certificate, validated through a DNS record so it can be issued before traffic is switched.
resource "google_certificate_manager_dns_authorization" "api" {
  name   = "ecabs-api"
  domain = var.api_domain

  depends_on = [google_project_service.this]
}

resource "google_certificate_manager_certificate" "api" {
  name = "ecabs-api"

  managed {
    domains            = [var.api_domain]
    dns_authorizations = [google_certificate_manager_dns_authorization.api.id]
  }
}

resource "google_certificate_manager_certificate_map" "api" {
  name = "ecabs-api"

  depends_on = [google_project_service.this]
}

resource "google_certificate_manager_certificate_map_entry" "api" {
  name         = "ecabs-api"
  map          = google_certificate_manager_certificate_map.api.name
  hostname     = var.api_domain
  certificates = [google_certificate_manager_certificate.api.id]
}

resource "google_compute_ssl_policy" "modern" {
  name            = "ecabs-modern-tls"
  profile         = "MODERN"
  min_tls_version = "TLS_1_2"

  depends_on = [google_project_service.this]
}

resource "google_compute_security_policy" "api" {
  name        = "ecabs-api"
  description = "Per-client rate limiting and WAF for the booking API"

  advanced_options_config {
    json_parsing = "STANDARD"
    log_level    = "NORMAL"
  }

  # One client cannot flood the API. Many phones can share a carrier NAT IP, so the limit is generous;
  # once clients authenticate, key this on the client identity instead of the IP.
  rule {
    action      = "throttle"
    priority    = 1000
    description = "Per-IP rate limit"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }

    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"

      rate_limit_threshold {
        count        = var.api_rate_limit_per_minute
        interval_sec = 60
      }
    }
  }

  # OWASP rules start in preview (log only): enforcing them before observing real traffic risks blocking
  # legitimate bookings, which is worse than the attack they prevent. Switch preview off once the logs are clean.
  rule {
    action      = "deny(403)"
    priority    = 2000
    description = "OWASP SQL injection and XSS (preview)"
    preview     = true

    match {
      expr {
        expression = "evaluatePreconfiguredWaf('sqli-v33-stable', {'sensitivity': 1}) || evaluatePreconfiguredWaf('xss-v33-stable', {'sensitivity': 1})"
      }
    }
  }

  rule {
    action      = "allow"
    priority    = 2147483647
    description = "Default allow"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }

  depends_on = [google_project_service.this]
}
