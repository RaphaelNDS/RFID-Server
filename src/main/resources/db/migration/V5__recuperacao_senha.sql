-- "Esqueceu a senha?" sem email: o pedido fica no próprio usuário e o ADMIN gera uma senha temporária.

-- Quando o usuário pediu uma senha nova (nulo = nenhum pedido em aberto). Um pedido por pessoa: pedir de novo
-- não cria outro registro, então o formulário público não tem como encher o banco.
alter table usuarios add column senha_pedida_em timestamp;

-- Senha temporária gerada pelo ADMIN: o usuário é obrigado a trocá-la logo depois de entrar.
alter table usuarios add column trocar_senha boolean default false not null;
