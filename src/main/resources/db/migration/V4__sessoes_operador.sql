-- Login de operador no coletor Android: sessões de 12h e o nome de quem fez cada ação.

-- Guarda só o HASH (SHA-256) do token, nunca o token: se o banco ou um backup vazar, as sessões não vazam.
-- Cada sessão vale só no coletor em que foi criada. Encerrada (logout) ou expirada, não autoriza novas
-- ações, mas continua valendo para ações feitas ANTES disso (o coletor pode enviá-las depois, offline).
create table sessoes_operador (
    token_hash   varchar(64)  primary key,
    usuario_id   bigint       not null,
    dispositivo  varchar(100) not null,
    criada_em    timestamp    not null,
    expira_em    timestamp    not null,
    encerrada_em timestamp,
    constraint fk_sessoes_operador_usuario foreign key (usuario_id) references usuarios (id) on delete cascade
);

create index idx_sessoes_operador_dispositivo on sessoes_operador (dispositivo);

-- Quem fez (nome do operador). Nulo nos registros anteriores ao login de operador.
alter table leituras_android add column operador varchar(255);
alter table inventarios add column operador varchar(255);
alter table inventario_itens add column operador varchar(255);
