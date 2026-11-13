-- 1.19.0: cliente com restrição. BLOQUEADO não abre pedido nem tem saída; APROVACAO só tem saída depois que
-- um administrador ou gestor aprova o pedido. A devolução nunca é barrada.
alter table clientes add column restricao varchar(20) not null default 'NENHUMA';
alter table clientes add constraint ck_clientes_restricao check (restricao in ('NENHUMA', 'APROVACAO', 'BLOQUEADO'));
alter table clientes add column motivo_restricao varchar(300);
alter table clientes add column restricao_em timestamp;
alter table clientes add column restricao_por varchar(255);

alter table pedidos_locacao add column aprovado_por varchar(255);
alter table pedidos_locacao add column aprovado_em timestamp;
