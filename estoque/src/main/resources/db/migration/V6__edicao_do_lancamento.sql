-- Fase 6: a edição aberta para um lançamento guarda o id dele. Único: o AbrirEdicao repetido (reenviado depois do
-- prazo, ou entregue duas vezes com outro id de evento) encontra a mesma edição em vez de abrir outra.
alter table edicao add column lancamento_id uuid unique;
