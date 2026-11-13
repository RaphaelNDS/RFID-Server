-- 2.1.0: o sistema simples fica só com a leitura e o cadastro das etiquetas, os inventários e a administração. As
-- Movimentações (saída e entrada de equipamentos, 2.0.0) saíram.
--
-- O servidor só chega aqui depois da trava do MigracoesConfig (TravaVersaoCompleta): banco com pedidos de locação, com
-- migração depois da V35, ou com V34/V35 de outra descrição (as da branch locadora) é recusado antes de migrar.
--
-- As tabelas movimentos e movimento_itens (V34) NÃO são apagadas: ficam no banco, sem uso, com as saídas e entradas
-- registradas na 2.0.0.

-- ===== Situações do equipamento: só DISPONIVEL (No estoque), EXTRAVIADO e BAIXADO =====

-- "Fora do estoque" só existia por causa da saída. Sem as Movimentações não haveria como trazer o equipamento de volta:
-- ele volta para "No estoque". Cada conversão fica no histórico da etiqueta (mesmo formato da V34)
insert into historico_etiquetas (tag_id, tag_real, acao, campo, valor_antigo, valor_novo, usuario, origem, momento)
select t.id, t.tag_real, 'ALTERACAO', 'Situação', 'Fora do estoque', 'No estoque', 'sistema', 'Atualização 2.1.0 (V35)', now()
from tags t
where t.situacao = 'FORA';

-- A versão muda junto (@Version): uma tela de edição aberta antes da atualização não grava a situação antiga por cima
update tags set situacao = 'DISPONIVEL', versao = versao + 1 where situacao = 'FORA';

alter table tags drop constraint ck_tags_situacao;
alter table tags add constraint ck_tags_situacao check (situacao in ('DISPONIVEL', 'EXTRAVIADO', 'BAIXADO'));

-- ===== Permissões: o módulo MOVIMENTO (Movimentações) saiu =====

-- Quem via as Movimentações via os equipamentos que saíam e entravam: continua vendo as etiquetas (Etiquetas · Ver),
-- se ainda não via. Criar, editar e excluir movimentações NÃO viram permissões de etiqueta (seria dar mais acesso do
-- que a pessoa tinha)
insert into permissoes (usuario_id, modulo, acao)
select distinct p.usuario_id, 'RFID', 'READ'
from permissoes p
where p.modulo = 'MOVIMENTO' and p.acao = 'READ'
  and not exists (select 1 from permissoes q where q.usuario_id = p.usuario_id and q.modulo = 'RFID' and q.acao = 'READ');

delete from permissoes where modulo = 'MOVIMENTO';
