-- Fase 9: o trace de quem gravou cada mensagem (W3C traceparent), para o publicador mandá-la no mesmo trace
alter table saida add column traceparent text;

-- O trace da saga, aberto quando o pedido nasce: o prazo vencido (fora de qualquer trace) manda os comandos nele
alter table pedido add column traceparent text;
