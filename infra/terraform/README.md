# Terraform — Infrastructure as Code

Provisions all Azure cloud resources for QualityOps Lab.

## Structure

```
terraform/
├── modules/          # Reusable: aks, database, redis, networking, keyvault, container-registry
│   └── <module>/
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
└── environments/     # Per-env config (staging, production)
    └── <env>/
        ├── main.tf
        ├── variables.tf
        ├── terraform.tfvars   # gitignored — real values
        └── backend.tf         # remote state config
```

## What Terraform manages

AKS cluster, Azure Database for PostgreSQL, Azure Cache for Redis,
VNet/subnets, Azure Key Vault, Azure Container Registry, DNS.

## What Terraform does NOT manage

App deployments inside Kubernetes — that's Helm's job.

## Hard rules

- Same modules for staging and production, different variables.
- Remote state in Azure Storage with locking.
- Never commit `.tfstate` or `.tfvars` with secrets.
- Tag all resources: `project`, `environment`, `managed_by`.

## Populated in Phase 5 of the roadmap.
