-- 99Food: o "App Secret da plataforma" guardava o NOME do app, nao o segredo dele.
--
-- MEDIDO NA PRODUCAO EM 10/11/2026:
--   config_plataforma['credencial.opendelivery.client-secret'] = 13 caracteres, EM TEXTO PURO,
--   comecando em "BR_T_Bo". "BR_T_BoraHapp" tem exatamente 13 caracteres, e o app que o dono
--   precisa usar chama "BR_P_BoraHapp" -- _T_ de teste no lugar de _P_ de producao. Ou seja:
--   alguem colou o identificador do app no campo do segredo. Mandavamos o nome do app como senha
--   dele, e a 99 respondia 502 "recusou a credencial" para TODA loja que caia nessa credencial.
--
-- A loja 18 (Montreal) escapava porque tem client_id/client_secret PROPRIOS na linha dela, e o
-- OpenDeliveryClient prefere a credencial da loja quando ela existe (credencialDaLoja). O segredo
-- dela tem, decifrado, exatamente 32 bytes -- que e o tamanho que o javadoc do
-- CredenciaisMarketplace.tamanhoDoSecret ja registrava como "o do unico app de 99Food que funciona
-- neste sistema", medido em 25/10/2026. O numero certo estava na tela desde entao.
--
-- O App Secret e do APLICATIVO, nao da loja: o ClientId e AppID_AppShopId e as 5 lojas da Zira sao
-- shops do MESMO App ID (5764607707103889279). Entao o segredo que funciona na loja 18 e o segredo
-- da plataforma, e esta guardado no lugar errado.
--
-- CORRECAO DE UMA AFIRMACAO FALSA QUE ESTAVA AQUI (reescrito em 10/11/2026, depois do flyway repair):
-- este comentario dizia que 5764607707103889279 era "o mesmo que esta em
-- credencial.opendelivery.client-id". **Nao era.** Aquele campo guardava 5764607673624955027, que e
-- o App ID do aplicativo de TESTE. Eu nao medi: conferi que o valor tinha 19 digitos e comecava com
-- 5764607 e declarei identidade -- e os DOIS App ID tem 19 digitos e comecam com 5764607. Por causa
-- dessa frase, a V68 saiu achando que bastava consertar o segredo, e as lojas 19-22 continuaram em
-- 401 por mais tres minutos depois do commit, ate a V69 consertar o App ID tambem. As duas juntas
-- formam o par certo.
--
-- POR QUE COPIAR O TEXTO CIFRADO FUNCIONA:
-- o SegredoGuardado usa AES-256/GCM com chave global (BORA_SEGREDO_COLUNAS), nonce gravado DENTRO do
-- proprio valor e **sem AAD** -- nao ha updateAAD em nenhum dos dois metodos. Logo o criptograma nao
-- esta amarrado a linha nem a tabela: o mesmo "enc:v1:..." decifra igual em config_plataforma.valor,
-- que passa pelo MESMO @Convert(SegredoGuardado). Copiamos o criptograma; ninguem -- nem a migracao,
-- nem o log, nem quem le este arquivo -- ve o segredo em claro.
--
-- DEPOIS DISTO, as lojas 19-22 nao precisam de credencial propria: com o merchant_id ja corrigido
-- pela V67 (APPShopID), o clientIdDaLoja cai no ramo appId() + "_" + merchantId e monta
-- 5764607707103889279_zira-zona-norte etc., agora com o segredo certo.
--
-- SE A HIPOTESE ESTIVER ERRADA (se a 99 emitir segredo por loja, e nao por app), as 19-22 continuam
-- falhando exatamente como hoje -- elas estao em 100% de falha. O pior caso e empate, e o valor que
-- estamos substituindo e comprovadamente lixo (o nome do app).

-- ===================================================================================
-- POR QUE ESTE ARQUIVO FOI REESCRITO DEPOIS DE JA ESTAR APLICADO (10/11/2026)
--
-- A primeira versao fazia `IF v_quantas <> 1 THEN RAISE EXCEPTION`, e isso transforma **migracao de
-- dado de producao em pre-condicao de boot**. Num banco VAZIO nao existe loja 18 nenhuma: v_quantas
-- = 0, excecao, Flyway falha, a API NAO SOBE. Efeito medido: os 211 testes de integracao so rodavam
-- com `-Dspring.flyway.target=67`, e um ambiente novo do zero nao subia.
--
-- A regra certa, a mesma da V69: abortar so quando ha AMBIGUIDADE (mais de uma fonte possivel, e eu
-- nao posso escolher sozinho qual e a certa). Com ZERO fontes nao ha nada a fazer, e esse e um
-- estado legitimo -- avisa e sai.
--
-- EDITAR ESTE ARQUIVO EXIGIU UM `flyway repair` NA PRODUCAO, e e por isso que isto esta escrito
-- aqui: o Flyway valida o checksum de toda migracao ja aplicada, entao mudar o arquivo faria o
-- proximo deploy falhar na VALIDACAO, antes de qualquer migracao rodar. O repair nao re-executa
-- nada; ele so realinha o checksum gravado com o do arquivo. Na producao o efeito desta migracao ja
-- estava aplicado, entao nada precisava rodar de novo.
--
-- O QUE ISSO CUSTA, e e o preco de ter escrito a guarda errada: a producao foi corrigida pelo
-- arquivo ANTIGO, e nenhum banco vai executar este texto novo em cima dela. Quem garante que o novo
-- esta certo e o banco vazio -- os ITs e um ambiente novo --, nao a producao.
-- ===================================================================================

DO $$
DECLARE
    v_cifrado  text;
    v_quantas  int;
    v_atual    text;
BEGIN
    -- A linha-fonte: a unica integracao da 99 com credencial propria que esta de fato buscando
    -- pedido (CONECTADO e com token vivo). Nao fixo "loja 18" de proposito: se alguem corrigir
    -- outra loja antes, a fonte certa continua sendo achada.
    SELECT count(*) INTO v_quantas
      FROM integracao_canal
     WHERE canal = 'NOVE_NOVE' AND status = 'CONECTADO'
       AND client_secret IS NOT NULL AND client_secret <> ''
       AND access_token IS NOT NULL;

    -- Mais de uma fonte: ha AMBIGUIDADE, e escolher errado grava o segredo errado para toda a
    -- plataforma. Esse caso SIM precisa de gente olhando.
    IF v_quantas > 1 THEN
        RAISE EXCEPTION 'V68 abortada: ha % integracoes NOVE_NOVE conectadas com credencial propria, '
                        'e nao da para saber de qual tirar o App Secret da plataforma. '
                        'Conferir a mao.', v_quantas;
    END IF;

    -- Nenhuma fonte: banco novo, banco de teste de integracao, ou a loja de referencia sem token no
    -- instante do deploy. Nao ha o que corrigir, e isto NAO e erro. Ver o bloco acima.
    IF v_quantas = 0 THEN
        RAISE NOTICE 'V68: nenhuma integracao NOVE_NOVE conectada com credencial propria para servir '
                     'de fonte do App Secret. Nada a fazer (banco novo, ou a loja de referencia esta '
                     'sem token agora). A migracao segue como aplicada.';
        RETURN;
    END IF;

    SELECT client_secret INTO v_cifrado
      FROM integracao_canal
     WHERE canal = 'NOVE_NOVE' AND status = 'CONECTADO'
       AND client_secret IS NOT NULL AND client_secret <> ''
       AND access_token IS NOT NULL;

    SELECT valor INTO v_atual
      FROM config_plataforma WHERE chave = 'credencial.opendelivery.client-secret';

    IF v_atual IS NOT NULL AND v_atual = v_cifrado THEN
        RAISE NOTICE 'V68: o App Secret da plataforma ja e o da loja conectada; nada a fazer.';
        RETURN;
    END IF;

    -- Guarda o valor anterior para poder voltar sem depender do dump. Fica na mesma tabela, com o
    -- prefixo "credencial.", que o ehChaveDeCredencial mantem fora de toda listagem do painel; e
    -- inerte porque todo acesso e por findById de chave exata.
    IF v_atual IS NOT NULL THEN
        INSERT INTO config_plataforma (chave, valor, atualizado_em)
        VALUES ('credencial.opendelivery.client-secret.antes-da-v68', v_atual, now())
        ON CONFLICT (chave) DO NOTHING;
    END IF;

    INSERT INTO config_plataforma (chave, valor, atualizado_em)
    VALUES ('credencial.opendelivery.client-secret', v_cifrado, now())
    ON CONFLICT (chave) DO UPDATE SET valor = EXCLUDED.valor, atualizado_em = now();

    RAISE NOTICE 'V68: App Secret da plataforma trocado pelo da loja conectada (criptograma copiado).';

    -- Daqui para baixo so roda quando o segredo MUDOU de fato. Estes dois UPDATE ficavam FORA do
    -- bloco, e rodavam sempre -- inclusive nos caminhos de "nada a fazer". Em todo deploy futuro
    -- eles zerariam o token das integracoes sem credencial propria, forcando um ciclo de login novo
    -- em producao sem motivo nenhum.

    -- Trocar a credencial e manter o token antigo faria a loja seguir no app velho ate o token
    -- vencer -- e o mesmo motivo do esquecerTokens() do CredenciaisMarketplace. So mexe em quem NAO
    -- tem credencial propria: a loja conectada continua com o token dela, que funciona.
    UPDATE integracao_canal
       SET access_token = NULL, token_expira_em = NULL
     WHERE canal = 'NOVE_NOVE'
       AND (client_secret IS NULL OR client_secret = '');

    -- Zera o relogio do backoff: mereceNovaTentativa() exige status ERRO e ultimo_polling_em mais
    -- velho que 10 min. Com ultimo_polling_em nulo a proxima rodada do poller (30s) tenta na hora.
    --
    -- ATENCAO AO MEDIR: isto solta o poller no mesmo segundo em que a migracao commita. Falha
    -- registrada nesse segundo NAO prova nada -- foi o erro que eu cometi as 14:15 de 10/11, quando
    -- li o log 830ms depois do meu proprio commit e quase conclui dali.
    UPDATE integracao_canal
       SET ultimo_polling_em = NULL, ultimo_erro = NULL
     WHERE canal = 'NOVE_NOVE' AND status = 'ERRO'
       AND (client_secret IS NULL OR client_secret = '');
END $$;
