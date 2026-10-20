-- O catálogo da loja (fase 7): gemas inventadas e a 1ª edição de cada uma. Repetível: roda de novo quando este
-- arquivo muda, atualiza as gemas pelo nome e só cria a 1ª edição de quem ainda não tem nenhuma. Fica fora dos testes
-- (o estoque testa com o próprio catálogo); a VM e o dev usam.
-- De propósito (revisor, 20/10/2026): a chave é o nome. Renomear aqui cria uma gema nova (a antiga continua no banco);
-- tirar uma linha não apaga a gema; total e preço só valem para a 1ª edição, quando ela é criada.
insert into carta (nome, raridade, descricao, mohs, cor, fluorescencia) values
    ('Auroíta', 'lendaria', 'Só brilha com luz de madrugada. Risca qualquer gema mais mole que ela.', 9, 'aurora', 'aurora'),
    ('Opala-Relâmpago', 'lendaria', 'Guarda o clarão do último raio que caiu perto dela.', 6, 'tempestade', 'tempestade'),
    ('Lumenita Oculta', 'rara', 'Quase sem cor de dia. Na luz negra, é a mais forte da vitrine.', 6, 'lumen', 'lumen'),
    ('Vidro-de-Eco', 'rara', 'Repete o último som que ouviu. Quanto mais limpa, mais tempo o eco dura.', 7, 'mar', null),
    ('Granada Fria', 'rara', 'Gela a mão de quem segura. Ninguém sabe de onde vem o frio.', 8, 'brasa', null),
    ('Âmbar-de-Sino', 'incomum', 'Tem um som preso dentro. Bata de leve e ele toca uma vez.', 3, 'ambar', 'ambar'),
    ('Safira-de-Neblina', 'incomum', 'Embaça quando alguém mente perto dela.', 8, 'neblina', null),
    ('Quartzo-Vespertino', 'comum', 'Fica mais verde ao fim da tarde. Comum, mas nenhuma é igual à outra.', 5, 'musgo', 'musgo'),
    ('Ônix-Calado', 'comum', 'Absorve o barulho em volta. Bom para dormir.', 7, 'onix', null)
on conflict (nome) do update set raridade = excluded.raridade, descricao = excluded.descricao, mohs = excluded.mohs,
    cor = excluded.cor, fluorescencia = excluded.fluorescencia;

insert into edicao (carta_id, numero, total, disponivel, preco_centavos)
select c.id, 1, v.total, v.total, v.preco
from (values ('Auroíta', 5, 4800), ('Opala-Relâmpago', 5, 4200), ('Lumenita Oculta', 10, 2200),
             ('Vidro-de-Eco', 10, 1800), ('Granada Fria', 10, 1800), ('Âmbar-de-Sino', 20, 900),
             ('Safira-de-Neblina', 20, 1100), ('Quartzo-Vespertino', 40, 400), ('Ônix-Calado', 40, 600))
    as v (nome, total, preco)
join carta c on c.nome = v.nome
where not exists (select 1 from edicao e where e.carta_id = c.id);
