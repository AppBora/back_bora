-- 99Food: o App ID da plataforma era o do aplicativo de TESTE.
--
-- MEDIDO NA PRODUCAO EM 10/11/2026, no log da v153:
--   loja 18 (funciona): client_id enviado = 5764607707103889279_zira-acaiteria
--   loja 20 (401):      client_id enviado = 5764607673624955027_zira-sao-bento
--
-- Os App ID sao DIFERENTES. `credencial.opendelivery.client-id` guardava 5764607673624955027, e o
-- aplicativo de producao (`BR_P_BoraHapp`, no PASSO-A-PASSO-99FOOD.md) e o 5764607707103889279 --
-- o que a loja 18 usa na linha dela. O par da plataforma era inteiro do app de teste: o App ID de
-- teste e, no campo do segredo, o NOME do app de teste ("BR_T_BoraHapp", 13 caracteres).
--
-- COMO EU DEIXEI ISSO PASSAR POR DOIS MESES (vale mais que o conserto):
-- eu medi o TAMANHO do client-id (19) e o PREFIXO (5764607) e declarei que era o App ID certo --
-- cheguei a escrever na memoria "o client-id tem 19 = o App ID 5764607707103889279, certo". Os dois
-- App ID tem 19 digitos e comecam com 5764607. **Comparei um comprimento e afirmei identidade.**
-- Nenhum log mostrava o campo que SAIA na chamada, porque o catch do 401 descartava tudo. A v153
-- passou a logar o client_id enviado, e a causa apareceu na primeira tentativa.
--
-- A V68 (hoje, 14:15) consertou o segredo -- ele era o nome do app -- mas, sem esta, deixou o par
-- incoerente: segredo do app de PRODUCAO com App ID do app de TESTE. 401 garantido. As duas juntas
-- formam o par certo. A V68 continua necessaria; esta nao a substitui.
--
-- De onde vem o valor: da propria loja que funciona, nunca digitado. O client_id dela e
-- AppID + "_" + APPShopID, e o APPShopID esta no merchant_id -- entao o App ID e o que sobra ao
-- tirar o final. Assim o conserto nao depende de eu copiar 19 digitos a mao, que e o tipo de erro
-- que originou este bug.
--
-- ===================================================================================
-- POR QUE FALTA DE FONTE NAO ABORTA (corrigido em 10/11, achado da revisao do DBA)
--
-- A primeira versao desta migracao fazia `IF v_quantas <> 1 THEN RAISE EXCEPTION`. Isso transforma
-- **migracao de dado de producao em pre-condicao de boot**, e era um defeito grave:
--
--   - Num banco NOVO (ambiente novo, dump restaurado em outro lugar, banco de teste de integracao)
--     nao existe loja 18 nenhuma. v_quantas = 0, excecao, Flyway falha, a API NAO SOBE.
--   - Na producao, o efeito desta migracao ja esta aplicado (eu rodei o SQL a mao as 14:55:16 e o
--     Flyway ainda nao a registrou), entao o proximo deploy vai executa-la. O token da loja 18 vence
--     de hora em hora. Se o deploy cair na janela entre o vencimento e a renovacao,
--     `access_token IS NOT NULL` falha, v_quantas = 0, e o deploy quebra por um motivo que nada tem
--     a ver com o que esta sendo deployado.
--
-- A regra certa: abortar so quando ha AMBIGUIDADE (mais de uma fonte possivel, e eu nao posso
-- escolher por conta propria qual e a certa). Com ZERO fontes nao ha nada a fazer, e isso e um
-- estado legitimo -- avisa e sai.
--
-- Os dois UPDATEs de token tambem passaram para DENTRO do bloco, e so rodam quando o App ID
-- realmente mudou. Antes rodavam sempre: em todo deploy futuro eles zerariam o token das
-- integracoes sem credencial propria, forcando um novo ciclo de login em producao sem motivo.
-- ===================================================================================

DO $$
DECLARE
    v_app_id  text;
    v_quantas int;
    v_atual   text;
BEGIN
    SELECT count(*) INTO v_quantas
      FROM integracao_canal
     WHERE canal = 'NOVE_NOVE' AND status = 'CONECTADO'
       AND client_id IS NOT NULL AND merchant_id IS NOT NULL
       AND access_token IS NOT NULL
       AND right(client_id, length(merchant_id) + 1) = '_' || merchant_id;

    -- Mais de uma fonte: nao da para escolher, e escolher errado grava o App ID errado em todas as
    -- lojas da plataforma. Esse caso SIM precisa de gente olhando.
    IF v_quantas > 1 THEN
        RAISE EXCEPTION 'V69 abortada: ha % integracoes NOVE_NOVE conectadas com client_id '
                        'terminando em _<merchant_id>, e nao da para saber de qual tirar o App ID '
                        'da plataforma. Conferir a mao.', v_quantas;
    END IF;

    -- Nenhuma fonte: banco novo, banco de teste, ou a loja de referencia sem token no instante do
    -- deploy. Nao ha o que corrigir e isto NAO e erro.
    IF v_quantas = 0 THEN
        RAISE NOTICE 'V69: nenhuma integracao NOVE_NOVE conectada com credencial propria para servir '
                     'de fonte do App ID. Nada a fazer (banco novo, ou a loja de referencia esta sem '
                     'token agora). A migracao segue como aplicada.';
        RETURN;
    END IF;

    SELECT left(client_id, length(client_id) - length(merchant_id) - 1) INTO v_app_id
      FROM integracao_canal
     WHERE canal = 'NOVE_NOVE' AND status = 'CONECTADO'
       AND client_id IS NOT NULL AND merchant_id IS NOT NULL
       AND access_token IS NOT NULL
       AND right(client_id, length(merchant_id) + 1) = '_' || merchant_id;

    IF v_app_id IS NULL OR v_app_id = '' OR v_app_id !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'V69 abortada: o App ID extraido da loja conectada nao e numerico (%). '
                        'Gravar isso quebraria a autenticacao de todas as lojas.', v_app_id;
    END IF;

    SELECT valor INTO v_atual
      FROM config_plataforma WHERE chave = 'credencial.opendelivery.client-id';

    IF v_atual IS NOT NULL AND v_atual = v_app_id THEN
        RAISE NOTICE 'V69: o App ID da plataforma ja e o da loja conectada (%); nada a fazer.', v_app_id;
        RETURN;
    END IF;

    IF v_atual IS NOT NULL THEN
        INSERT INTO config_plataforma (chave, valor, atualizado_em)
        VALUES ('credencial.opendelivery.client-id.antes-da-v69', v_atual, now())
        ON CONFLICT (chave) DO NOTHING;
    END IF;

    INSERT INTO config_plataforma (chave, valor, atualizado_em)
    VALUES ('credencial.opendelivery.client-id', v_app_id, now())
    ON CONFLICT (chave) DO UPDATE SET valor = EXCLUDED.valor, atualizado_em = now();

    RAISE NOTICE 'V69: App ID da plataforma trocado de % para % (o do app de producao).', v_atual, v_app_id;

    -- Daqui para baixo so roda quando o App ID MUDOU de fato.
    --
    -- Trocar a credencial e manter o token antigo faria a loja seguir no app velho ate o token
    -- vencer -- mesmo motivo do esquecerTokens() do CredenciaisMarketplace. So mexe em quem NAO tem
    -- credencial propria: a loja de referencia usa a dela e continua funcionando.
    UPDATE integracao_canal
       SET access_token = NULL, token_expira_em = NULL
     WHERE canal = 'NOVE_NOVE'
       AND (client_secret IS NULL OR client_secret = '');

    -- Solta o recuo de 10 min para a proxima rodada tentar na hora.
    --
    -- ATENCAO AO MEDIR: isto dispara o poller no mesmo segundo em que a migracao commita. Falha
    -- registrada nesse segundo NAO prova nada -- foi o erro que eu cometi as 14:15 de hoje, quando
    -- li o log 830ms depois do meu proprio commit e quase conclui dali. Soltar o backoff num
    -- comando SEPARADO depois do commit, e exigir hora claramente posterior.
    UPDATE integracao_canal
       SET ultimo_polling_em = NULL, ultimo_erro = NULL
     WHERE canal = 'NOVE_NOVE' AND status = 'ERRO'
       AND (client_secret IS NULL OR client_secret = '');
END $$;
