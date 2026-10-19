# Foil

Loja de cartas colecionáveis de um jogo fictício, feita como **vitrine de arquitetura de eventos**: três
serviços que conversam por Kafka (Redpanda), uma saga que desfaz os passos quando algo falha e um lançamento
de carta rara em que centenas de compradores disputam poucas unidades **sem nunca vender a mais**. O
pagamento passa pelo [Fiel](https://github.com/Lakes777/fiel), com dinheiro de mentira.

> Em construção, uma fase por vez. A especificação completa (decisões, fases e critérios de aceite) está em
> [docs/SPEC.md](docs/SPEC.md).

## Andamento

| Fase | Entrega | Situação |
|---|---|---|
| 1. Esqueleto | Maven multi-módulo, 3 serviços com `/saude`, Flyway, contratos, Testcontainers, CI | feita |
| 2. Estoque | Cartas, edições, reserva atômica, devolução | |
| 3. Eventos | Outbox, envelope, consumidores idempotentes, partição por pedido | |
| 4. Saga | Máquina de estados, compensações, prazo de 5 min, fila de mortos | |
| 5. Pagamento | Integração com o Fiel (cobrança, webhook) | |
| 6. Lançamento | 200 robôs contra 10 unidades, ao vivo | |
| 7. Interface | Loja e painel de eventos | |
| 8. Publicação | foil.andrelagos.dev | |
| 9. Observabilidade | OpenTelemetry, Prometheus, Grafana, Jaeger | |

## Módulos

| Módulo | O que tem |
|---|---|
| `contratos` | Envelope dos eventos (`id`, `tipo`, `versao`, `ocorridoEm`, `pedidoId`, `dados`) e nomes dos tópicos |
| `comum` | Base dos três serviços: `GET /saude` (200 só com banco e Redpanda respondendo; 503 dizendo qual caiu) e, nos testes, os contêineres |
| `pedidos` | Orquestrador da saga (porta 8091) |
| `estoque` | Cartas, edições e reservas (porta 8092) |
| `pagamentos` | Cobranças pelo Fiel ou simuladas (porta 8093) |

Cada serviço tem o próprio banco (`foil_pedidos`, `foil_estoque`, `foil_pagamentos`) e as próprias migrações Flyway.

## Tecnologia

Java 21 (threads virtuais) · Spring Boot 4 · Spring Kafka · Redpanda · PostgreSQL 17 · Flyway · JUnit 5 ·
Testcontainers · GitHub Actions

## Rodar

Precisa de Java 21 e Docker.

```bash
./mvnw verify                      # testes: sobe Postgres e Redpanda de verdade em contêineres

docker compose up -d               # Postgres (5434), Redpanda (19092) e o console do Redpanda (8090)
./mvnw install -DskipTests
./mvnw -pl estoque spring-boot:run -Dspring-boot.run.profiles=dev
curl localhost:8092/saude          # {"servico":"estoque","status":"ok","banco":"ok","kafka":"ok","migracao":"1"}
```
