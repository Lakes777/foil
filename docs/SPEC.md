# Foil · especificação

> Decidido com o André numa entrevista (/grill-me) em 18/10/2026. Este arquivo é o contrato do projeto:
> cada fase começa lendo-o, e qualquer decisão que mudar no caminho muda aqui primeiro (com a data).
> Os números entre parênteses (P1...P29) são as perguntas da entrevista que geraram cada decisão.

## 1. O que é

Uma loja de cartas colecionáveis de um jogo fictício, feita como **vitrine de arquitetura de eventos**:
três serviços que conversam por Kafka, uma saga que desfaz os passos quando algo falha, e um lançamento
de carta rara em que centenas de compradores disputam poucas unidades **sem nunca vender a mais**.
Ninguém compra de verdade: o dinheiro é de mentira e o pagamento passa pelo [Fiel](https://github.com/Lakes777/fiel).

O que o projeto precisa provar (para o recrutador e na entrevista):

1. Não existe venda a mais, nem com 200 pedidos ao mesmo tempo.
2. Nenhum evento se perde nem é aplicado duas vezes, nem com um serviço caindo no meio.
3. Toda saga termina num estado final coerente: confirmada, ou desfeita com a carta devolvida.
4. Dá para ver tudo isso acontecendo ao vivo (painel de eventos e, no fim, métricas e traces).

## 2. Fora do escopo (de propósito)

- Contas e cadastro na loja: quem compra é visitante, identificado por cookie (P14).
- Roleta diária de cartas e mercado entre jogadores (P8). O **mercado** é a primeira fase extra se
  sobrar fôlego depois do núcleo (é uma segunda saga: carta de A para B, dinheiro de B para A).
- Dinheiro, CPF ou dado real; frete; notificações por e-mail.
- Avro e Schema Registry (P20): melhoria futura, depois do JSON versionado.
- Vários núcleos/instâncias: a VM tem 1 núcleo; nada de escalar horizontalmente na demo.
- Personagens, nomes ou arte de jogos reais (Pokémon etc.): o jogo e as cartas são inventados.

## 3. Restrições

- **Custo zero.** Roda na VM ARM da Oracle (1 núcleo, ~4,3 GB livres em 18/10), junto com Beacon,
  Pursuit, Cadence e Fiel.
- **Memória (P21):** cada serviço Spring com teto de ~300 MB (`-Xmx` na faixa do Fiel), Redpanda com
  512 MB e 1 núcleo, interface com Caddy ~32 MB. Observabilidade (fase 9) ~400 MB a mais. Folga mínima
  de 1 GB na VM depois de tudo.
- **Desenvolvimento no WSL com 3,8 GB:** suítes rodam um serviço por vez; vitest com `--maxWorkers=2`;
  Gradle/Maven sem daemon persistente quando possível.
- **Rede compartilhada `beacon_interna`:** todo serviço com apelido único `foil-*` (nunca depender do nome
  do serviço "api"/"banco"); Postgres pelo apelido `beacon-banco`.
- **Postgres do Beacon (P13):** três bancos (`foil_pedidos`, `foil_estoque`, `foil_pagamentos`), cada um
  com usuário próprio, dono do seu banco, **sem superpoder** e com `revoke connect ... from public`.

## 4. Tecnologia

| Parte | Escolha |
|---|---|
| Linguagem | Java 21, threads virtuais (P3, P21) |
| Framework | Spring Boot + Spring Kafka (P3) |
| Mensageria | Redpanda (protocolo do Kafka; o código usa o cliente Kafka padrão) (P2) |
| Banco | PostgreSQL 17 (o do Beacon), Flyway, SQL à mão onde a regra importa |
| Build | Maven multi-módulo, um repositório só (P19) |
| Testes | JUnit 5 + Testcontainers (Postgres e Redpanda de verdade); Vitest na interface |
| Interface | React + TypeScript + Vite, ao vivo por SSE (P15) |
| Observabilidade | OpenTelemetry, Prometheus, Grafana (público, só leitura), Jaeger (P29) |
| Deploy | Docker (imagens ARM montadas no PC), Caddy do Beacon, `/publicar-vm` |

Módulos: `contratos` (envelope e eventos, usado pelos três), `pedidos`, `estoque`, `pagamentos`, `web`,
`deploy/`.

## 5. Arquitetura

```
 visitante ── web (React) ──HTTP/SSE──> pedidos (orquestrador da saga)
                                            │  comandos / eventos (Redpanda)
                         ┌──────────────────┼──────────────────┐
                         v                                     v
                      estoque                              pagamentos ──HTTP──> Fiel
              (catálogo, edições, reservas)          (cobrança real ou simulada)  │
                                                              ^                   │
                                                              └──── webhook ──────┘
```

- **Saga orquestrada (P6):** o serviço de pedidos guarda a máquina de estados de cada pedido e manda
  comandos; estoque e pagamentos só respondem com eventos.
- **Outbox (P12):** todo evento/comando é gravado na mesma transação da mudança de estado e publicado
  depois por um processo do próprio serviço. Nunca `kafkaTemplate.send` dentro da regra de negócio.
- **Consumidores idempotentes (P12):** cada serviço guarda os ids de evento já processados (na mesma
  transação do efeito) e ignora repetidos.
- **Ordem (P24):** chave de partição = id do pedido; 3 partições por tópico.
- **Fila de mortos (P25):** depois de N tentativas com espera crescente, o evento vai para
  `<tópico>.mortos`, aparece no painel e pode ser reprocessado por um botão.

### Tópicos (rascunho; a fase 3 fecha os nomes)

| Tópico | Quem escreve | Quem lê |
|---|---|---|
| `estoque.comandos` | pedidos | estoque |
| `estoque.eventos` | estoque | pedidos |
| `pagamentos.comandos` | pedidos | pagamentos |
| `pagamentos.eventos` | pagamentos | pedidos |
| `pedidos.eventos` | pedidos | painel (SSE) e observabilidade |

### Envelope (P20)

```json
{ "id": "uuid do evento", "tipo": "EstoqueReservado", "versao": 1,
  "ocorridoEm": "2026-10-18T21:00:00Z", "pedidoId": "uuid", "dados": { } }
```

Mudança de formato = versão nova; consumidor aceita a versão atual e a anterior. Os contratos são
testados nos dois lados (quem escreve e quem lê usam o mesmo módulo `contratos`).

### Estados do pedido (rascunho; a fase 4 fecha)

```
CRIADO -> RESERVANDO -> AGUARDANDO_PAGAMENTO -> CONFIRMADO
              |                  |    \
              v                  |     -> (prazo de 5 min vencido) -> LIBERANDO -> CANCELADO
        SEM_ESTOQUE (final)      v
                       PAGAMENTO_RECUSADO -> LIBERANDO -> CANCELADO
```

Estados finais: `CONFIRMADO`, `SEM_ESTOQUE`, `CANCELADO`. Nenhum pedido fica parado num estado
intermediário: todo estado intermediário tem prazo e um caminho de saída.

## 6. Regras de negócio

- **Reserva atômica (P23):** `update edicao set disponivel = disponivel - 1 where id = ? and disponivel > 0`;
  zero linhas afetadas = sem estoque. Mais um `check (disponivel >= 0)` no banco como última trava.
- **Prazo de pagamento (P11):** a reserva vale 5 minutos; vencido o prazo sem pagamento, a saga cancela
  e devolve a unidade. Pagamento que chega depois do cancelamento é estornado pelo Fiel (ou registrado
  como anomalia, a decidir na fase 5).
- **Edições (P28):** cada lançamento cria uma edição nova da carta (ex.: 10 unidades); o estoque de um
  lançamento não interfere no próximo.
- **Lançamento (P7, P27):** "Simular lançamento" solta ~200 compradores-robôs contra uma edição de 10.
  Um lançamento por vez no site inteiro (fila visível), no máximo 1 por minuto por IP, robôs com ritmo
  controlado para não travar o núcleo da VM.
- **Pagamento dos robôs (P10):** simulado dentro do serviço de pagamentos, com falhas sorteadas (para
  exercitar a compensação). Só a compra manual passa pelo Fiel.

## 7. Integração com o Fiel (P5, P9, P17, P18)

- **No Fiel (fase 5, commit no repositório do Fiel):** chave de integração por variável de ambiente
  (como a do Beacon: só cria e lê cobranças; comparada em tempo constante) e conta fixa **"Loja Foil"**,
  garantida na partida (sobrevive ao reset diário das 4h, como a Loja Exemplo).
- **Compra manual:** pagamentos cria a cobrança no Fiel pela chave; o visitante paga pelo QR em
  `fiel.andrelagos.dev/pagar` como visitante do Fiel; o webhook do Fiel (HMAC com carimbo de tempo)
  avisa o Foil, que confere a assinatura.
- **Reset do Fiel às 4h:** o Foil recadastra o webhook pela chave ao subir e todo dia às 4h05, guardando
  o segredo novo. A limpeza do Foil roda às 4h10 (P28).

## 8. Demonstração

- `foil.andrelagos.dev`, só demo, sem cadastro.
- Telas: loja (cartas, compra, QR do Fiel), **painel de eventos** (cada saga passo a passo, ao vivo) e o
  lançamento com estoque caindo ao vivo, vendidos e recusados.
- Identidade visual própria e o jogo fictício (nome, cartas, arte) decididos na fase 7.
- Limpeza diária às 4h10: pedidos, edições e eventos antigos.

## 9. Fases e critérios de aceite

Cada fase: testes verdes no CI, revisor antes do commit, contraprovas nas regras importantes,
README atualizado. Uma sessão nova por fase, começando por este arquivo.

| Fase | Entrega | Pronto quando |
|---|---|---|
| 1. Esqueleto | Maven multi-módulo, 3 serviços com `/saude`, Flyway, módulo `contratos`, Testcontainers (Postgres + Redpanda), CI, repositório público | CI verde; cada serviço sobe e responde `/saude` num teste com contêineres reais |
| 2. Estoque | Cartas, edições, reserva atômica, devolução | Teste: 200 reservas simultâneas numa edição de 10 = exatamente 10 sucessos e `disponivel = 0`; contraprova sem o `where disponivel > 0` quebra o teste |
| 3. Eventos | Outbox nos três serviços, envelope, consumidores idempotentes, partição por pedido | Teste: o mesmo evento entregue 2x tem efeito 1x; queda entre gravar e publicar não perde evento |
| 4. Saga | Máquina de estados, compensações, prazo de 5 min, fila de mortos + reprocessar | Teste: **derrubar pagamentos no meio** e, ao voltar, todas as sagas terminam em estado final; nenhum pedido preso |
| 5. Pagamento | Fase no Fiel (chave + Loja Foil) e pagamentos do Foil (cobrança, webhook, recadastro 4h05, robôs) | Compra manual paga pelo QR do Fiel vira `CONFIRMADO`; pagamento recusado desfaz a saga |
| 6. Lançamento | Simulador, fila global, limite por IP, ritmo, SSE | Lançamento de 200 robôs termina com 10 vendas, o resto recusado ou desfeito, sem pedido preso |
| 7. Interface | Loja, painel de eventos, identidade, jogo fictício | Tudo ao vivo; acessível por teclado; GIF e prints no README |
| 8. Publicação | VM ARM via `/publicar-vm`, apelidos `foil-*`, usuários próprios no Postgres, limpeza 4h10 | No ar com HTTPS; os outros sites da VM continuam respondendo certo; Vigil monitorando |
| 9. Observabilidade | OpenTelemetry, Prometheus, Grafana público só leitura, Jaeger | Painel público com vendas/s, sagas desfeitas e atraso do consumidor; trace de uma saga visível de ponta a ponta |

Depois da fase 9: currículo, portfólio, perfil e guia (`/atualizar-os-quatro`).

## 10. Riscos conhecidos

- **1 núcleo na VM:** três JVMs + Redpanda disputando CPU; o lançamento precisa de ritmo controlado
  (P27) e a fase 8 mede antes de abrir ao público. Se não couber, o Jaeger sai primeiro (P29).
- **Memória do WSL:** Testcontainers com Redpanda + Postgres por serviço; rodar um módulo por vez.
- **Prioridade (P22):** se os documentos do Subras chegarem, o Foil pausa na fase em que estiver.
