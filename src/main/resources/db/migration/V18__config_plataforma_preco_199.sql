-- Configurações globais da plataforma (chave/valor) — ex.: módulo fiscal ligado/desligado.
-- Visível e editável apenas pelo ADMINISTRADOR_BORA.
CREATE TABLE config_plataforma (
    chave         VARCHAR(60) PRIMARY KEY,
    valor         VARCHAR(255) NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Fiscal NFC-e nasce DESLIGADO (decisão 2026-07-07: ligar quando faturamento >= R$ 5 mil/mês livre).
INSERT INTO config_plataforma (chave, valor) VALUES ('fiscal.habilitado', 'false');

-- Preço de lançamento: R$ 199/mês por loja (era 299).
UPDATE assinatura SET valor = 199.00, atualizado_em = NOW();
