-- 1.24.0: agenda de entregas e retiradas. Cada pedido pode ter quem leva e a hora combinada da entrega, e quem busca e
-- a hora combinada da retirada (devolução no cliente). Tudo opcional: sem isso, o pedido aparece na agenda sem hora.
alter table pedidos_locacao add column entrega_responsavel varchar(120);
alter table pedidos_locacao add column entrega_hora time;
alter table pedidos_locacao add column retirada_responsavel varchar(120);
alter table pedidos_locacao add column retirada_hora time;
