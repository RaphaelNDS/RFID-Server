-- 1.9.0: fotos e anexos do bem (foto do equipamento, nota fiscal em PDF, laudo...).
--
-- Os arquivos ficam NO BANCO (bytea): o backup diário (pg_dump) e a cópia cifrada fora do computador já levam tudo
-- junto, sem pasta nova para guardar. O conteúdo fica numa tabela separada para a lista de anexos não ler os bytes.
-- Excluir a etiqueta apaga os anexos dela (on delete cascade); o histórico guarda os nomes.
create table anexos_bem (
    id            bigserial primary key,
    tag_id        bigint       not null references tags (id) on delete cascade,
    nome          varchar(150) not null,
    tipo          varchar(20)  not null,
    tamanho_bytes integer      not null,
    descricao     varchar(200),
    enviado_em    timestamp    not null,
    enviado_por   varchar(255) not null,
    constraint ck_anexos_bem_tipo check (tipo in ('JPEG', 'PNG', 'WEBP', 'PDF')),
    constraint ck_anexos_bem_tamanho check (tamanho_bytes > 0)
);
create index ix_anexos_bem_tag on anexos_bem (tag_id, id);

create table anexos_bem_conteudo (
    anexo_id bigint primary key references anexos_bem (id) on delete cascade,
    conteudo bytea not null
);
