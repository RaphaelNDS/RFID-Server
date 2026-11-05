-- Filtros da tela de leituras ("contém", sem diferenciar maiúsculas: lower(campo) like '%termo%'). Sem índice,
-- cada busca lia a tabela inteira, que chega a milhões de leituras por ano com o gatilho do leitor. O índice
-- trigram (pg_trgm) serve justamente para LIKE com % no começo. As expressões são as mesmas da consulta
-- (LeituraAndroidConsultaService): lower(coluna).
-- pg_trgm vem com o PostgreSQL e é "trusted" (o dono do banco pode criar), não precisa instalar nada.
--
-- A extensão é do banco inteiro e fica num schema só (public). Quando as tabelas estão em outro schema (o teste
-- de migração usa um por teste), a classe de operador não fica visível sem o nome completo: por isso o bloco
-- descobre o schema da extensão e usa "<schema>.gin_trgm_ops".
create extension if not exists pg_trgm schema public;

do $$
declare
    esquema text;
    campo   text;
begin
    select n.nspname into esquema
    from pg_extension e join pg_namespace n on n.oid = e.extnamespace
    where e.extname = 'pg_trgm';

    foreach campo in array array['tag', 'tipo', 'marca', 'modelo', 'numero_serie', 'operador'] loop
        execute format(
            'create index %I on leituras_android using gin (lower(%I) %I.gin_trgm_ops)',
            'idx_leituras_android_' || case campo when 'numero_serie' then 'serie' else campo end || '_trgm',
            campo, esquema
        );
    end loop;
end $$;
