# API Endpoints


> ⚠️ **Lista parcial (conferido em 04/10/2026).** Este arquivo lista **5** endpoints; o sistema tem
> cerca de **143**. Ele nunca foi mantido e não serve como referência da API.
>
> A fonte de verdade são os controllers em `src/main/java/br/com/bora/controller/`. O Swagger existe,
> mas nasce **desligado** (ligue com `SPRINGDOC_API_DOCS_ENABLED=true` em desenvolvimento).
>
> Regra que vale para toda a API: **a loja vem sempre do token**, nunca de parâmetro na URL.
- GET /api/health
- GET /api/pedidos
- POST /api/pedidos
- PATCH /api/pedidos/{id}/status?status=ENTREGUE
- GET /api/dashboard/resumo   (a loja vem do token, NUNCA da URL — mandar `?lojaId=` não muda nada)
