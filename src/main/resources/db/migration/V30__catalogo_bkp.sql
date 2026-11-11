-- 1.14.0: catálogo inicial com os tipos, marcas e modelos de equipamento do site bkpbr.com.br (levantados em
-- 11/11/2026, conferidos com o usuário; sem CPU para PDV, etiqueta eletrônica e terminal de autoatendimento).
--
-- Só ACRESCENTA: tipo, marca ou modelo que já existe com o mesmo nome (sem diferenciar maiúsculas) fica como está e
-- não é repetido. Nada do catálogo atual é apagado nem alterado. A marca é por tipo (a Zebra do coletor é uma, a das
-- impressoras é outra), como no resto do catálogo. Linha com modelo vazio = só o tipo e a marca.
-- Os contadores de id vão para depois do maior id existente (um tipo, marca ou modelo gravado com id escolhido à mão
-- deixava o contador para trás, e o insert abaixo recusava o id repetido). Só avança, nunca volta.
do $$
declare
    t text;
    seq text;
    maior bigint;
    atual bigint;
begin
    foreach t in array array['tipo_equipamento', 'marca', 'modelo'] loop
        seq := pg_get_serial_sequence(t, 'id');
        execute format('select coalesce(max(id), 0) from %I', t) into maior;
        execute format('select last_value from %s', seq) into atual;
        if maior >= atual then
            perform setval(seq, maior);
        end if;
    end loop;
end $$;

create temporary table catalogo_bkp (tipo varchar(255), marca varchar(255), modelo varchar(255)) on commit drop;

insert into catalogo_bkp (tipo, marca, modelo) values
    -- Coletor de dados
    ('Coletor de Dados', 'Zebra', 'MC33'), ('Coletor de Dados', 'Zebra', 'MC3300X'), ('Coletor de Dados', 'Zebra', 'MC3190'),
    ('Coletor de Dados', 'Zebra', 'MC9090'), ('Coletor de Dados', 'Zebra', 'MC9290'), ('Coletor de Dados', 'Zebra', 'TC15'),
    ('Coletor de Dados', 'Zebra', 'TC20'), ('Coletor de Dados', 'Zebra', 'TC21'),
    ('Coletor de Dados', 'Honeywell', 'EDA52'), ('Coletor de Dados', 'Honeywell', 'Dolphin 6500'),
    ('Coletor de Dados', 'Datalogic', 'Skorpio X4'), ('Coletor de Dados', 'Datalogic', 'Memor'), ('Coletor de Dados', 'Datalogic', 'Falcon X4'),
    ('Coletor de Dados', 'Urovo', 'DT40'), ('Coletor de Dados', 'Urovo', 'RT40'),
    -- Impressora de etiquetas (térmica / desktop)
    ('Impressora de Etiquetas', 'Zebra', 'ZD621'), ('Impressora de Etiquetas', 'Zebra', 'ZD220'),
    ('Impressora de Etiquetas', 'Zebra', 'TLP 2844'), ('Impressora de Etiquetas', 'Zebra', 'GX420t'),
    ('Impressora de Etiquetas', 'Honeywell', 'PC42t'), ('Impressora de Etiquetas', 'Honeywell', 'PC42E'),
    ('Impressora de Etiquetas', 'Argox', 'OS-214 Plus'), ('Impressora de Etiquetas', 'Argox', 'OS-2140'), ('Impressora de Etiquetas', 'Argox', 'OS-214 TT'),
    ('Impressora de Etiquetas', 'Datamax', 'E-4205A'),
    -- Impressora industrial e comercial (inclui alto desempenho)
    ('Impressora Industrial', 'Zebra', 'ZT220'), ('Impressora Industrial', 'Zebra', 'ZT610'), ('Impressora Industrial', 'Zebra', 'S4M'),
    ('Impressora Industrial', 'Zebra', 'Z4M'), ('Impressora Industrial', 'Zebra', 'ZM400'), ('Impressora Industrial', 'Zebra', '110Xi3'),
    ('Impressora Industrial', 'Zebra', '220Xi3'),
    ('Impressora Industrial', 'Honeywell', 'PM42'), ('Impressora Industrial', 'Honeywell', 'PD41'), ('Impressora Industrial', 'Honeywell', 'PD43'),
    ('Impressora Industrial', 'Honeywell', 'PD45'), ('Impressora Industrial', 'Honeywell', 'PX6'),
    ('Impressora Industrial', 'Datamax', 'M-4206'), ('Impressora Industrial', 'Datamax', 'I-4212'),
    -- Impressora portátil
    ('Impressora Portátil', 'Zebra', 'ZQ521'), ('Impressora Portátil', 'Zebra', 'ZQ520'), ('Impressora Portátil', 'Zebra', 'ZQ630'),
    ('Impressora Portátil', 'Zebra', 'QLn320'), ('Impressora Portátil', 'Zebra', 'RL3'), ('Impressora Portátil', 'Zebra', 'RL4'),
    ('Impressora Portátil', 'Honeywell', 'PR2'), ('Impressora Portátil', 'Honeywell', 'PB22'), ('Impressora Portátil', 'Honeywell', 'PB51'),
    -- Impressora RFID
    ('Impressora RFID', 'Zebra', 'ZT410'), ('Impressora RFID', 'Zebra', 'ZT231'), ('Impressora RFID', 'Zebra', 'R110Xi4'),
    -- Impressora de cartão PVC
    ('Impressora de Cartão PVC', 'Zebra', 'ZC300'), ('Impressora de Cartão PVC', 'Zebra', 'P330i'), ('Impressora de Cartão PVC', 'Zebra', 'P430i'),
    ('Impressora de Cartão PVC', 'Zebra', 'ZXP Series'),
    ('Impressora de Cartão PVC', 'Datacard', 'SD260'), ('Impressora de Cartão PVC', 'Datacard', 'SD360'),
    ('Impressora de Cartão PVC', 'Datacard', 'CD800'), ('Impressora de Cartão PVC', 'Datacard', 'SP30'),
    ('Impressora de Cartão PVC', 'Evolis', 'Zenius'), ('Impressora de Cartão PVC', 'Evolis', 'Dualys 3'),
    ('Impressora de Cartão PVC', 'Evolis', 'Primacy 2'), ('Impressora de Cartão PVC', 'Evolis', 'Pebble'),
    -- Leitor de código de barras
    ('Leitor de Código de Barras', 'Zebra', 'DS2278'), ('Leitor de Código de Barras', 'Zebra', 'LS2208'), ('Leitor de Código de Barras', 'Zebra', 'LI2208'),
    ('Leitor de Código de Barras', 'Zebra', 'LS7708'), ('Leitor de Código de Barras', 'Zebra', 'DS7708'), ('Leitor de Código de Barras', 'Zebra', 'LS4278'),
    ('Leitor de Código de Barras', 'Datalogic', 'QuickScan QW2400'), ('Leitor de Código de Barras', 'Datalogic', 'QuickScan QBT2131'),
    ('Leitor de Código de Barras', 'Datalogic', 'Magellan 3410VS'), ('Leitor de Código de Barras', 'Datalogic', 'PowerScan'),
    ('Leitor de Código de Barras', 'Datalogic', 'VS2200'),
    ('Leitor de Código de Barras', 'Honeywell', '1470g'), ('Leitor de Código de Barras', 'Honeywell', 'Voyager'),
    ('Leitor de Código de Barras', 'Honeywell', 'Solaris MK7820'), ('Leitor de Código de Barras', 'Honeywell', 'Eclipse 5145'),
    ('Leitor de Código de Barras', 'Honeywell', '3800i'),
    ('Leitor de Código de Barras', 'Bematech', 'BR-400'), ('Leitor de Código de Barras', 'Bematech', 'S-100'),
    ('Leitor de Código de Barras', 'Bematech', 'S-500'), ('Leitor de Código de Barras', 'Bematech', 'Áquila S-3200'),
    ('Leitor de Código de Barras', 'Elgin', 'Flash II'), ('Leitor de Código de Barras', 'Elgin', 'EL8600'),
    -- Balança (checkout, precisão e recebimento)
    ('Balança', 'Toledo', 'Prix 6'), ('Balança', 'Toledo', 'Prix TI200'),
    -- Rede
    ('Access Point', 'Ubiquiti', 'UniFi AP G5 Pro'),
    -- Ponto de venda
    ('Gaveta de Dinheiro', 'Bematech', 'GD56M'),
    ('Teclado para PDV', 'Smak', 'SKO-44'),
    ('Monitor Touch', 'Elo', null);

insert into tipo_equipamento (nome)
select distinct c.tipo from catalogo_bkp c
where not exists (select 1 from tipo_equipamento t where lower(t.nome) = lower(c.tipo));

insert into marca (tipo_id, nome)
select distinct t.id, c.marca from catalogo_bkp c
join tipo_equipamento t on lower(t.nome) = lower(c.tipo)
where not exists (select 1 from marca m where m.tipo_id = t.id and lower(m.nome) = lower(c.marca));

insert into modelo (marca_id, nome)
select distinct m.id, c.modelo from catalogo_bkp c
join tipo_equipamento t on lower(t.nome) = lower(c.tipo)
join marca m on m.tipo_id = t.id and lower(m.nome) = lower(c.marca)
where c.modelo is not null
  and not exists (select 1 from modelo o where o.marca_id = m.id and lower(o.nome) = lower(c.modelo));
