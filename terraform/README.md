# Terraform: GCP platform

Builds the Google Cloud footprint for one environment (one GCP project per environment). Workloads inside the
cluster (RabbitMQ, KEDA, the two services) are Kubernetes manifests in [`../k8s`](../k8s), applied by CI.

| File | Creates |
|---|---|
| `network.tf` | VPC, cluster subnet with pod and service ranges, Cloud NAT for outbound traffic from private nodes |
| `gke.tf` | GKE Autopilot cluster: private nodes, IAM-only DNS control-plane endpoint, Workload Identity, Secret Manager add-on, Gateway API, upgrade window Tue–Thu early morning; least-privilege node service account |
| `registry.tf` | Artifact Registry Docker repository with immutable tags and cleanup policies |
| `iam.tf` | Workload identity for `booking-consumer`; keyless GitHub Actions deployer (Workload Identity Federation, main branch only) |
| `secrets.tf` | Secret Manager secret for the consumer's MongoDB connection string, readable only by the consumer |
| `atlas.tf` | MongoDB Atlas project, auto-scaling replica set on GCP, Private Service Connect endpoint, application user; the connection string goes straight into Secret Manager |
| `edge.tf` | Static IP, Google-managed certificate, modern TLS policy and Cloud Armor policy (per-IP rate limit, OWASP rules in preview) for the API Gateway |
| `apis.tf` | Required Google APIs |

## Reviewer quick start (validate and plan, nothing applied)

Production state lives in a GCS bucket. To plan without one, add a local-backend override (`*_override.tf` is
git-ignored). Planning makes no Google or Atlas API calls, so a placeholder token works if you have no `gcloud` login.

```bash
cd terraform
printf 'terraform {\n  backend "local" {}\n}\n' > backend_override.tf

terraform init
terraform validate
GOOGLE_OAUTH_ACCESS_TOKEN=placeholder terraform plan -var-file=prod.tfvars
```

PowerShell:

```powershell
cd terraform
Set-Content backend_override.tf "terraform {`n  backend `"local`" {}`n}"

terraform init
terraform validate
$env:GOOGLE_OAUTH_ACCESS_TOKEN = "placeholder"; terraform plan '-var-file=prod.tfvars'
```

The plan shows 44 resources. The MongoDB Atlas provider warns that no credentials are set; that is expected.
Use `-var-file=demo.tfvars.example` to see the cheaper demo shape without Atlas (36 resources).

## Using it for real

1. Create the state bucket once per project (versioned, so earlier state can be recovered; GCS locks state natively):

   ```bash
   gcloud storage buckets create gs://PROJECT_ID-tfstate --location=europe-west1 \
     --uniform-bucket-level-access --public-access-prevention
   gcloud storage buckets update gs://PROJECT_ID-tfstate --versioning
   ```

2. Provide Atlas credentials (an Atlas service account with Project Creator on the organisation):
   `MONGODB_ATLAS_CLIENT_ID` and `MONGODB_ATLAS_CLIENT_SECRET`.

3. Initialise against the bucket and apply:

   ```bash
   terraform init -backend-config="bucket=PROJECT_ID-tfstate" -backend-config="prefix=ecabs"
   terraform apply -var-file=prod.tfvars
   ```

   On a first apply, Atlas can take a few minutes to publish the private connection string; if the
   `mongodb_uri` precondition fails, run `terraform apply` again.

4. DNS (Cloudflare, records set to **DNS only**, not proxied, so Cloud Armor sees real client IPs and Google
   terminates TLS):
   - the CNAME from the `certificate_dns_authorization` output, so Google can issue the certificate;
   - an A record for `api_domain` pointing at `api_ip_address`.

5. Configure CI with the `github_workload_identity_provider` and `ci_service_account` outputs.

## Secrets

- The application's MongoDB password is generated as an ephemeral value and passed only through write-only
  arguments: it is never stored in Terraform state, plan files or CI logs.
- To rotate it, increment `mongodb_password_version`; Terraform sets a new password in Atlas and adds a new Secret
  Manager version. Then `kubectl -n ecabs rollout restart deployment/booking-consumer`, because Spring reads the
  mounted file at startup. (The old password stops working immediately; a zero-downtime rotation would use two
  database users alternately, noted as future work.)
- Without Atlas (`enable_atlas = false`), add the connection string by hand:
  `printf '%s' 'mongodb+srv://...' | gcloud secrets versions add booking-consumer-mongodb-uri --data-file=-`
