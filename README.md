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
| 2. Estoque | Cartas, edições, reserva atômica, devolução | feita |
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
| `estoque` | Cartas, edições, reserva e devolução; `GET /cartas`, `/cartas/{id}/edicoes`, `/edicoes/{id}` (porta 8092) |
| `pagamentos` | Cobranças pelo Fiel ou simuladas (porta 8093) |

Cada serviço tem o próprio banco (`foil_pedidos`, `foil_estoque`, `foil_pagamentos`) e as próprias migrações Flyway.

## Como o estoque não vende a mais

A reserva é uma linha de SQL só, e quem decide se ainda tem carta é o banco:

```sql
update edicao set disponivel = disponivel - 1 where id = :edicao and disponivel > 0
-- zero linhas alteradas = acabou
```

Mais duas travas: um `check (disponivel >= 0 and disponivel <= total)` na tabela e a chave primária da reserva no
id do pedido, que impede o mesmo pedido de levar duas unidades. A reserva guarda o resultado (`reservada`,
`sem_estoque`, `devolvida`), então o mesmo pedido repetido recebe sempre a mesma resposta: quem ficou sem estoque
não leva depois uma unidade devolvida por outro. A devolução só acontece para quem consegue trocar `reservada` por
`devolvida`, então devolver duas vezes devolve uma unidade só.

O teste solta **200 pedidos ao mesmo tempo numa edição de 10** e exige exatamente 10 reservas, 190 "sem estoque",
nenhum erro e `disponivel = 0`. As contraprovas (o código trocado de propósito, e o teste tem de quebrar):

| Versão trocada | Resultado |
|---|---|
| Sem o `and disponivel > 0` | 190 pedidos estouram o `check` do banco: erro em vez de "sem estoque" |
| Apagar a reserva quando fica sem estoque (em vez de guardar o resultado) | O pedido repetido leva uma unidade devolvida depois, e a saga dele já tinha acabado |
| Jeito ingênuo: lê o disponível em Java e grava `disponivel = lido - 1` | **20 e 15 vendas** em duas rodadas, numa edição de 10, e o `check` não pega, porque o número nunca fica negativo |

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
