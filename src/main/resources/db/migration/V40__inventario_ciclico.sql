-- 1.27.0: inventário cíclico. Em vez de contar tudo de uma vez, cada local precisa ser inventariado pelo menos uma vez a
-- cada ciclo (em semanas); a tela distribui os locais pelas semanas, os mais atrasados primeiro. Uma linha só (id = 1).
create table inventario_ciclico (
    id            integer      primary key check (id = 1),
    ativo         boolean      not null default false,
    semanas       integer      not null default 4 check (semanas between 1 and 52),
    alterado_por  varchar(255),
    alterado_em   timestamp
);
insert into inventario_ciclico (id, ativo, semanas) values (1, false, 4);
