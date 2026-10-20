-- Fase 6: o lançamento (P7, P27). Um por vez no site inteiro: os outros esperam NA_FILA, em ordem de chegada.
-- ABRINDO = esperando o estoque abrir a edição; SOLTANDO = robôs saindo no ritmo; ESPERANDO = todos soltos,
-- esperando as sagas deles terminarem. Finais: ENCERRADO e FALHOU (o estoque recusou ou não respondeu).
create table lancamento (
    id            uuid        primary key,
    ordem         bigint      generated always as identity unique,
    estado        text        not null check (estado in ('NA_FILA', 'ABRINDO', 'SOLTANDO', 'ESPERANDO', 'ENCERRADO',
                                                         'FALHOU')),
    motivo        text,
    edicao_id     bigint,
    robos         int         not null check (robos >= 1),
    -- quantos robôs já viraram pedido: guardado na mesma transação dos pedidos, então uma queda no meio continua
    -- de onde parou, sem robô a mais nem a menos
    soltos        int         not null default 0,
    prazo         timestamptz,
    -- quantas vezes o AbrirEdicao já foi mandado: o estoque que nunca responde não prende a fila para sempre
    aberturas     int         not null default 0,
    pedido_em     timestamptz not null,
    comecou_em    timestamptz,
    terminou_em   timestamptz,
    atualizado_em timestamptz not null default now(),
    check (soltos between 0 and robos),
    check (estado not in ('SOLTANDO', 'ESPERANDO') or (edicao_id is not null and comecou_em is not null)),
    -- ABRINDO reenvia o AbrirEdicao quando vence; ESPERANDO encerra assim mesmo (as sagas têm os próprios prazos)
    check ((estado in ('ABRINDO', 'ESPERANDO')) = (prazo is not null))
);

-- A regra "um por vez" no banco: dois lançamentos andando ao mesmo tempo é erro, não condição de corrida
create unique index um_lancamento_por_vez on lancamento ((true)) where estado in ('ABRINDO', 'SOLTANDO', 'ESPERANDO');
create index lancamento_na_fila on lancamento (ordem) where estado = 'NA_FILA';

-- Os pedidos dos robôs apontam para o lançamento; os da loja não têm
alter table pedido add column lancamento_id uuid references lancamento (id);
create index pedido_do_lancamento on pedido (lancamento_id, estado) where lancamento_id is not null;
