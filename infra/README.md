# novel-infra

Deployment and infrastructure configuration for **MeTruyenChu** platform.

## Structure

```
infra/
├── docker-compose/       # Local dev compose files
│   ├── infra.yml         # PostgreSQL, Redis, RabbitMQ, MinIO
│   └── full-stack.yml    # All services
├── k8s/
│   └── helm/             # Helm charts for production
├── scripts/
│   ├── init-dbs.sh       # Database initialization
│   └── backup.sh         # Backup scripts
└── ...
```
