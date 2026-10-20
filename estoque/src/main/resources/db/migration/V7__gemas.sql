-- Fase 7: as cartas são gemas fictícias. Dureza na escala de Mohs de verdade (1 a 10); a interface desenha a gema a
-- partir da chave de paleta em "cor" e, na luz negra, da chave em "fluorescencia" (null = não fluoresce, apaga).
alter table carta add column mohs int not null default 5 check (mohs between 1 and 10);
alter table carta add column cor text not null default 'lilas' check (cor ~ '^[a-z]{2,20}$');
alter table carta add column fluorescencia text check (fluorescencia ~ '^[a-z]{2,20}$');
