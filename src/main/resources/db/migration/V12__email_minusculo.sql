-- O email é o login: passa a ser gravado sem espaços nas pontas e em minúsculas (UserService.normalizarEmail).
-- Ajusta os já cadastrados. Se dois usuários só se diferenciam por maiúsculas (o cadastro já recusava isso),
-- os dois ficam como estão: o login compara sem diferenciar maiúsculas e o ADMIN resolve pela tela de usuários.
update usuarios u
set email = lower(trim(u.email))
where u.email <> lower(trim(u.email))
  and not exists (
      select 1 from usuarios o
      where o.id <> u.id and lower(trim(o.email)) = lower(trim(u.email))
  );
