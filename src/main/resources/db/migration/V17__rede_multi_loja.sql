-- Rede multi-loja: um usuário pode estar vinculado a várias lojas (dono de rede).
-- O vínculo dá acesso à loja via troca de contexto (novo JWT); usuario.loja_id segue sendo a loja padrão.
CREATE TABLE usuario_loja (
    usuario_id BIGINT NOT NULL REFERENCES usuario (id),
    loja_id    BIGINT NOT NULL REFERENCES loja (id),
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (usuario_id, loja_id)
);
CREATE INDEX ix_usuario_loja_loja ON usuario_loja (loja_id);

-- Todo usuário existente ganha o vínculo com a própria loja.
INSERT INTO usuario_loja (usuario_id, loja_id)
SELECT id, loja_id FROM usuario WHERE loja_id IS NOT NULL;
