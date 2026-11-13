package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O "ID do loja" numerico da 99 passa a ter campo — e a trava que impede pedido na loja errada.
 *
 * <h2>Por que este teste existe (13/11/2026)</h2>
 * <p>A coluna {@code shop_id_canal} existe desde a V67 e o webhook casa por ela. <b>Nenhuma tela
 * escrevia nela</b>: cada unidade nova ficava pela metade esperando alguem abrir o banco — a
 * armadilha "endpoint sem tela", agora ao contrario (coluna sem tela). Abrir a 6a unidade da Zira
 * e exatamente o caso que descobriu isso.</p>
 *
 * <h2>A trava que importa, e que NAO existia nem para o campo antigo</h2>
 * <p>O {@code lojaDoHeader} do webhook acha a loja por <b>qualquer um</b> dos dois identificadores:
 * primeiro o App Shop ID ({@code merchantId}), depois o numerico. A checagem antiga comparava o
 * valor novo so com o <b>campo homonimo</b> das outras lojas. Entao o ID numerico desta loja podia
 * ser cadastrado igual ao App Shop ID de outra sem reclamacao nenhuma — e o pedido real de uma
 * cairia no painel da outra, calado. A checagem passou a olhar os dois campos.</p>
 */
class OidDaLOJAna99TEMcampoTest {

    private IntegracaoCanalRepository repo;
    private AuthContext ctx;
    private IntegracaoService service;
    private List<IntegracaoCanal> noBanco;

    @BeforeEach
    void montar() {
        repo = mock(IntegracaoCanalRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(23L);
        when(ctx.isAdminBora()).thenReturn(true);   // quem cadastra codigo de marketplace e o suporte
        noBanco = new ArrayList<>();
        when(repo.findByLojaIdAndCanal(any(), any())).thenReturn(Optional.empty());
        when(repo.findByCanal(any())).thenAnswer(inv -> noBanco);
        when(repo.save(any(IntegracaoCanal.class))).thenAnswer(inv -> inv.getArgument(0));
        // Sem client oficial na lista, o canal e tratado como "do lojista" -- o que deixa este teste
        // medir o campo, e nao o caminho de vinculo do marketplace.
        service = new IntegracaoService(repo, ctx, mock(PixService.class), List.of());
    }

    private IntegracaoCanal salvar(Map<String, Object> corpo) {
        final IntegracaoCanal[] gravada = new IntegracaoCanal[1];
        when(repo.save(any(IntegracaoCanal.class))).thenAnswer(inv -> {
            gravada[0] = inv.getArgument(0);
            return gravada[0];
        });
        service.salvar("NOVE_NOVE", corpo);
        return gravada[0];
    }

    private Map<String, Object> corpo(String appShopId, String idNumerico) {
        Map<String, Object> m = new HashMap<>();
        m.put("merchantId", appShopId);
        if (idNumerico != null) m.put("shopIdCanal", idNumerico);
        m.put("ativo", "true");
        return m;
    }

    private IntegracaoCanal deOutraLoja(Long lojaId, String appShopId, String idNumerico) {
        IntegracaoCanal o = new IntegracaoCanal();
        o.lojaId = lojaId;
        o.canal = "NOVE_NOVE";
        o.merchantId = appShopId;
        o.shopIdCanal = idNumerico;
        return o;
    }

    @Test
    void oIDnumericoEhGRAVADO() {
        IntegracaoCanal i = salvar(corpo("zira-meu-endereco", "5764608368417965999"));
        assertEquals("zira-meu-endereco", i.merchantId);
        assertEquals("5764608368417965999", i.shopIdCanal,
                "sem isto a coluna da V67 continua sem tela e a unidade nasce pela metade");
    }

    @Test
    void espacoNasPontasNaoVaiParaOBanco() {
        // O operador copia o numero do portal da 99 e vem espaço junto. O webhook compara texto:
        // " 576..." e "576..." seriam identificadores diferentes, e o pedido nao casaria.
        assertEquals("5764608368417965999", salvar(corpo("zira-x", "  5764608368417965999  ")).shopIdCanal);
    }

    @Test
    void campoEmBrancoVIRAnuloEmVezDeStringVazia() {
        assertNull(salvar(corpo("zira-x", "   ")).shopIdCanal,
                "string vazia no banco casaria com header vazio e acharia a loja errada");
    }

    @Test
    void oIDnumericoDEoutraLOJAehRECUSADO() {
        noBanco.add(deOutraLoja(18L, "zira-acaiteria", "5764615393256407102"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> salvar(corpo("zira-meu-endereco", "5764615393256407102")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("outra loja"), e.getReason());
    }

    /**
     * O CASO QUE A TRAVA ANTIGA DEIXAVA PASSAR: valor repetido ENTRE os dois campos.
     *
     * <p>O webhook procura primeiro por {@code merchantId}. Se o ID numerico desta loja fosse igual
     * ao App Shop ID da loja 18, o pedido desta loja acharia a 18 — e quem recebe pedido que nao e
     * dela nao tem como saber que esta recebendo.</p>
     */
    @Test
    void valorIGUALaoAPPSHOPIDdeOUTRAlojaTambemEhRECUSADO() {
        noBanco.add(deOutraLoja(18L, "zira-acaiteria", "5764615393256407102"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> salvar(corpo("zira-meu-endereco", "zira-acaiteria")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    /** E o mesmo vale para o campo antigo: App Shop ID igual ao ID numerico de outra loja. */
    @Test
    void oAPPSHOPIDigualAOidNUMERICOdeOUTRAlojaEhRECUSADO() {
        noBanco.add(deOutraLoja(18L, "zira-acaiteria", "5764615393256407102"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> salvar(corpo("5764615393256407102", null)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void oMESMOvalorNAmesmaLOJAnaoEhConflito() {
        // Salvar o cartao de novo sem mexer em nada nao pode virar erro.
        IntegracaoCanal minha = deOutraLoja(23L, "zira-meu-endereco", "5764608368417965999");
        noBanco.add(minha);
        when(repo.findByLojaIdAndCanal(23L, "NOVE_NOVE")).thenReturn(Optional.of(minha));

        IntegracaoCanal i = salvar(corpo("zira-meu-endereco", "5764608368417965999"));
        assertEquals("5764608368417965999", i.shopIdCanal);
    }

    @Test
    void corpoSEMoCAMPOnaoAPAGAoQueJAestaGravado() {
        // A tela do lojista nao desenha este campo. Se "ausente" fosse lido como "vazio", abrir o
        // cartao e salvar apagaria o roteamento do webhook da loja.
        IntegracaoCanal minha = deOutraLoja(23L, "zira-meu-endereco", "5764608368417965999");
        when(repo.findByLojaIdAndCanal(23L, "NOVE_NOVE")).thenReturn(Optional.of(minha));

        Map<String, Object> semOcampo = new HashMap<>();
        semOcampo.put("ativo", "true");
        IntegracaoCanal i = salvar(semOcampo);
        assertEquals("5764608368417965999", i.shopIdCanal, "o campo ausente tem de ser ignorado");
    }
}
