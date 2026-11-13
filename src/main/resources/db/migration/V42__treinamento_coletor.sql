-- 1.31.0: modo treinamento por coletor. Coletor em treinamento lê e faz inventário sem mudar nada de verdade: as
-- leituras não são gravadas nem viram pendentes, o inventário fica marcado como treino (não muda o local dos bens nem
-- conta no inventário cíclico) e cadastro e transferência pelo coletor são recusados.
alter table coletores add column treinamento boolean not null default false;
alter table inventarios add column treino boolean not null default false;
