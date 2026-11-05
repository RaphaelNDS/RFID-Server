-- 1) Versão de cada etiqueta (@Version): a tela de edição recusa salvar por cima da mudança de outra pessoa.
alter table tags add column versao bigint not null default 0;

-- 2) Nomes únicos sem diferenciar maiúsculas. O sistema procura local, tipo, marca e modelo pelo nome ignorando
-- maiúsculas, mas o banco só impedia o nome exatamente igual (marca e modelo nem isso): dois coletores abrindo ao
-- mesmo tempo o primeiro inventário de "Almoxarifado" e "almoxarifado" criavam dois locais, e daí em diante toda
-- busca por esse local dava erro 500. Os índices impedem a duplicata.
--
-- Se já houver duplicados no banco, o índice daquela tabela NÃO é criado (a migração não apaga nem junta nada
-- sozinha) e fica um aviso no log do PostgreSQL; o sistema continua funcionando como antes nessa tabela.
do $$
begin
    if not exists (select 1 from locais group by lower(nome) having count(*) > 1) then
        create unique index uk_locais_nome_minusculo on locais (lower(nome));
    else
        raise notice 'Há locais com o mesmo nome (sem diferenciar maiúsculas): índice uk_locais_nome_minusculo não criado';
    end if;

    if not exists (select 1 from tipo_equipamento group by lower(nome) having count(*) > 1) then
        create unique index uk_tipo_equipamento_nome_minusculo on tipo_equipamento (lower(nome));
    else
        raise notice 'Há tipos de equipamento com o mesmo nome: índice uk_tipo_equipamento_nome_minusculo não criado';
    end if;

    if not exists (select 1 from marca group by tipo_id, lower(nome) having count(*) > 1) then
        create unique index uk_marca_tipo_nome_minusculo on marca (tipo_id, lower(nome));
    else
        raise notice 'Há marcas com o mesmo nome no mesmo tipo: índice uk_marca_tipo_nome_minusculo não criado';
    end if;

    if not exists (select 1 from modelo group by marca_id, lower(nome) having count(*) > 1) then
        create unique index uk_modelo_marca_nome_minusculo on modelo (marca_id, lower(nome));
    else
        raise notice 'Há modelos com o mesmo nome na mesma marca: índice uk_modelo_marca_nome_minusculo não criado';
    end if;
end $$;
