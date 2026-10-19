-- Ids dos eventos já aplicados: o consumidor grava aqui na mesma transação do efeito
-- e ignora o que já estiver gravado (consumidor idempotente, P12).
create table evento_processado (
    evento_id    uuid        primary key,
    processado_em timestamptz not null default now()
);
