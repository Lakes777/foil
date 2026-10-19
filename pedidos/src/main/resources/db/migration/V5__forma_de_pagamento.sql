-- Fase 5: como o pedido é pago. SIMULADA é o sorteio dos robôs (P10); FIEL é a compra manual, paga pelo QR do Fiel.
-- Os pedidos de antes são todos simulados. qr e link_para_pagar chegam no CobrancaCriada, só na forma FIEL.
alter table pedido
    add column forma           text not null default 'SIMULADA' check (forma in ('SIMULADA', 'FIEL')),
    add column qr              text,
    add column link_para_pagar text,
    add check (forma = 'FIEL' or (qr is null and link_para_pagar is null));
