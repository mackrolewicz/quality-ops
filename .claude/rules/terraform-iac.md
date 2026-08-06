---
paths:
  - "infra/terraform/**"
---

# Terraform / IaC rules

- Always use modules for reusable infrastructure — never inline resources in environment configs.
- Pin provider versions in `versions.tf` — never use `latest`.
- Tag every resource: `project`, `environment`, `managed_by = "terraform"`.
- Mark sensitive variables with `sensitive = true`.
- Never commit `.tfstate`, `.tfvars` with real values, or `.terraform/`.
- Always use remote state with locking (Azure Storage backend).
- Run `terraform fmt` and `terraform validate` before committing.
- Staging and production must use the same modules with different variable values.
- Reference secrets from Azure Key Vault data sources — never hardcode.
- Every module must have `main.tf`, `variables.tf`, `outputs.tf`.
- Name resources: `{type}-qualityops-{environment}` (e.g., `aks-qualityops-staging`).
