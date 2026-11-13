-- 1.21.0: revisão automática por uso. Uma regra por tipo de equipamento: revisar a cada N locações e/ou a cada D dias,
-- contados da última manutenção preventiva concluída (ou do cadastro do bem). Pelo menos um dos dois é obrigatório.
create table regras_revisao (
    tipo_equipamento_id  bigint      primary key references tipo_equipamento (id) on delete cascade,
    a_cada_locacoes      integer,
    a_cada_dias          integer,
    alterada_por         varchar(255) not null,
    alterada_em          timestamp    not null default now(),
    constraint ck_regras_revisao_alguma check (a_cada_locacoes is not null or a_cada_dias is not null),
    constraint ck_regras_revisao_locacoes check (a_cada_locacoes is null or a_cada_locacoes between 1 and 1000),
    constraint ck_regras_revisao_dias check (a_cada_dias is null or a_cada_dias between 1 and 3650)
);
