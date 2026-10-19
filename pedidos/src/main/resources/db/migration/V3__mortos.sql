-- Fila de mortos (P25): o que não deu para processar por erro de contrato, guardado para o painel listar
-- e para reprocessar. origem = posição na fila de mortos (tópico/partição/offset): entregue de novo, não duplica.
create table morto (
    id              uuid        primary key,
    topico          text        not null,
    chave           text,
    mensagem        text,
    erro            text        not null,
    origem          text        not null unique,
    morto_em        timestamptz not null default now(),
    reprocessado_em timestamptz
);
