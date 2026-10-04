# Bora — Fase 3 Back-end Java

Back-end Java 21 + Spring Boot 3 para SaaS multi-loja de pequenos deliveries.

## Rodar com Docker
```bash
docker compose up --build
```

## Endpoints principais
- GET /actuator/health  (público — é este que o monitoramento usa; `/api/health` exige login)
- GET /api/pedidos
- POST /api/pedidos
- PATCH /api/pedidos/{id}/status
- GET /api/dashboard/resumo
```
