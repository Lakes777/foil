-- A saga de cada pedido (P6): o estado atual e o prazo do estado. Todo estado intermediário tem prazo
-- (em LIBERANDO, o prazo reenvia a devolução), então nenhum pedido fica parado para sempre.
create table pedido (
    id             uuid        primary key,
    edicao_id      bigint      not null,
    estado         text        not null check (estado in ('RESERVANDO', 'AGUARDANDO_PAGAMENTO', 'LIBERANDO',
                                                          'CONFIRMADO', 'SEM_ESTOQUE', 'CANCELADO')),
    motivo         text,
    preco_centavos int,
    prazo          timestamptz,
    criado_em      timestamptz not null default now(),
    atualizado_em  timestamptz not null default now(),
    -- só os estados com prazo têm prazo
    check ((estado in ('RESERVANDO', 'AGUARDANDO_PAGAMENTO', 'LIBERANDO')) = (prazo is not null))
);

create index pedido_vencendo on pedido (prazo) where prazo is not null;
