package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O identificador que vai para a 99 e sempre {app_id}_{app_shop_id}.
 *
 * <p>Com a credencial da plataforma o Bora ja montava a juncao. Com credencial propria da loja ele
 * mandava o campo cru, assumindo um client_id completo. Em 09/10/2026 isso custou cinco tentativas
 * para ligar a primeira loja real: o dono colava o App ID do aplicativo de producao, a 99 recebia um
 * identificador sem loja e recusava, e a mensagem da tela falava em "confira o App Shop ID e o App
 * Secret" — nao em "faltou juntar os dois".</p>
 */
class ClientIdDaLojaTest {

    private OpenDeliveryClient comAppDaPlataforma(String appId) {
        CredenciaisMarketplace c = mock(CredenciaisMarketplace.class);
        when(c.clientId("NOVE_NOVE")).thenReturn(appId);
        when(c.clientSecret("NOVE_NOVE")).thenReturn("segredo-da-plataforma");
        return new OpenDeliveryClient("http://nao-usado", c,
                mock(IntegracaoCanalRepository.class), new MarketplaceHttp());
    }

    private IntegracaoCanal loja(String clientIdProprio, String secretProprio, String appShopId) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 18L;
        i.canal = "NOVE_NOVE";
        i.merchantId = appShopId;
        i.clientId = clientIdProprio;
        i.clientSecret = secretProprio;
        return i;
    }

    @Test
    void semCredencialPropria_juntaOAppDaPlataformaComALoja() {
        var c = comAppDaPlataforma("111");

        assertEquals("111_boraloja2", c.clientIdDaLoja(loja(null, null, "boraloja2")));
    }

    @Test
    void credencialPropria_soComOAppId_oBoraJuntaSozinho() {
        var c = comAppDaPlataforma("111");

        assertEquals("999_zira-acaiteria", c.clientIdDaLoja(loja("999", "s", "zira-acaiteria")),
                "colar so o App ID tem que bastar; juntar a mao custou 5 tentativas em 09/10");
    }

    @Test
    void credencialPropria_jaCompleta_ehRespeitada() {
        var c = comAppDaPlataforma("111");

        assertEquals("999_zira-acaiteria",
                c.clientIdDaLoja(loja("999_zira-acaiteria", "s", "zira-acaiteria")),
                "quem ja configurou com a juncao a mao nao pode quebrar");
    }

    @Test
    void credencialPropria_comEspacoSobrando_naoEstragaOIdentificador() {
        var c = comAppDaPlataforma("111");

        assertEquals("999_zira-acaiteria", c.clientIdDaLoja(loja("  999  ", "s", "zira-acaiteria")),
                "espaco colado junto do numero nao pode ir para a 99");
    }

    @Test
    void credencialPropria_semCodigoDaLoja_mandaOQueTem() {
        var c = comAppDaPlataforma("111");

        assertEquals("999", c.clientIdDaLoja(loja("999", "s", null)),
                "sem o codigo da loja nao ha o que juntar; a recusa da 99 dira o que falta");
    }
}
