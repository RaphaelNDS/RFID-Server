-- Versão do app retirada pelo ADMIN (publicada com problema): o servidor deixa de oferecê-la e volta a oferecer a
-- anterior. Quem já instalou a retirada fica nela até sair uma versão nova (o Android não instala código menor).
alter table versoes_app add column retirada_em timestamp;
alter table versoes_app add column retirada_por varchar(255);
alter table versoes_app add column motivo_retirada varchar(500);
