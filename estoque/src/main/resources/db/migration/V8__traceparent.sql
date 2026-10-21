-- Fase 9: o trace de quem gravou cada mensagem (W3C traceparent), para o publicador mandá-la no mesmo trace
alter table saida add column traceparent text;
