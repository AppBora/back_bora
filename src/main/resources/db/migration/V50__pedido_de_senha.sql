-- Quem esqueceu a senha avisa o administrador da loja SEM depender de WhatsApp.
--
-- Nao ha envio de e-mail no sistema, e o envio automatico por WhatsApp depende da Cloud API da Meta:
-- medido em 18/10/2026, as duas credenciais gravadas estao invalidas (a da loja 1 venceu em
-- 08/07/2026; a da loja 18 tem 9 caracteres, e placeholder). Ou seja, HOJE o sistema nao consegue
-- mandar mensagem nenhuma sozinho.
--
-- Entao o pedido fica gravado aqui e aparece na tela de Usuarios do administrador, que e quem pode
-- redefinir a senha. Isso funciona sem Meta, sem e-mail e sem custo. Quando a conta da Meta existir,
-- o mesmo registro dispara o aviso por WhatsApp -- o codigo ja chama o WhatsAppSender e grava em
-- `avisado_em` se a Meta aceitou.
--
-- Por que NAO existe `email` aqui: a tela de login nao pode virar um verificador de e-mail. O
-- endpoint publico responde sempre igual, exista o usuario ou nao, e so grava linha quando o usuario
-- existe de verdade. Guardar o e-mail digitado transformaria esta tabela num deposito de tentativas.

create table if not exists pedido_de_senha (
  id          bigserial primary key,
  usuario_id  bigint      not null references usuario(id) on delete cascade,
  loja_id     bigint,
  criado_em   timestamptz not null default now(),
  -- Quando o administrador redefiniu a senha dessa pessoa. Nulo = ainda esperando.
  atendido_em timestamptz,
  -- Quando o aviso por WhatsApp saiu de fato. Nulo = nao saiu (hoje, sempre).
  avisado_em  timestamptz
);

-- A tela de Usuarios pergunta "o que esta em aberto nesta loja": e esta consulta, e so ela.
create index if not exists idx_pedido_de_senha_aberto
  on pedido_de_senha (loja_id, criado_em desc)
  where atendido_em is null;

comment on table pedido_de_senha is
  'Pedidos de "esqueci minha senha". Quem resolve e o administrador da loja, na tela Usuarios.';
