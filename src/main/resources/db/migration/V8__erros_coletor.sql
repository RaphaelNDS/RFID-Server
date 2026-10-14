-- Erros do app Android nos coletores (travamentos e falhas relevantes), enviados pelo app quando há rede.
-- O id é gerado no coletor: reenviar o mesmo erro não duplica. Erros repetidos em sequência chegam agrupados
-- (ocorrencias). Apagados depois de rfid.coletores.dias-erros.
create table erros_coletor (
    id            varchar(36)   primary key,
    coletor       varchar(100)  not null,
    momento       timestamp     not null,
    recebido_em   timestamp     not null,
    tipo          varchar(30)   not null,
    mensagem      varchar(500)  not null,
    detalhe       varchar(8000),
    ocorrencias   integer       default 1 not null,
    versao_app    varchar(50),
    codigo_versao integer,
    operador      varchar(255)
);

create index idx_erros_coletor_coletor_momento on erros_coletor (coletor, momento);
create index idx_erros_coletor_recebido_em on erros_coletor (recebido_em);
