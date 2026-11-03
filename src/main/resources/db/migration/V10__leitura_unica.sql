-- A mesma leitura (mesma etiqueta, mesma hora de leitura no coletor) só pode estar gravada uma vez.
-- O serviço já conferia antes de gravar, mas duas requisições ao mesmo tempo (o coletor reenvia o lote quando a
-- resposta demora) passavam as duas pela conferência e gravavam em dobro. Agora o próprio banco recusa a segunda.

-- Remove as repetidas que já existam, ficando com a primeira gravada de cada (tag, data_hora)
delete from leituras_android a
using leituras_android b
where a.tag = b.tag
  and a.data_hora = b.data_hora
  and a.id > b.id;

create unique index uk_leituras_android_tag_data_hora on leituras_android (tag, data_hora);
