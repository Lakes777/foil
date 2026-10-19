-- Catálogo do jogo fictício (as cartas de verdade são decididas na fase 7)
create table carta (
    id        bigint generated always as identity primary key,
    nome      text not null unique,
    raridade  text not null check (raridade in ('comum', 'incomum', 'rara', 'lendaria')),
    descricao text not null default ''
);

-- Cada lançamento é uma edição nova com estoque próprio (P28).
-- O check é a última trava contra venda a mais: mesmo um bug no código não deixa disponivel < 0 (P23).
create table edicao (
    id         bigint generated always as identity primary key,
    carta_id   bigint not null references carta (id),
    numero     int    not null check (numero >= 1),
    total      int    not null check (total >= 1),
    disponivel int    not null,
    criada_em  timestamptz not null default now(),
    unique (carta_id, numero),
    constraint disponivel_entre_zero_e_total check (disponivel >= 0 and disponivel <= total)
);

-- Uma linha por pedido, com o resultado guardado: o mesmo pedido repetido recebe sempre a mesma resposta.
-- Sem isso, um pedido que ficou "sem estoque" (estado final da saga) poderia reservar mais tarde uma unidade
-- devolvida por outro, e ninguém a devolveria. Apagar uma edição (limpeza das 4h10) leva as reservas junto.
create table reserva (
    pedido_id    uuid   primary key,
    edicao_id    bigint not null references edicao (id) on delete cascade,
    situacao     text   not null check (situacao in ('reservada', 'sem_estoque', 'devolvida')),
    reservada_em timestamptz not null default now(),
    devolvida_em timestamptz,
    check ((situacao = 'devolvida') = (devolvida_em is not null))
);

create index reserva_ativa_por_edicao on reserva (edicao_id) where situacao = 'reservada';
