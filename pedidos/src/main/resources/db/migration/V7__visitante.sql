-- Fase 7: "Minhas gemas". Quem compra pela loja é um visitante identificado por cookie (P14), sem conta; os pedidos
-- dos robôs e os antigos ficam sem visitante.
alter table pedido add column visitante uuid;
create index pedido_do_visitante on pedido (visitante, criado_em desc) where visitante is not null;

-- A unidade ("4 de 10" no certificado): gravada na confirmação, uma vez só, na ordem em que as confirmações de cada
-- edição acontecem (os robôs contam). Calcular na leitura dependia do horário de início da transação e podia mudar.
alter table pedido add column unidade int check (unidade >= 1);
alter table pedido add check ((unidade is not null) = (estado = 'CONFIRMADO'));
create unique index unidade_unica_por_edicao on pedido (edicao_id, unidade) where unidade is not null;
