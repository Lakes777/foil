-- Uma cobrança por pedido, com a situação guardada: o mesmo comando repetido (mesmo com outro id) recebe a mesma
-- resposta. 'cancelada' sem nunca ter sido cobrada é a trava contra um CobrarPedido atrasado.
create table cobranca (
    pedido_id      uuid        primary key,
    valor_centavos int         check (valor_centavos > 0),
    situacao       text        not null check (situacao in ('aprovada', 'recusada', 'cancelada', 'estornada')),
    criada_em      timestamptz not null default now(),
    atualizada_em  timestamptz not null default now()
);
