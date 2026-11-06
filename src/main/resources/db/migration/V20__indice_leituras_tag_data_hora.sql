-- Índice (tag, data_hora) das leituras para a última leitura de cada etiqueta (relatório "não vistas" e exportação do
-- cadastro: TagRepository.ultimasVezesPorTag faz max(data_hora) por tag). A V19 trocou o índice único da V10 por um
-- parcial (só as leituras sem a hora do coletor), que não serve a essa consulta: sem este, cada etiqueta varria a tabela.
-- Não é único: a regra de leitura repetida continua com os índices da V16 e da V19.
create index if not exists idx_leituras_android_tag_data_hora on leituras_android (tag, data_hora);
