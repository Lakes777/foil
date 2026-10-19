-- Fase 5: a compra manual é cobrada no Fiel (P5, P9). A cobrança simulada responde na hora; a do Fiel passa por
-- estados de espera, porque cada passo depende de um pedido HTTP ao Fiel, que pode estar fora do ar:
--
--   pendente (sem fiel_id) ──criou no Fiel──> pendente (com fiel_id e QR) ──pagou (aviso ou conferência)──> aprovada
--   pendente ──CancelarCobranca──> cancelando ──Fiel cancelou──> cancelada
--   aprovada ──CancelarCobranca──> estornando ──Fiel estornou──> estornada
--   cancelando ──o Fiel diz que já foi paga──> estornando
--
-- Quem faz os pedidos ao Fiel é o TrabalhoNoFiel, fora do consumidor do Redpanda: com o Fiel fora do ar, só as
-- compras manuais esperam; os comandos dos robôs seguem. proxima_em é quando ele olha a linha de novo.
alter table cobranca
    add column forma      text        not null default 'simulada' check (forma in ('simulada', 'fiel')),
    add column fiel_id    bigint,
    add column qr         text,
    add column tentativas int         not null default 0,
    add column proxima_em timestamptz,
    drop constraint cobranca_situacao_check,
    add constraint cobranca_situacao_check check (situacao in ('pendente', 'aprovada', 'recusada', 'cancelando',
                                                               'cancelada', 'estornando', 'estornada')),
    -- só a do Fiel espera; e só a que já existe no Fiel pode estar cancelando ou estornando
    add check (forma = 'fiel' or situacao not in ('pendente', 'cancelando', 'estornando')),
    add check (situacao not in ('cancelando', 'estornando') or fiel_id is not null);

-- O que o trabalhador ainda precisa fazer
create index cobranca_no_fiel on cobranca (proxima_em)
    where forma = 'fiel' and situacao in ('pendente', 'cancelando', 'estornando');

-- O endereço que o Foil cadastrou no Fiel e o segredo que confere a assinatura dos avisos. Uma linha só; o
-- recadastro (na partida e às 4h05, depois da recriação diária do Fiel) troca o segredo.
create table webhook_do_fiel (
    id            int         primary key default 1 check (id = 1),
    url           text        not null,
    segredo       text        not null,
    registrado_em timestamptz not null default now()
);
