-- Situação de cada coletor Android, para a tela Coletores: quando falou com o servidor pela última vez, de
-- qual IP, com qual versão do app e qual operador. Atualizada pelos contatos aceitos em /api/android
-- (no máximo uma gravação por minuto por coletor, ou na hora quando algo muda).
-- O nome é o mesmo da chave do coletor em rfid.dispositivos.chaves.
create table coletores (
    nome             varchar(100) primary key,
    primeiro_contato timestamp    not null,
    ultimo_contato   timestamp    not null,
    ultimo_ip        varchar(64),
    versao_app       varchar(50),
    codigo_versao    integer,
    ultimo_operador  varchar(255)
);
