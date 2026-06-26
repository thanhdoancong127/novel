# MeTruyenChu Platform

A Vietnamese web novel reading platform with TTS audio support.

## Monorepo Structure

```
novel/
├── docs/          → github.com/thanhdoancong127/novel-docs
│                  Documentation, specs, architecture
├── backend/       → github.com/thanhdoancong127/novel-backend
│                  Spring Boot microservices (Java 21)
├── frontend/      → github.com/thanhdoancong127/novel-frontend
│                  Next.js application (TypeScript)
└── infra/         → github.com/thanhdoancong127/novel-infra
                   Docker Compose, K8s, CI/CD scripts
```

## Quick Start

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/thanhdoancong127/novel.git

# Or after cloning, init submodules
git submodule update --init --recursive
```

See each sub-repo's README for detailed setup instructions.
