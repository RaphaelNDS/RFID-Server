-- Usuário desativado pelo ADMIN não entra mais (web e coletor), mas continua no banco: o histórico de quem
-- cadastrou, leu e alterou cada coisa depende dele. Todos os usuários que já existem começam ativos.
alter table usuarios add column ativo boolean not null default true;
