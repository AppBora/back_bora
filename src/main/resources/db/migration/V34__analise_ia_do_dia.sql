-- Teto de custo da IA: uma análise paga por usuário por dia.
--
-- Guardar o plano em vez de só contar chamadas resolve duas coisas de uma vez: segura a conta da
-- Anthropic e deixa o lojista reabrir a análise do dia quantas vezes quiser, de graça.
CREATE TABLE IF NOT EXISTS ia_analise_rede (
    id          BIGSERIAL PRIMARY KEY,
    usuario_id  BIGINT      NOT NULL,
    loja_id     BIGINT,
    dia         DATE        NOT NULL,
    inicio      DATE,
    fim         DATE,
    plano       TEXT        NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- É o índice que aplica o limite: uma linha por usuário por dia.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ia_analise_usuario_dia ON ia_analise_rede (usuario_id, dia);
