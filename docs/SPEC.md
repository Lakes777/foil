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
  512 MB e 1 núcleo (20/10/2026, fase 8: `--memory=384M` num contêiner de 640 MB; serviços com 448 MB e `-Xmx256m`), interface com Caddy ~32 MB. Observabilidade (fase 9) ~400 MB a mais. Folga mínima
  de 1 GB na VM depois de tudo.
- **Um processador e threads virtuais (20/10/2026, achado na 1ª publicação):** no Java 21, a thread virtual que espera
  dentro de um `synchronized` prende o carregador; na VM a JVM via 1 processador = 1 carregador, e o `/saude`
  (synchronized esperando as duas conferências em paralelo) travava para sempre. Corrigido com `ReentrantLock` no
  `/saude` e no painel, `Carregadores.garantir()` no `main` (no mínimo 4, para um `synchronized` de biblioteca não parar
  o serviço) e o `ponta-a-ponta` subindo as JVMs com `-XX:ActiveProcessorCount=1` (sem a correção, trava no Java 21). Saída
  definitiva FEITA 21/10/2026: Java 25 no Foil e no Fiel (JEP 491, o synchronized deixa de prender). A trava, os 4
  carregadores e o teste com 1 processador ficam como segunda proteção (um synchronized ainda prende em frame nativo).
- **Desenvolvimento no WSL com 3,8 GB:** suítes rodam um serviço por vez; vitest com `--maxWorkers=2`;
  Gradle/Maven sem daemon persistente quando possível.
- **Rede compartilhada `beacon_interna`:** todo serviço com apelido único `foil-*` (nunca depender do nome
  do serviço "api"/"banco"); Postgres pelo apelido `beacon-banco`.
- **Postgres do Beacon (P13):** três bancos (`foil_pedidos`, `foil_estoque`, `foil_pagamentos`), cada um
  com usuário próprio, dono do seu banco, **sem superpoder** e com `revoke connect ... from public`.

## 4. Tecnologia

| Parte | Escolha |
|---|---|
| Linguagem | Java 25, threads virtuais (P3, P21; era Java 21 até 21/10/2026) |
| Framework | Spring Boot + Spring Kafka (P3) |
| Mensageria | Redpanda (protocolo do Kafka; o código usa o cliente Kafka padrão) (P2) |
| Banco | PostgreSQL 17 (o do Beacon), Flyway, SQL à mão onde a regra importa |
| Build | Maven multi-módulo, um repositório só (P19) |
| Testes | JUnit 5 + Testcontainers (Postgres e Redpanda de verdade); Vitest na interface |
| Interface | React + TypeScript + Vite, ao vivo por SSE (P15) |
| Observabilidade | OpenTelemetry, Prometheus, Grafana (público, só leitura), Jaeger (P29) |
| Deploy | Docker (imagens ARM montadas no PC), Caddy do Beacon, `/publicar-vm` |

Módulos: `contratos` (envelope e eventos, usado pelos três), `comum`, `pedidos`, `estoque`, `pagamentos`,
`web`, `deploy/`.

> 19/10/2026 (fase 1): entrou o módulo `comum`, com o que os três serviços têm igual (dependências de web,
> banco e Kafka, o `GET /saude` e, num test-jar, os contêineres dos testes), para não copiar o mesmo
> código três vezes. Regra: o `comum` não guarda regra de negócio; o que é de um serviço fica no serviço.
> Também na fase 1: Spring Boot 4.1.1, Redpanda v26.2.5 e a tabela `evento_processado` (P12) criada pela
> V1 de cada serviço. O WSL agora tem 8 GB (pente instalado em 19/10), mas a regra de um módulo por vez fica.

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

### Tópicos (fechados na fase 3, 19/10/2026: ficaram como no rascunho)

| Tópico | Quem escreve | Quem lê |
|---|---|---|
| `estoque.comandos` | pedidos | estoque |
| `estoque.eventos` | estoque | pedidos |
| `pagamentos.comandos` | pedidos | pagamentos |
| `pagamentos.eventos` | pagamentos | pedidos |
| `pedidos.eventos` | pedidos | painel (SSE) e observabilidade |

Estoque (fase 3): comandos `ReservarUnidade {edicaoId}` e `DevolverUnidade {}`; eventos `UnidadeReservada
{edicaoId}` (também para pedido repetido já reservado), `ReservaRecusada {edicaoId, motivo}` com motivo
`SEM_ESTOQUE`, `EDICAO_INEXISTENTE` ou `JA_DEVOLVIDA`, e `UnidadeDevolvida {devolvidaAgora}`. A outbox é a tabela
`saida` (publicada em ordem de `seq`, marcada só depois da confirmação; produtor com acks=all e idempotência).
Erro no consumidor (19/10/2026, achado do revisor): erro passageiro (banco ou Redpanda fora) é tentado de
novo para sempre, com espera crescente até 30 s, e a partição espera, então nada se perde. Erro de contrato
(tipo ou versão desconhecidos, JSON inválido) é descartado com log de erro, sem travar os seguintes; a fila de
mortos da fase 4 passa a guardá-lo. Na saída, um evento que não consegue sair trava só os eventos seguintes do
mesmo pedido; os outros pedidos seguem (a fase 4/9 precisa tornar isso visível no painel). As linhas já
publicadas da `saida` e os ids antigos de `evento_processado` entram na limpeza das 4h10 (fase 8).

### Envelope (P20)

```json
{ "id": "uuid do evento", "tipo": "EstoqueReservado", "versao": 1,
  "ocorridoEm": "2026-10-18T21:00:00Z", "pedidoId": "uuid", "dados": { } }
```

Mudança de formato = versão nova; consumidor aceita a versão atual e a anterior. (19/10/2026: enquanto tudo está na v1, o
consumidor aceita só a v1; a regra da versão anterior começa quando sair a primeira v2.) Os contratos são
testados nos dois lados (quem escreve e quem lê usam o mesmo módulo `contratos`).

### Estados do pedido (fechados na fase 4, 19/10/2026)

```
RESERVANDO ──UnidadeReservada──> AGUARDANDO_PAGAMENTO ──PagamentoAprovado──> CONFIRMADO
    │  └──ReservaRecusada──> SEM_ESTOQUE (final)   │  └──PagamentoRecusado──────────────┐
    └──prazo da reserva (2 min)──┐                  └──prazo do pagamento (5 min)        │
                                 │                     + CancelarCobranca ─────────────┤
                                 └──────────> LIBERANDO (DevolverUnidade) ──UnidadeDevolvida──> CANCELADO
                                               └──prazo (1 min): reenvia DevolverUnidade
```

Estados finais: `CONFIRMADO`, `SEM_ESTOQUE`, `CANCELADO` (com `motivo`: `PAGAMENTO_RECUSADO`,
`PAGAMENTO_CANCELADA`, `PRAZO_DA_RESERVA`, `PRAZO_DO_PAGAMENTO`; em `SEM_ESTOQUE`, o motivo do estoque). `CRIADO` saiu: o pedido já nasce `RESERVANDO`, com o comando
na saída, na mesma transação. Decisões da fase 4:

- Cada passo trava a linha do pedido (`for update`), confere o estado e grava estado + comandos numa
  transação. Evento que não cabe no estado atual é ignorado (chegou depois do prazo; a compensação já foi).
- Todo estado intermediário tem prazo (um verificador a cada segundo). Em `LIBERANDO` (achado do revisor),
  o prazo de 1 min **reenvia** o `DevolverUnidade`: a resposta pode ter caído na fila de mortos, e devolver de
  novo não devolve duas vezes.
- A ordem na partição não basta: o botão de reprocessar põe a mensagem no fim da fila. Por isso o
  `DevolverUnidade` leva a edição e, se ainda não houver reserva, o estoque **grava a devolução como trava**
  (um `ReservarUnidade` reprocessado depois recebe `JA_DEVOLVIDA` e não prende unidade). Mesma ideia da
  trava de cobrança em pagamentos.
- Consumidor morto sem avisar (kill -9) só perde as partições quando a sessão vence. Sessão de 10 s (o padrão
  de 45 s parava as cobranças por 37 s a cada queda, medido no teste de ponta a ponta).
- Prazo do pagamento vencido manda `CancelarCobranca` antes do `DevolverUnidade`. Em pagamentos: cobrança
  pendente é cancelada, aprovada é **estornada**, e cobrança que ainda não existe fica proibida (um
  `CobrarPedido` atrasado recebe `PagamentoRecusado{CANCELADA}`). Isso resolve "pagamento depois do
  cancelamento" (§6) para o pagamento simulado; a fase 5 aplica a mesma regra ao Fiel.
- Pagamento simulado (P10): aprovado ou recusado por sorteio fixo pelo id do pedido (taxa padrão 80%), então
  repetir dá sempre a mesma resposta.
- Preço: vem do estoque (`edicao.preco_centavos`) no `UnidadeReservada`; o visitante nunca informa valor.
- Fila de mortos: erro de contrato vai para `<tópico>.mortos` (mesma partição), cada serviço guarda os seus
  na tabela `morto` e expõe `GET /mortos` e `POST /mortos/{id}/reprocessar` (o botão do painel, fase 7).
  Na fase 8, o Caddy precisa decidir quem pode apertar o botão.
- Prova do critério: o módulo `ponta-a-ponta` sobe os três serviços como processos Java (os `-exec.jar`) e
  derruba pagamentos com `kill -9`, parado e no meio do trabalho. **Fase 8:** o jar executável de cada
  serviço é o `*-exec.jar` (o sem sufixo não roda sozinho); o Dockerfile e a `/publicar-vm` usam esse.
- **Fase 8 (segurança, achado do revisor):** `GET/POST /mortos*` mostram mensagens e reprocessam; o Caddy
  bloqueia de fora (o botão do painel passa por um caminho protegido, a decidir na fase 7). `POST /pedidos`
  só vai ao ar depois do limite por IP da fase 6 (senão um laço de curl compra uma edição inteira).

## 6. Regras de negócio

- **Reserva atômica (P23):** `update edicao set disponivel = disponivel - 1 where id = ? and disponivel > 0`;
  zero linhas afetadas = sem estoque. Mais um `check (disponivel >= 0)` no banco como última trava.
- **Resultado da reserva guardado (19/10/2026, fase 2, achado do revisor):** a tabela `reserva` tem uma
  linha por pedido com a `situacao` (`reservada`, `sem_estoque`, `devolvida`). O mesmo pedido repetido, mesmo
  com outro id de evento, recebe sempre a mesma resposta (`JA_RESERVADA`, `SEM_ESTOQUE`, `JA_DEVOLVIDA`).
  Assim um pedido que terminou em `SEM_ESTOQUE` nunca leva depois uma unidade devolvida por outro (ela sumiria,
  porque a saga dele já acabou). A fase 3/4 traduz esses resultados em eventos; `JA_DEVOLVIDA` não pode
  virar "reservado" na saga.
- **Prazo de pagamento (P11):** a reserva vale 5 minutos; vencido o prazo sem pagamento, a saga cancela
  e devolve a unidade. Pagamento que chega depois do cancelamento é estornado pelo Fiel (decidido na fase 5,
  19/10/2026: estorno, ver §7).
- **Edições (P28):** cada lançamento cria uma edição nova da carta (ex.: 10 unidades); o estoque de um
  lançamento não interfere no próximo.
- **Lançamento (P7, P27):** "Simular lançamento" solta ~200 compradores-robôs contra uma edição de 10.
  Um lançamento por vez no site inteiro (fila visível), no máximo 1 por minuto por IP, robôs com ritmo
  controlado para não travar o núcleo da VM.
- **Pagamento dos robôs (P10):** simulado dentro do serviço de pagamentos, com falhas sorteadas (para
  exercitar a compensação). Só a compra manual passa pelo Fiel.

> 19/10/2026 (fase 6): decisões tomadas ao fazer.
>
> - **Quem faz o quê:** o lançamento mora em pedidos (tabela `lancamento`, V6), que já é o orquestrador. A edição é
>   aberta pelo estoque com um comando novo, `AbrirEdicao {unidades}`, pela saída como os outros (nada de HTTP entre os
>   serviços). As respostas são `EdicaoAberta {edicaoId, cartaId, unidades, precoCentavos}` e `EdicaoRecusada {motivo}`
>   (`SEM_CARTA_RARA`, `UNIDADES_INVALIDAS`). Nesses três, o `pedidoId` do envelope leva o **id do lançamento** (é só a
>   chave de partição); o mesmo vale para o evento novo `LancamentoMudouDeEstado {estado, motivo, edicaoId, robos,
>   soltos}` em `pedidos.eventos`.
> - **A carta é do estoque:** entre as raras e lendárias, a que teve menos edições (empate: a mais antiga). O
>   `POST /lancamentos` não leva corpo nenhum: quem aperta o botão só escolhe a hora. `edicao.lancamento_id` é único, então
>   o `AbrirEdicao` repetido devolve a mesma edição.
> - **Estados:** `NA_FILA → ABRINDO → SOLTANDO → ESPERANDO → ENCERRADO`, ou `FALHOU` (edição recusada). `ABRINDO` vencido
>   (1 min) reenvia o `AbrirEdicao` (20/10/2026, achado do revisor: no máximo 5 envios, coluna `aberturas`; depois disso
>   `FALHOU` com `motivo = ESTOQUE_SEM_RESPOSTA`, senão um `EdicaoAberta` que sempre cai na fila de mortos pararia a fila
>   para sempre); `ESPERANDO` termina quando nenhum pedido dos robôs está num estado intermediário, ou
>   pelo prazo de 10 min (`motivo = PRAZO`): as sagas que faltam terminam pelos próprios prazos, e a fila não para por elas.
> - **Um por vez:** um índice único parcial no banco (`where estado in ('ABRINDO','SOLTANDO','ESPERANDO')`) faz de "dois
>   lançamentos andando" um erro, não uma condição de corrida (teste com contraprova). (20/10/2026: `avancar()` também
>   pega a trava consultiva da fila, como o `pedir()`; com duas instâncias, as duas chamavam o próximo juntas e uma
>   levava erro do índice. Contraprova: 8 rodadas simultâneas sem a trava dão `DuplicateKeyException`.) Fila de no máximo 5 esperando;
>   o sexto recebe 503 com `Retry-After: 60` e não gasta o minuto do visitante.
> - **Robôs e ritmo:** uma agenda a cada 100 ms solta os robôs devidos pelo relógio (10 por segundo desde o começo, e não
>   por rodada; no máximo 50 numa rodada, então o atraso de uma parada longa volta aos poucos). Cada robô é um pedido
>   comum da saga com `forma = SIMULADA` e `pedido.lancamento_id`; os robôs e o contador `soltos` são gravados na mesma
>   transação, então uma queda no meio continua de onde parou. 200 robôs = ~20 s; as unidades devolvidas por pagamento
>   recusado voltam a tempo para os robôs que chegam depois, por isso o lançamento vende as 10. (20/10/2026: os
>   horários do lançamento são cortados em microssegundos antes de gravar; o driver do Postgres arredonda os
>   nanossegundos e o começo gravado podia ficar depois do relógio, soltando um robô a menos.)
> - **Limites por IP (na memória, como no Fiel):** 1 lançamento por minuto e 10 compras (`POST /pedidos`) por minuto;
>   IPv6 conta pelo /64; 429 com `Retry-After`. O IP vem do `X-Forwarded-For` só quando o pedido chega de um proxy
>   confiável: **fase 8:** `FOIL_CABECALHOS_DO_PROXY=native` e `FOIL_PROXIES_CONFIAVEIS` = a rede do Caddy, como no Fiel.
> - **SSE (`GET /eventos`, em pedidos):** lê `pedidos.eventos` sem grupo de consumidor (`assign` a partir do fim), então
>   cada instância vê tudo e não guarda posição. Cada mensagem: `event` = tipo, `data` = envelope inteiro. Cada visitante
>   tem fila própria (1000 mensagens) e uma thread virtual: quem lê devagar é desconectado em vez de segurar os outros.
>   (20/10/2026, achado do revisor: o `complete()` desse desconectado roda numa thread à parte. Ele espera a mesma trava
>   do `send()`, e o escritor de quem parou de ler fica preso no `send()`; chamado da thread do Kafka, parava o painel
>   de todo mundo. Contraprova: com o `complete()` direto, o teste do visitante que não lê trava por mais de 60 s.)
>   Teto de 100 conexões (503) e 5 por IP (429); conexão vence em 30 min (o EventSource reconecta); comentário a cada
>   15 s (20/10/2026: configurável em `foil.painel.manter-vivo`; quem saiu só aparece na segunda escrita depois de
>   fechar, então a vaga volta em até duas voltas). O retrato do momento vem do `GET /lancamentos` (o da vez, a fila e os 3 últimos). **Fase 8:** o Caddy precisa
>   de `flush_interval -1` no `/eventos`.
> - **Fase 8, conferir (revisor, 20/10/2026):** (1) se o Caddy estiver na rede do Docker e `FOIL_PROXIES_CONFIAVEIS`
>   não cobrir esse IP, todo mundo vira "o IP do Caddy": 1 lançamento e 10 compras por minuto no site inteiro e o 6º
>   visitante do painel com 429. Conferir na `/publicar-vm` (ou avisar no log ao subir quando o `remoteAddr` é privado).
>   (2) O limite por IP não tem teto de chaves (só faxina por minuto); quem tem um /48 de IPv6 cria até 65 mil entradas
>   por minuto. Poucos MB, risco baixo; um teto (ex.: 100 mil) se a VM apertar. (3) O painel não vê partições novas
>   criadas depois de ligar (o tópico tem as partições fixas).
> - **Riscos aceitos (fase 8 decide):** o limite de compras é por IP; quem tem muitos IPs ainda prende unidades por até
>   5 min sem pagar. A compra pelo Fiel passa pelo limite de cobranças por minuto da Loja Foil no Fiel (30): um pico de
>   compras manuais vira `PagamentoRecusado`.

## 7. Integração com o Fiel (P5, P9, P17, P18)

- **No Fiel (fase 5, commit no repositório do Fiel):** chave de integração por variável de ambiente
  (como a do Beacon: só cria e lê cobranças; comparada em tempo constante) e conta fixa **"Loja Foil"**,
  garantida na partida (sobrevive ao reset diário das 4h, como a Loja Exemplo).
- **Compra manual:** pagamentos cria a cobrança no Fiel pela chave; o visitante paga pelo QR em
  `fiel.andrelagos.dev/pagar` como visitante do Fiel; o webhook do Fiel (HMAC com carimbo de tempo)
  avisa o Foil, que confere a assinatura.
- **Reset do Fiel às 4h:** o Foil recadastra o webhook pela chave ao subir e todo dia às 4h05, guardando
  o segredo novo. A limpeza do Foil roda às 4h10 (P28).

> 19/10/2026 (fase 5): decisões tomadas ao fazer.
>
> - **O que a chave pode (mudou):** criar, ler uma, cancelar e estornar as cobranças da Loja Foil, e cadastrar o
>   webhook (`PUT /webhook`). "Só cria e lê" não bastava: a compensação da fase 4 (prazo vencido cancela a pendente
>   e estorna a paga) precisa cancelar e estornar no Fiel, e o recadastro das 4h05 é pela chave. Carteira,
>   transferências, chaves e a lista de cobranças ficam fora (403). Pior caso com a chave vazada: devolver aos
>   clientes o que eles pagaram. Comparação em tempo constante; menos de 32 caracteres, o Fiel não sobe.
> - **Loja Foil** = `foil@exemplo.fiel` (domínio que o cadastro público recusa), criada na partida quando
>   `FIEL_INTEGRACAO_CHAVE` existe; senha aleatória que ninguém conhece.
> - **Referência da cobrança (V9 no Fiel):** opcional, única por loja; criar de novo com a mesma devolve a mesma
>   cobrança (`Idempotent-Replayed`), com outro valor é 422. É o id do pedido, vai no aviso, e é por ela que o Foil
>   acha o pedido (o id do Fiel recomeça do 1 quando a demonstração é recriada).
> - **Contratos:** `CobrarPedido` v2 `{valorCentavos, forma}` com `forma` `SIMULADA` ou `FIEL`: a primeira v2 do
>   projeto, então a regra "aceita a atual e a anterior" começou aqui (v1 vale como `SIMULADA`). Evento novo
>   `CobrancaCriada {qr, linkParaPagar}` (v1), que a saga guarda no pedido sem mudar de estado. `POST /pedidos` sem
>   `forma` é `FIEL` (a compra da loja); os robôs da fase 6 usam `SIMULADA`. **Decidido na fase 8 (20/10/2026):** o
>   `POST /pedidos` público recusa `SIMULADA` com 400 (`foil.pedidos.aceita-simulada=false`; só os testes ligam), senão
>   um curl levava gemas sem pagar. Os robôs não passam pelo HTTP.
> - **Quem fala com o Fiel é o `TrabalhoNoFiel`, fora do consumidor do Redpanda:** o consumidor só anota a cobrança
>   (`pendente`, `cancelando`, `estornando`, V5 de pagamentos), e o trabalhador faz o pedido HTTP sem transação
>   aberta, com espera crescente até 60 s. Com o Fiel fora do ar, só as compras manuais esperam; os comandos dos
>   robôs seguem (testado). Erro 4xx do Fiel na criação = `PagamentoRecusado{RECUSADO}`.
> - **Pagamento depois do cancelamento (§6): estorno, não anomalia.** Cancelar no Fiel e receber 409 com a cobrança
>   paga, ou o aviso de paga chegando com a cobrança cancelando, vira `estornando`, e o estorno usa o id do pedido
>   como Idempotency-Key. O QR vale o mesmo prazo do pagamento da saga (5 min).
> - **Avisos:** `POST /fiel/avisos` em pagamentos confere o HMAC sobre os bytes recebidos, o `t` a no máximo 5 min
>   e guarda o `Fiel-Evento-Id` em `evento_processado`. Sem segredo ainda = 503 (o Fiel tenta de novo). Rede de
>   segurança: a cobrança pendente é conferida no Fiel a cada 15 s, então um aviso perdido não deixa a saga presa.
> - **Endereços na VM (fase 8):** a API do Fiel pelo apelido interno (`FOIL_FIEL_API=http://fiel-api:8080`), mas
>   os avisos pelo endereço público (`FOIL_FIEL_WEBHOOK=https://foil.andrelagos.dev/fiel/avisos`), porque o Fiel
>   só entrega em `https://` fora da rede interna (proteção contra SSRF dele). O Caddy precisa mandar
>   `/fiel/avisos` para `foil-pagamentos`. A mesma chave vai em `FIEL_INTEGRACAO_CHAVE` (Fiel) e `FOIL_FIEL_CHAVE`
>   (Foil); o Fiel com a V9 precisa ser publicado **antes** do Foil.
> - **Prova:** `CompraPeloFielTest` (ponta-a-ponta) sobe o jar do Fiel de verdade com os três serviços: compra paga
>   pelo QR vira `CONFIRMADO`; pagamento feito com pagamentos derrubado (`kill -9`) e depois do prazo termina
>   `CANCELADO`, com o dinheiro estornado no Fiel e a unidade de volta; o Fiel recusando a cobrança desfaz a saga.
>   O CI monta o jar do `main` do Fiel, então uma mudança no Fiel que quebre o contrato aparece no CI do Foil.
> - **Achados do revisor (19/10):** o id da cobrança no Fiel recomeça do 1 a cada recriação das 4h, então antes de
>   conferir, cancelar ou estornar o trabalhador lê a cobrança e exige `referencia` = id do pedido; outra referência
>   = cobrança que sumiu (sem isso, o cancelamento de um pedido antigo estornava o pagamento de um pedido novo com o
>   mesmo id; teste + contraprova). Fiel fora do ar no cadastro do webhook não derruba pagamentos (o
>   `ApplicationReadyEvent` que lança exceção fecha a aplicação); tenta de novo a cada minuto, inclusive depois das
>   4h05. `/fiel/avisos` recusa corpo acima de 64 KB (413) sem ler o resto; **fase 8:** `request_body max_size` no
>   Caddy também. Ao desistir, a saga tira o QR do pedido.
> - **Ordem de publicação (fase 8):** Fiel com a V9 antes do Foil, e no Foil pagamentos antes de pedidos (um
>   pagamentos antigo mandaria o `CobrarPedido` v2 para a fila de mortos).

## 8. Demonstração

- `foil.andrelagos.dev`, só demo, sem cadastro.
- Telas: loja (cartas, compra, QR do Fiel), **painel de eventos** (cada saga passo a passo, ao vivo) e o
  lançamento com estoque caindo ao vivo, vendidos e recusados.
- Identidade visual própria e o jogo fictício (nome, cartas, arte) decididos na fase 7.

> 20/10/2026 (fase 7): decisões tomadas com o André, a partir de um protótipo (5 versões; ele recusou duas
> rodadas de propostas antes desta).
>
> - **O jogo:** gemas fictícias (nomes e cores inventados), com dureza na **escala de Mohs de verdade** (1 a 10) e
>   **fluorescência** (algumas acendem na luz negra, como fluorita e calcita). Raridade continua `comum`/`incomum`/
>   `rara`/`lendaria`. A arte não é imagem: a interface desenha a gema lapidada por código a partir da `cor` da carta
>   (uma chave de paleta) e, na luz negra, da `fluorescencia` (outra chave, ou nenhuma).
> - **Identidade "joalheria à noite":** grafite calmo, a cor só dentro das gemas, brilho de prisma; Bodoni Moda
>   (itálico nos nomes) + Hanken Grotesk + Red Hat Mono; lilás `#cbb8ff` nos destaques. Sem emojis.
> - **Uma função por aba** (pedido dele): Vitrine (abertura + gemas à venda), Lançamento (o da vez, a fila, o estojo de
>   10 lugares), Minhas gemas (inspetor) e Por dentro (a saga da compra + todos os eventos). O endereço guarda a aba.
> - **Luz negra:** botão no topo e tecla L, em qualquer aba; as gemas sem fluorescência apagam, as outras acendem na
>   cor delas; o verso do certificado tem uma marca em "tinta invisível" (AUTÊNTICA + unidade + pedido).
> - **Minhas gemas:** as compras deste navegador. Inspetor 3D: arrastar gira, "virar" mostra o certificado da unidade
>   (unidade N de total, lapidada, comprada, pedido, cobrança), luz e zoom; teclado: setas, V, + e -.
> - **Backend que isso pede:** (1) estoque: `carta` ganha `mohs`, `cor`, `fluorescencia` (V7) e o **catálogo** vira
>   uma migração repetível (`R__catalogo.sql` em `db/catalogo`, ligada fora dos testes) com a 1ª edição de cada gema;
>   `GET /vitrine` = cada gema com a edição mais nova. (2) pedidos: cookie `foil_visitante` (UUID aleatório,
>   HttpOnly, SameSite=Lax, Secure fora do dev, 1 ano) criado no `POST /pedidos` e gravado em `pedido.visitante` (V7);
>   `GET /minhas` = os pedidos desse cookie (máx. 50), com a **unidade** de cada confirmado = a ordem dele entre os
>   confirmados da edição (os robôs contam). Sem cookie = lista vazia.
> - **Achados do revisor (20/10/2026), corrigidos:** (1) a compra saía 2 vezes em dev (o POST num `useEffect` + StrictMode):
>   agora o POST é feito no clique e o diálogo só acompanha (teste em StrictMode exige 1 POST; contraprova: 2). (2) a
>   **unidade** passou a ser gravada na confirmação (`pedido.unidade`, trava consultiva por edição + índice único +
>   check "confirmado ⇔ unidade"), em vez de calculada na leitura (dependia do horário da transação e fazia 1 consulta
>   por pedido); contraprova: sem a trava, 20 confirmações simultâneas repetem unidade. (3) a vitrine mostra a edição
>   **mais nova com estoque** (um lançamento esgotado escondia o que sobrou da edição anterior). (4) "Minhas gemas"
>   só trata 404 como "edição apagada"; outra falha vira erro com "Tentar de novo". (5) `linkParaPagar` só vira link se
>   for https (ou http em localhost). (6) `--apagado` clareado para 5,7:1 de contraste; o registro de eventos com
>   `aria-live="off"`. (7) cookie sem `Secure` no perfil dev (`foil.cookie.seguro=false`), para abrir pelo IP.
>   Aceito: o catálogo usa o nome como chave (renomear cria outra gema; documentado no arquivo).
> - **Caminhos:** a interface chama `/api/pedidos/*` e `/api/estoque/*`; no dev, o proxy do Vite tira o prefixo; na
>   **fase 8**, o Caddy faz o mesmo (`handle_path`). O `web/` é React + TS + Vite com Vitest; o CI ganha um job dele.
- Limpeza diária às 4h10: pedidos, edições e eventos antigos.

> 20/10/2026 (fase 8): a limpeza, decidida ao fazer. `Limpeza` no `comum` (agendada às 4h10 de Brasília, apaga em
> lotes de 5000, cada lote numa transação) + uma `Limpeza.Regras` por serviço. Dois prazos:
> - **Eventos: 4 dias** (`foil.limpeza.dias-dos-eventos`). Os tópicos passam a ter `retention.ms` de 2 dias e
>   `segment.ms` de 1 dia: a retenção só apaga segmento fechado, e o padrão do Redpanda fecha em 14 dias (revisor,
>   conferido num Redpanda v26.2.5: sem isso, uma mensagem ficaria ~17 dias). Máximo no tópico = 3 dias. O id em
>   `evento_processado` precisa durar mais que isso: um consumidor que perde a posição (offset do grupo vence em 7
>   dias) recomeça do início e só não aplica duas vezes se o id ainda estiver lá. O mesmo vale para as linhas que
>   respondem a comando repetido (reserva recusada/devolvida, cobrança terminada, inclusive a cancelada que trava o
>   `CobrarPedido` atrasado). O `comum` recusa subir com 3 dias ou menos. `spring.kafka.admin.modify-topic-configs=true`
>   acerta também os tópicos que já existem (dev e VM).
> - **Mortos x travas (revisor):** a reserva devolvida e a cobrança cancelada são as travas contra um comando
>   reprocessado da fila de mortos. Elas ficam enquanto existir um morto do pedido (`morto.chave` = id do pedido), e
>   o `POST /mortos/{id}/reprocessar` recusa (410) morto com mais de 30 − 3 − 1 = 26 dias: a mensagem devolvida ainda
>   pode ser lida por 3 dias, e o morto (com ele, as travas) sai aos 30.
> - **Compras: 30 dias** (`foil.limpeza.dias-das-compras`): pedidos confirmados e os do visitante ("Minhas gemas"),
>   edições dos lançamentos (só sem reserva nesse prazo; as do catálogo ficam sempre) e mortos.
> - O que sai em cada um: `saida` publicada há mais de 1 dia (a não publicada nunca); pedidos recusados/cancelados
>   dos **robôs** em 4 dias, o resto dos terminados em 30; lançamento terminado sem pedidos, menos os **3 últimos**
>   (o `GET /lancamentos` mostra); pedido em andamento e cobrança esperando o Fiel nunca.
> - **Por que os confirmados dos robôs ficam 30 dias:** a unidade de uma venda nova é `max(unidade) + 1` da edição;
>   apagar a maior de uma edição ainda à venda faria um certificado novo repetir o número de um antigo. Risco aceito:
>   se todas as vendas de uma edição saírem e ela ainda estiver à venda, a numeração recomeça (a edição de lançamento
>   sai junto em 30 dias sem reserva; a do catálogo pode recomeçar).
> - Edições dos lançamentos com um dia de folga (o pedido sai pela confirmação, que vem depois da reserva).
> - Testes com as datas empurradas para o passado (os de pedidos numa transação desfeita); contraprovas: apagar o
>   confirmado dos robôs cedo e tirar a proteção dos 3 últimos reprovam os testes.

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
