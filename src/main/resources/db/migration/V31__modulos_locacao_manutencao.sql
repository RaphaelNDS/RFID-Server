-- 1.15.0: perfis por função. A locação (clientes, pedidos, saída, devolução, conferência, disponibilidade, painel) e a
-- manutenção saem do módulo RFID e ganham módulos próprios, LOCACAO e MANUTENCAO, para os perfis Expedição,
-- Conferente e Técnico poderem ter um sem o outro.
--
-- Ninguém perde acesso: quem tinha RFID com uma ação passa a ter também LOCACAO e MANUTENCAO com a mesma ação (é o que
-- valia até a 1.14.0, quando as duas telas pediam as permissões de RFID). Sem repetir o que já existir.
insert into permissoes (usuario_id, modulo, acao)
select p.usuario_id, novo.modulo, p.acao
from permissoes p
cross join (values ('LOCACAO'), ('MANUTENCAO')) as novo (modulo)
where p.modulo = 'RFID'
  and not exists (select 1 from permissoes q where q.usuario_id = p.usuario_id and q.modulo = novo.modulo and q.acao = p.acao);
