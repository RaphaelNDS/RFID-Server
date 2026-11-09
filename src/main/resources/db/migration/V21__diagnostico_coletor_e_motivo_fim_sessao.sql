-- 1.4.4
--
-- Diagnóstico do coletor (tela Coletores). O app 1.7.x manda em toda requisição a fila de envio, a última
-- sincronização completa, o espaço livre, a versão do Android, o modelo e a hora do aparelho. Guarda o último valor
-- recebido. O desvio do relógio vai em segundos: hora do coletor menos a do servidor (positivo = coletor adiantado).
-- Tudo opcional: os apps anteriores à 1.7 não mandam esses cabeçalhos.
alter table coletores add column if not exists fila_pendentes integer;
alter table coletores add column if not exists fila_mais_antiga timestamp;
alter table coletores add column if not exists ultima_sinc_ok timestamp;
alter table coletores add column if not exists espaco_livre_mb bigint;
alter table coletores add column if not exists versao_android varchar(20);
alter table coletores add column if not exists modelo varchar(100);
alter table coletores add column if not exists desvio_relogio_segundos bigint;

-- Por que a sessão do operador acabou antes do prazo: SAIR ("Sair" no app), NOVO_LOGIN (outro login no mesmo coletor)
-- ou ADMIN (desativação, senha temporária ou troca de senha). Só SAIR e NOVO_LOGIN têm a margem de relógio no fim
-- (SessaoOperadorService.MARGEM_FIM_PELO_COLETOR). Nulo nas sessões abertas e nas encerradas antes da 1.4.4, que ficam
-- sem margem, como antes.
alter table sessoes_operador add column if not exists encerrada_por varchar(20);
