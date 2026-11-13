-- 1.26.0: garantia do fabricante. O equipamento guarda até quando vai a garantia e com quem (fabricante ou fornecedor);
-- a ordem de manutenção grava se o bem estava na garantia quando foi aberta, para acionar o fabricante em vez de pagar.
alter table tags add column garantia_ate date;
alter table tags add column garantia_fornecedor varchar(160);
alter table manutencoes add column em_garantia boolean not null default false;
alter table manutencoes add column garantia_ate date;
alter table manutencoes add column garantia_fornecedor varchar(160);
