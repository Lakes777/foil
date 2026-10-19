-- Preço por edição, em centavos de dinheiro de mentira: quem cobra (pagamentos) recebe o valor do estoque
-- pela saga, nunca do visitante.
alter table edicao add column preco_centavos int not null default 1000 check (preco_centavos > 0);
