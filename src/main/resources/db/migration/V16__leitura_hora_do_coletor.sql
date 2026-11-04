-- Hora que o coletor informou para cada leitura, mesmo no futuro (relógio do coletor adiantado). A data_hora de uma
-- leitura "do futuro" vira a hora do recebimento, que muda a cada reenvio: o índice único (tag, data_hora) da V10 não
-- reconhecia o reenvio, e a mesma leitura era gravada de novo. O reenvio passa a ser reconhecido por esta coluna.
alter table leituras_android add column lido_em_coletor timestamp(6);

-- Leituras já gravadas: a data_hora é a hora do coletor (exceto as poucas "do futuro", que ficam como estão)
update leituras_android set lido_em_coletor = data_hora where lido_em_coletor is null;

-- Sem hora informada (coluna nula) não dá para saber se é reenvio: o índice único deixa passar
create unique index uk_leituras_android_tag_lido_em_coletor on leituras_android (tag, lido_em_coletor);

-- Relatório "não vistas": último inventário de cada etiqueta sem ler a tabela de itens inteira
create index idx_inventario_itens_tag_lido_em on inventario_itens (tag, lido_em);
