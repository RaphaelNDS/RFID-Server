-- 1) Senha temporária com prazo: guarda quando o ADMIN a gerou. Sem isso ela valia para sempre: a pessoa que nunca
--    entrou podia ter a conta tomada meses depois por quem tivesse acesso à mensagem com a senha.
--    As já existentes contam a partir de agora (o prazo começa na instalação desta versão).
alter table usuarios add column senha_temporaria_em timestamp;
update usuarios set senha_temporaria_em = now() where trocar_senha;

-- 2) Leitura única: o índice (tag, data_hora) da V10 ficou só para as leituras sem a hora do coletor. Desde a V16 o
--    reenvio é identificado por (tag, lido_em_coletor); o índice antigo recusava como "repetida" a mesma tag lida
--    duas vezes com o relógio do coletor adiantado (as duas ganham data_hora = hora do recebimento).
drop index if exists uk_leituras_android_tag_data_hora;
create unique index uk_leituras_android_tag_data_hora_sem_hora_coletor
    on leituras_android (tag, data_hora) where lido_em_coletor is null;
