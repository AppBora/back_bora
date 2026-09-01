-- Suspensao administrativa e arquivamento de loja pelo ADMINISTRADOR_BORA.
--
-- Por que um campo novo em vez de reusar loja.ativo: quem escreve loja.ativo hoje e o webhook do
-- Asaas (AssinaturaService.ativarLoja). Se o administrador desabilitasse pelo mesmo campo, o
-- proximo PAYMENT_CONFIRMED da mensalidade reativaria a loja sozinho, desfazendo a decisao dele.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS suspensa_pela_plataforma BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loja ADD COLUMN IF NOT EXISTS suspensa_em              TIMESTAMPTZ;
ALTER TABLE loja ADD COLUMN IF NOT EXISTS motivo_suspensao         VARCHAR(255);

-- "Excluir cliente" e arquivamento logico, nunca DELETE: pedido, assinatura e acerto_entregador
-- guardam historico financeiro, e 16 tabelas com loja_id nao tem FK para loja -- um DELETE
-- fisico travaria em algumas e deixaria as outras orfas em silencio.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS excluida_em     TIMESTAMPTZ;
ALTER TABLE loja ADD COLUMN IF NOT EXISTS excluida_por    BIGINT;
ALTER TABLE loja ADD COLUMN IF NOT EXISTS motivo_exclusao VARCHAR(255);

-- A listagem do painel filtra arquivadas por padrao; o indice parcial cobre esse caminho.
CREATE INDEX IF NOT EXISTS idx_loja_nao_excluida ON loja (id) WHERE excluida_em IS NULL;
