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
| 3. Eventos | Outbox, envelope, consumidores idempotentes, partição por pedido | feita |
| 4. Saga | Máquina de estados, compensações, prazo de 5 min, fila de mortos | feita |
| 5. Pagamento | Integração com o Fiel (cobrança, webhook) | |
| 6. Lançamento | 200 robôs contra 10 unidades, ao vivo | |
| 7. Interface | Loja e painel de eventos | |
| 8. Publicação | foil.andrelagos.dev | |
| 9. Observabilidade | OpenTelemetry, Prometheus, Grafana, Jaeger | |

## Módulos

| Módulo | O que tem |
|---|---|
| `contratos` | Envelope dos eventos (`id`, `tipo`, `versao`, `ocorridoEm`, `pedidoId`, `dados`), tópicos e os comandos/eventos de cada serviço |
| `comum` | Base dos três serviços: `GET /saude` (200 só com banco e Redpanda respondendo; 503 dizendo qual caiu), outbox, consumidor idempotente, tópicos e, nos testes, os contêineres |
| `pedidos` | Orquestrador da saga: `POST /pedidos`, `GET /pedidos/{id}` (porta 8091) |
| `estoque` | Cartas, edições, reserva e devolução; `GET /cartas`, `/cartas/{id}/edicoes`, `/edicoes/{id}` (porta 8092) |
| `pagamentos` | Cobranças simuladas (o Fiel entra na fase 5), cancelamento e estorno (porta 8093) |
| `ponta-a-ponta` | Teste com os três serviços como processos de verdade, derrubando um deles |

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

## Como nenhum evento se perde nem é aplicado duas vezes

- **Outbox:** o serviço nunca publica no Redpanda dentro da regra de negócio. Ele grava o evento na tabela `saida`,
  na **mesma transação** da mudança de estado (gravar fora de uma transação é proibido e dá erro). Um publicador lê
  os pendentes em ordem e só marca como publicado depois da confirmação do Redpanda; no primeiro erro, para, para
  não passar um evento na frente do outro.
- **Consumidor idempotente:** quem consome anota o id do evento na tabela `evento_processado`, na mesma transação do
  efeito e da resposta. O mesmo id de novo não faz nada.
- **Ordem:** a chave de cada mensagem é o id do pedido, então tudo de um pedido cai na mesma partição (3 por tópico).

| Queda | O que acontece | Teste |
|---|---|---|
| Entre gravar e publicar | O evento fica pendente no banco e sai quando o serviço volta | `quedaEntreGravarEPublicarNaoPerdeOEvento` |
| Entre publicar e marcar | O evento sai duas vezes com o mesmo id; o consumidor ignora a segunda | `quedaEntrePublicarEMarcarRepublicaOMesmoId` + `oMesmoComandoEntregueDuasVezesTemEfeitoUmaVez` |
| Redpanda fora | Nada é marcado como publicado; o publicador desiste em segundos, sem travar | `redpandaForaDeixaOEventoPendente` |
| Banco do consumidor fora | A mensagem é tentada de novo para sempre, com espera crescente (até 30 s); a partição espera, nada se perde | `erroPassageiroEReaplicadoAteDarCertoENadaSePerde` (15 falhas seguidas) |
| Evento que nunca sai (grande demais) | Só os eventos seguintes do mesmo pedido esperam; os outros pedidos seguem | `eventoQueNaoSaiTravaSoOMesmoPedido` |

Mensagem com contrato quebrado (tipo ou versão desconhecidos, JSON inválido) não adianta tentar de novo: vai para a
fila de mortos, sem travar as seguintes.

Contraprovas: com o tratamento de erro padrão do Spring Kafka (10 tentativas sem espera), a mensagem passageira é
descartada e o teste quebra; sem a checagem do id repetido, o mesmo comando entregue duas vezes gera duas respostas
`UnidadeReservada`; sem a transação no consumidor, a gravação na saída é recusada; marcando como publicado mesmo
quando o envio falha, o evento se perde (o teste do Redpanda fora quebra).

Mensagem com contrato quebrado vai para a **fila de mortos** (`<tópico>.mortos`): cada serviço guarda as suas e
expõe `GET /mortos` e `POST /mortos/{id}/reprocessar`, que devolve a mensagem ao tópico de origem.

## A saga

O serviço de pedidos orquestra; estoque e pagamentos só respondem.

```
RESERVANDO ──UnidadeReservada──> AGUARDANDO_PAGAMENTO ──PagamentoAprovado──> CONFIRMADO
    │  └──ReservaRecusada──> SEM_ESTOQUE           │  └──PagamentoRecusado──────────────┐
    └──prazo (2 min)──┐                             └──prazo (5 min) + CancelarCobranca──┤
                      └──────────> LIBERANDO (DevolverUnidade) ──UnidadeDevolvida──> CANCELADO
                                    └──prazo (1 min): reenvia a devolução
```

- Cada passo trava o pedido, confere o estado e grava o estado novo e os próximos comandos numa transação só.
- Evento que chega tarde (depois do prazo) é ignorado: a compensação já está a caminho.
- Pagamento aprovado depois do prazo é **estornado**: o `CancelarCobranca` vai na mesma partição, depois do
  `CobrarPedido`; se a cobrança ainda nem existia, ela fica proibida.
- Devolução que chega antes da reserva (a reserva estava na fila de mortos e foi reprocessada depois) fica gravada
  como trava: a reserva atrasada é recusada em vez de prender uma unidade para sempre.

**O teste que importa** (`ponta-a-ponta/DerrubarPagamentosTest`): sobe pedidos, estoque e pagamentos como três
processos Java, com Postgres e Redpanda de verdade, e derruba pagamentos com `kill -9`.

| Cenário | Resultado exigido |
|---|---|
| 20 compras numa edição de 10 com pagamentos fora; pagamentos volta antes do prazo | Nenhum pedido preso; 10 sem estoque; cada `CONFIRMADO` tem uma cobrança aprovada e uma unidade a menos |
| 5 compras com pagamentos fora até o prazo vencer; depois ele volta | Os 5 cancelados, estoque cheio de novo, e **nenhuma cobrança aprovada**: as atrasadas foram estornadas ou proibidas |
| 60 compras; pagamentos cai com `kill -9` **no meio** (na rodada medida, 59 cobranças ainda pendentes) | Nenhum pedido preso; cobranças aprovadas = confirmados; estoque = 60 − confirmados |

O teste também achou um problema de operação: depois do `kill -9`, o pagamentos novo ficava **37 s** parado,
esperando a sessão do consumidor morto vencer (padrão de 45 s). Com sessão de 10 s, volta em ~9 s.

Contraprovas: aceitando "pagamento aprovado" em qualquer estado, um pedido cancelado volta a ser confirmado (dois
testes da saga quebram); sem o `CancelarCobranca` no prazo, as cobranças atrasadas ficam aprovadas para sempre (o
cenário 2 não termina); sem a trava da devolução, a reserva reprocessada prende a unidade.

## Tecnologia

Java 21 (threads virtuais) · Spring Boot 4 · Spring Kafka · Redpanda · PostgreSQL 17 · Flyway · JUnit 5 ·
Testcontainers · GitHub Actions

## Rodar

Precisa de Java 21 e Docker.

```bash
./mvnw verify                      # testes: sobe Postgres e Redpanda de verdade em contêineres (~5 min)

docker compose up -d               # Postgres (5434), Redpanda (19092) e o console do Redpanda (8090)
./mvnw install -DskipTests
./mvnw -pl estoque spring-boot:run -Dspring-boot.run.profiles=dev
curl localhost:8092/saude          # {"servico":"estoque","status":"ok","banco":"ok","kafka":"ok","migracao":"5"}
```
