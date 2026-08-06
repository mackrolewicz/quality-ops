# Helm Charts

Production Helm charts for deploying to AKS.

Will be populated in Phase 5 (Cloud Native).

## Structure (planned)
```
qualityops/
├── Chart.yaml
├── values.yaml
├── values-staging.yaml
├── values-prod.yaml
└── templates/
    ├── api-deployment.yaml
    ├── api-service.yaml
    ├── worker-deployment.yaml
    ├── gateway-deployment.yaml
    ├── ingress.yaml
    └── configmap.yaml
```
