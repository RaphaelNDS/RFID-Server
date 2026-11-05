-- Etiqueta (EPC) sempre sem espaços nas pontas e em MAIÚSCULAS.
--
-- O PostgreSQL diferencia maiúsculas (o H2 de antes não): uma etiqueta cadastrada na web como "e2801170..." (colada
-- de planilha) não batia com a leitura "E2801170..." do coletor. A leitura ia para pendentes, o cadastro pelo
-- coletor criava uma segunda etiqueta para o mesmo objeto e os relatórios mostravam o item como "nunca visto".
-- A partir desta versão o servidor padroniza o EPC em toda entrada; aqui ficam padronizados os dados que já existem.
--
-- Só muda a linha que não colide com outra já existente na forma padronizada (a mesma etiqueta gravada nas duas
-- formas): essas ficam como estão e são listadas no NOTICE, para o administrador resolver na tela. Assim a migração
-- nunca falha por causa de dado repetido.

-- Etiquetas cadastradas: o hash (SHA-256 em hex minúsculo do texto em UTF-8) acompanha o texto
do $$
declare
    conflitos text;
begin
    select string_agg(t.tag_real, ', ') into conflitos
    from tags t
    where t.tag_real <> upper(btrim(t.tag_real))
      and exists (select 1 from tags o where o.id <> t.id and upper(btrim(o.tag_real)) = upper(btrim(t.tag_real)));
    if conflitos is not null then
        raise notice 'Etiquetas cadastradas nas duas formas (maiúsculas e minúsculas), mantidas como estão: %', conflitos;
    end if;
end $$;

update tags t
set tag_real = upper(btrim(t.tag_real)),
    tag_hash = encode(sha256(convert_to(upper(btrim(t.tag_real)), 'UTF8')), 'hex')
where t.tag_real <> upper(btrim(t.tag_real))
  and not exists (select 1 from tags o where o.id <> t.id and upper(btrim(o.tag_real)) = upper(btrim(t.tag_real)));

-- Pendentes: a forma repetida (já pendente ou já cadastrada) sai; as outras são padronizadas
delete from tags_nao_cadastradas n
where n.tag <> upper(btrim(n.tag))
  and (exists (select 1 from tags_nao_cadastradas o where o.id <> n.id and upper(btrim(o.tag)) = upper(btrim(n.tag)))
       or exists (select 1 from tags t where t.tag_real = upper(btrim(n.tag))));

update tags_nao_cadastradas set tag = upper(btrim(tag)) where tag <> upper(btrim(tag));

-- Leituras: índices únicos (tag, data_hora) e (tag, lido_em_coletor)
update leituras_android l
set tag = upper(btrim(l.tag))
where l.tag <> upper(btrim(l.tag))
  and not exists (
      select 1 from leituras_android o
      where o.id <> l.id and o.tag = upper(btrim(l.tag))
        and (o.data_hora = l.data_hora or o.lido_em_coletor = l.lido_em_coletor));

-- Itens de inventário: índice único (inventario_id, tag)
update inventario_itens i
set tag = upper(btrim(i.tag))
where i.tag <> upper(btrim(i.tag))
  and not exists (
      select 1 from inventario_itens o
      where o.id <> i.id and o.inventario_id = i.inventario_id and o.tag = upper(btrim(i.tag)));

-- Histórico das etiquetas (sem índice único)
update historico_etiquetas set tag_real = upper(btrim(tag_real)) where tag_real <> upper(btrim(tag_real));
