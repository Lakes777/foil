-- Fase 9: o trace de quem gravou cada mensagem (W3C traceparent), para o publicador mandá-la no mesmo trace
alter table saida add column traceparent text;

-- O trace da saga, guardado quando o CobrarPedido chega: o aviso do Fiel (que vem num trace próprio) e o trabalhador
-- respondem nele
alter table cobranca add column traceparent text;
