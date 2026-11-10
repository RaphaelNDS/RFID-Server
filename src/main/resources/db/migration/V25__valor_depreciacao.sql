-- 1.8.0: valor do bem e depreciação linear (CPC 27).
--
-- A vida útil (em meses) e o valor residual (% do valor de aquisição) ficam no TIPO de equipamento e valem para todos
-- os bens dele. Sem vida útil no tipo, o bem não deprecia (o valor contábil é o de aquisição).
alter table tipo_equipamento add column vida_util_meses integer;
alter table tipo_equipamento add column valor_residual_pct numeric(5, 2);
alter table tipo_equipamento add constraint ck_tipo_vida_util check (vida_util_meses is null or vida_util_meses between 1 and 1200);
alter table tipo_equipamento add constraint ck_tipo_residual check (valor_residual_pct is null or valor_residual_pct between 0 and 100);

-- O bem: quanto custou, quando foi comprado (a depreciação conta os meses completos desde essa data) e a nota fiscal.
-- Todos opcionais:
-- o bem sem valor fica fora dos totais e aparece como "sem valor" nos relatórios.
alter table tags add column valor_aquisicao numeric(14, 2);
alter table tags add column data_aquisicao date;
alter table tags add column nota_fiscal varchar(60);
alter table tags add constraint ck_tags_valor_aquisicao check (valor_aquisicao is null or valor_aquisicao >= 0);
