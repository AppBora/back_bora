package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Integração morta não pode falar em silêncio.
 *
 * <h2>O defeito — item 3.11 da varredura de 18/11/2026</h2>
 * <p>O rebaixamento de CONECTADO para ERRO depois de
 * {@link IntegracaoCanal#FALHAS_PARA_REBAIXAR} falhas seguidas já funcionava. <b>O que faltava era
 * alguém olhar.</b> O ERRO só aparecia abrindo a tela de Integrações <i>daquela</i> loja, uma por
 * uma — e a plataforma tem 8 lojas.</p>
 *
 * <p>Medido em 18/11: o Open Delivery da loja 1 respondia HTTP <b>403 "User is forbidden"</b> cinco
 * vezes por hora, a cada 30 s. Era a loja de teste — ruído, não cliente. Mas o <b>mesmo padrão</b>,
 * em 13/11, era o iFood da Pizzaria dizendo CONECTADO com token vencido há <b>18 dias</b>, sem
 * nunca ter buscado um pedido. Esse era cliente pagante, com pedido perdido todo dia.</p>
 *
 * <p>É a terceira vez que "integração morta em silêncio" aparece numa varredura. O conserto não é
 * outro log: é a informação chegar onde o dono já olha.</p>
 */
class AintegracaoMORTAapareceNOpainelTest {

    private final IntegracaoCanalRepository repo = mock(IntegracaoCanalRepository.class);

    /** O service só precisa do repositório para este caminho; o resto fica nulo de propósito. */
    private IntegracaoService servico() {
        return new IntegracaoService(repo, null, null, java.util.List.of());
    }

    private void oBancoDevolve(Object[]... linhas) {
        when(repo.emErro(anyInt())).thenReturn(List.of(linhas));
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: sem integração morta, a lista é VAZIA — e isso é resposta, não ausência de
     * resposta. A tela distingue as duas coisas com palavra; aqui eu garanto que o dado permite.
     */
    @Test
    void aPREMISSAsemINTEGRACAOmortaALISTAehVAZIA() {
        oBancoDevolve();
        assertTrue(servico().mortasNaPlataforma().isEmpty());
    }

    // ----------------------------------------------------------------- o que importa

    /** O caso da loja 1 medido em produção, e o da Pizzaria medido em 13/11. */
    @Test
    void asDUASmortasAPARECEMcomOqueOdonoPRECISAver() {
        OffsetDateTime agora = OffsetDateTime.now();
        oBancoDevolve(
                new Object[]{1L, "OPENDELIVERY", "ERRO", 5, agora.minusMinutes(1),
                        "403 User is forbidden", true},
                new Object[]{19L, "IFOOD", "ERRO", 44, agora.minusDays(18),
                        "401 token expirado", true});

        List<Map<String, Object>> mortas = servico().mortasNaPlataforma();

        assertEquals(2, mortas.size());
        assertEquals(1L, mortas.get(0).get("lojaId"));
        assertEquals("OPENDELIVERY", mortas.get(0).get("canal"));
        assertEquals(5, mortas.get(0).get("falhasSeguidas"));
        assertEquals("403 User is forbidden", mortas.get(0).get("ultimoErro"));
        assertNotNull(mortas.get(0).get("ultimoPollingEm"),
                "sem a hora da ultima busca nao da para saber se e de agora ou de 18 dias atras");
        assertEquals(19L, mortas.get(1).get("lojaId"));
        assertEquals("IFOOD", mortas.get(1).get("canal"));
    }

    /** `ultimoPollingEm` nulo é o caso pior: nunca buscou pedido nenhum. Não pode estourar. */
    @Test
    void aQUEnuncaBUSCOUpedidoNAOderrubaOpainel() {
        oBancoDevolve(new Object[]{23L, "IFOOD", "ERRO", 3, null, "credencial recusada", true});

        var m = servico().mortasNaPlataforma().get(0);

        assertNull(m.get("ultimoPollingEm"));
        assertEquals(23L, m.get("lojaId"));
    }

    // ----------------------------------------------------------------- o segredo

    /**
     * NENHUM SEGREDO SAI NA RESPOSTA.
     *
     * <p>{@link IntegracaoCanal} carrega {@code clientSecret}, {@code accessToken},
     * {@code refreshToken}, {@code webhookToken} e {@code codeVerifier}. Devolver a entidade neste
     * endpoint vazaria credencial de cliente para a resposta HTTP. Este teste trava os <b>nomes das
     * chaves</b> do mapa: campo novo só entra se alguém o escrever aqui também.</p>
     */
    @Test
    void oMAPAnaoCARREGAnenhumSEGREDO() {
        oBancoDevolve(new Object[]{1L, "OPENDELIVERY", "ERRO", 5, OffsetDateTime.now(), "403", true});

        var chaves = servico().mortasNaPlataforma().get(0).keySet();

        assertEquals(List.of("lojaId", "canal", "status", "falhasSeguidas", "ultimoPollingEm",
                        "ultimoErro", "ativo"),
                List.copyOf(chaves),
                "as chaves da resposta mudaram. Se um campo novo entrou, confira que ele NAO e "
                        + "credencial: clientSecret, accessToken, refreshToken, webhookToken e "
                        + "codeVerifier nunca podem sair daqui.");
        for (String proibida : new String[]{"clientSecret", "accessToken", "refreshToken",
                "webhookToken", "codeVerifier", "clientId"}) {
            assertFalse(chaves.contains(proibida), "a resposta carrega " + proibida);
        }
    }

    /** E a consulta do repositório também não pode selecionar segredo. */
    @Test
    void aCONSULTAdoREPOSITORIOnaoSELECIONAsegredo() throws Exception {
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/repository/IntegracaoCanalRepository.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf("java.util.List<Object[]> emErro(");
        assertTrue(i > 0, "nao achei o metodo emErro");
        String consulta = fonte.substring(Math.max(0, i - 700), i);

        for (String proibido : new String[]{"clientSecret", "accessToken", "refreshToken",
                "webhookToken", "codeVerifier"}) {
            assertFalse(consulta.contains("i." + proibido),
                    "a consulta emErro seleciona " + proibido + " — isso vai para a resposta HTTP");
        }
        assertTrue(consulta.contains("i.lojaId") && consulta.contains("i.canal"),
                "a consulta tem de trazer ao menos loja e canal, senao o painel nao diz onde olhar");
    }

    // ----------------------------------------------------------------- o limite

    /** O limite vem da constante, não de um número solto. */
    @Test
    void oLIMITEvemDAconstante() {
        oBancoDevolve();
        servico().mortasNaPlataforma();
        verify(repo).emErro(IntegracaoCanal.FALHAS_PARA_REBAIXAR);
    }
}
