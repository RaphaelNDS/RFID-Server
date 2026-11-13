-- 1.30.0: configurações do sistema em chave e valor. Primeiro uso: os módulos tirados do menu ("modulo.<NOME>" =
-- "desligado") e o quadro Primeiros passos do painel inicial ("primeiros-passos.oculto" = "sim"). Sem linha = padrão.
create table configuracoes_sistema (
    chave         varchar(80)  primary key,
    valor         varchar(500) not null,
    alterado_por  varchar(255),
    alterado_em   timestamp
);
