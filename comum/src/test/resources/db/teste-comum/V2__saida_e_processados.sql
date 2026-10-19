-- Outbox (P12): todo evento ou comando é gravado aqui na mesma transação da mudança de estado
-- e publicado depois pelo PublicadorDaSaida, na ordem de seq. Nunca kafkaTemplate.send dentro da regra.
create table saida (
    seq          bigint generated always as identity primary key,
    id           uuid        not null unique,
    topico       text        not null,
    chave        text        not null,
    envelope     text        not null,
    criado_em    timestamptz not null default now(),
    publicado_em timestamptz
);

create index saida_pendente on saida (seq) where publicado_em is null;

create table evento_processado (
    evento_id     uuid        primary key,
    processado_em timestamptz not null default now()
);
