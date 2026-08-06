---
name: infrastructure-as-code
description: Use this skill when working with Terraform, Bicep, or any infrastructure provisioning. Covers IaC patterns, Terraform modules, Azure resource provisioning, state management, and the progression from Docker Compose to managed cloud infrastructure.
---

# Infrastructure as Code (IaC) patterns

This skill covers how cloud infrastructure is provisioned and managed
in this project.

## 1. IaC progression

| Phase | Tool | What it manages |
|---|---|---|
| Phase 1-2 | Docker Compose | Local dev: Postgres, Redis, Kafka |
| Phase 5 | Terraform | Azure: AKS, DB, Redis, networking, DNS |
| Phase 5 | Helm | Kubernetes: app deployments, services, ingress |
| Phase 5+ | Terraform + Helm | Full stack: infra + app deployment |

## 2. Project structure

```
infra/
├── terraform/
│   ├── environments/
│   │   ├── staging/
│   │   │   ├── main.tf              # staging-specific config
│   │   │   ├── variables.tf
│   │   │   ├── terraform.tfvars     # staging values (gitignored)
│   │   │   └── backend.tf           # remote state config
│   │   └── production/
│   │       ├── main.tf
│   │       ├── variables.tf
│   │       ├── terraform.tfvars     # prod values (gitignored)
│   │       └── backend.tf
│   ├── modules/
│   │   ├── aks/                     # AKS cluster module
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── outputs.tf
│   │   ├── database/                # Azure Database for PostgreSQL
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── outputs.tf
│   │   ├── redis/                   # Azure Cache for Redis
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── outputs.tf
│   │   ├── networking/              # VNet, subnets, NSGs
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── outputs.tf
│   │   ├── keyvault/                # Azure Key Vault
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── outputs.tf
│   │   └── container-registry/      # Azure Container Registry
│   │       ├── main.tf
│   │       ├── variables.tf
│   │       └── outputs.tf
│   └── shared/
│       └── versions.tf              # provider versions, shared config
├── helm/                            # app deployment (existing)
├── docker/                          # Dockerfiles (existing)
└── compose/                         # local dev (existing)
```

## 3. Terraform basics

### Provider configuration

```hcl
# versions.tf
terraform {
  required_version = ">= 1.7"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.100"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}
```

### Resource group

```hcl
resource "azurerm_resource_group" "main" {
  name     = "rg-qualityops-${var.environment}"
  location = var.location

  tags = {
    project     = "qualityops"
    environment = var.environment
    managed_by  = "terraform"
  }
}
```

### AKS cluster module

```hcl
# modules/aks/main.tf
resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-qualityops-${var.environment}"
  location            = var.location
  resource_group_name = var.resource_group_name
  dns_prefix          = "qualityops-${var.environment}"
  kubernetes_version  = var.kubernetes_version

  default_node_pool {
    name                = "system"
    node_count          = var.node_count
    vm_size             = var.vm_size
    enable_auto_scaling = true
    min_count           = var.min_nodes
    max_count           = var.max_nodes
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin = "azure"
    network_policy = "calico"
  }

  tags = var.tags
}
```

### PostgreSQL

```hcl
# modules/database/main.tf
resource "azurerm_postgresql_flexible_server" "main" {
  name                   = "psql-qualityops-${var.environment}"
  resource_group_name    = var.resource_group_name
  location               = var.location
  version                = "16"
  administrator_login    = var.admin_username
  administrator_password = var.admin_password
  storage_mb             = var.storage_mb
  sku_name               = var.sku_name

  zone = "1"

  tags = var.tags
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = "qualityops"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}
```

### Redis

```hcl
# modules/redis/main.tf
resource "azurerm_redis_cache" "main" {
  name                = "redis-qualityops-${var.environment}"
  resource_group_name = var.resource_group_name
  location            = var.location
  capacity            = var.capacity
  family              = var.family
  sku_name            = var.sku_name

  minimum_tls_version = "1.2"

  redis_configuration {
    maxmemory_policy = "allkeys-lru"
  }

  tags = var.tags
}
```

### Using modules from environments

```hcl
# environments/staging/main.tf
module "networking" {
  source              = "../../modules/networking"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment         = "staging"
}

module "aks" {
  source              = "../../modules/aks"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment         = "staging"
  node_count          = 2
  min_nodes           = 1
  max_nodes           = 5
  vm_size             = "Standard_D2s_v3"
  kubernetes_version  = "1.29"
}

module "database" {
  source              = "../../modules/database"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment         = "staging"
  admin_username      = var.db_admin_username
  admin_password      = var.db_admin_password
  sku_name            = "B_Standard_B1ms"
  storage_mb          = 32768
}

module "redis" {
  source              = "../../modules/redis"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment         = "staging"
  capacity            = 0
  family              = "C"
  sku_name            = "Basic"
}
```

## 4. Remote state

Never store Terraform state locally in a team project:

```hcl
# backend.tf
terraform {
  backend "azurerm" {
    resource_group_name  = "rg-qualityops-tfstate"
    storage_account_name = "stqualityopstfstate"
    container_name       = "tfstate"
    key                  = "staging.terraform.tfstate"
  }
}
```

## 5. Variables and secrets

```hcl
# variables.tf
variable "environment" {
  type        = string
  description = "Environment name: staging or production"
}

variable "db_admin_password" {
  type        = string
  sensitive   = true
  description = "PostgreSQL admin password"
}
```

**Rules:**
- Never commit `terraform.tfvars` with real values — gitignore it.
- Use `sensitive = true` for passwords and keys.
- Store secrets in Azure Key Vault, reference via data sources.
- Use environment-specific `.tfvars` files.

## 6. Terraform workflow

```bash
# Initialize (download providers, configure backend)
cd infra/terraform/environments/staging
terraform init

# Preview changes
terraform plan -var-file="terraform.tfvars"

# Apply changes
terraform apply -var-file="terraform.tfvars"

# Destroy (careful!)
terraform destroy -var-file="terraform.tfvars"
```

### CI/CD integration

```yaml
# .github/workflows/terraform.yml
jobs:
  terraform:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
      - run: terraform init
        working-directory: infra/terraform/environments/staging
      - run: terraform plan -no-color
        working-directory: infra/terraform/environments/staging
      # Apply only on merge to main (manual approval recommended)
```

## 7. Hard rules

- **Never** apply Terraform changes without running `plan` first.
- **Never** commit `.tfstate` files or `.tfvars` with secrets.
- **Always** use remote state with locking (Azure Storage + blob lease).
- **Always** use modules for reusable infrastructure (DRY).
- **Always** tag all resources with project, environment, managed_by.
- **Always** pin provider versions (don't use `latest`).
- Use `terraform fmt` before committing (auto-format).
- Use `terraform validate` in CI.
- Staging and production use the **same modules** with different variables.

## 8. Terraform vs Bicep

| | Terraform | Bicep |
|---|---|---|
| Multi-cloud | Yes (AWS, GCP, Azure) | Azure only |
| State management | Required (remote backend) | Handled by Azure |
| Learning value | Higher (industry standard) | Lower (Azure-specific) |
| Complexity | More setup | Simpler for Azure-only |
| Recommendation | **Use this** — more transferable skills | Only if you go Azure-native |

## 9. What Terraform manages vs. what Helm manages

| Concern | Terraform | Helm |
|---|---|---|
| AKS cluster | Yes | No |
| PostgreSQL server | Yes | No |
| Redis instance | Yes | No |
| VNet / subnets | Yes | No |
| Container registry | Yes | No |
| Key Vault | Yes | No |
| DNS records | Yes | No |
| App deployments | No | Yes |
| Kubernetes services | No | Yes |
| Ingress rules | No | Yes |
| ConfigMaps | No | Yes |

**Rule:** Terraform for cloud resources. Helm for what runs inside Kubernetes.
