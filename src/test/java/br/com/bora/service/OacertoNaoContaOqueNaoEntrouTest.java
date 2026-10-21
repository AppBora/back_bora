package br.com.bora.service;

import br.com.bora.dto.AcertoLinha;
import br.com.bora.repository.AcertoEntregadorRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O acerto do entregador não conta dinheiro que não entrou, e não esconde entrega sem motoboy.
 *
 * <h2>Item 6.3: o PIX que ninguém pagou entrava no acerto</h2>
 * <p>As duas consultas do acerto filtravam status ENTREGUE e {@code acerto_id} nulo — e nada sobre o
 * pagamento. Um PIX gerado, nunca pago, e marcado como entregue entrava como venda normal: o
 * {@code valorTotal} dele ia para a coluna "outras formas", <b>como se o dinheiro tivesse entrado por
 * cartão</b>. A conta com o motoboy fechava errada, e para mais.</p>
 *
 * <p>Agora vale a mesma regra do faturamento ("PIX que ninguém pagou não é venda"): o pedido fica
 * fora até o pagamento ser resolvido, e entra no acerto seguinte quando for. E desde o item 6.1 o
 * lojista é avisado na observação do pedido de que saiu comida sem pagamento.</p>
 *
 * <h2>Item 6.4: a entrega sem motoboy desaparecia</h2>
 * <p>O acerto exige {@code entregador is not null}. Uma entrega concluída sem ninguém escolhido no
 * card <b>nunca aparecia em acerto nenhum</b> — nem errada, nem certa: desaparecia, com a taxa e o
 * valor dela. Agora ela entra como uma <b>linha própria</b> na prévia, e quem faz o acerto vê o
 * dinheiro que está fora da conta.</p>
 */
class OacertoNaoContaOqueNaoEntrouTest {

    private PedidoRepository pedidos;
    private AcertoEntregadorService service;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        service = new AcertoEntregadorService(pedidos, mock(AcertoEntregadorRepository.class), ctx);
        when(pedidos.previaAcerto(eq(LOJA), any(), any())).thenReturn(List.of());
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any())).thenReturn(List.of());
    }

    private List<AcertoLinha> previa() {
        return service.previa(LocalDate.now().minusDays(7), LocalDate.now());
    }

    // =============================================================== 6.4 a linha que faltava

    @Test
    void aENTREGAsemMOTOBOYapareceNAprevia() {
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {3L, new BigDecimal("15.00"), new BigDecimal("120.00")}));

        var linhas = previa();

        assertEquals(1, linhas.size());
        assertEquals(AcertoEntregadorService.SEM_MOTOBOY, linhas.get(0).entregador());
        assertEquals(3L, linhas.get(0).qtdeEntregas());
        assertEquals(0, new BigDecimal("15.00").compareTo(linhas.get(0).valorTaxas()));
    }

    @Test
    void aLINHAsemMOTOBOYnaoINVENTAdinheiroEMmao() {
        // Ninguem recebeu esse dinheiro em nome da loja, porque nao se sabe quem entregou. Jogar no
        // "dinheiro" faria a loja cobrar de um motoboy que ela nao sabe qual e.
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {2L, new BigDecimal("10.00"), new BigDecimal("80.00")}));

        var linha = previa().get(0);

        assertEquals(0, BigDecimal.ZERO.compareTo(linha.valorDinheiro()));
        assertEquals(0, new BigDecimal("80.00").compareTo(linha.valorOutras()));
    }

    @Test
    void SEMentregaORFA_aLINHAnaoAPARECE() {
        // Linha de zero seria ruido em toda loja que escolhe o motoboy direito.
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {0L, BigDecimal.ZERO, BigDecimal.ZERO}));

        assertTrue(previa().isEmpty());
    }

    @Test
    void aLINHAsemMOTOBOYvemDEPOISdosENTREGADORES() {
        // Ela e um aviso, nao um entregador: no fim da tabela, onde se olha o total.
        when(pedidos.previaAcerto(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {"Joao", 5L, new BigDecimal("25.00"),
                        new BigDecimal("200.00"), new BigDecimal("300.00")}));
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {1L, new BigDecimal("5.00"), new BigDecimal("40.00")}));

        var linhas = previa();

        assertEquals(2, linhas.size());
        assertEquals("Joao", linhas.get(0).entregador());
        assertEquals(AcertoEntregadorService.SEM_MOTOBOY, linhas.get(1).entregador());
    }

    @Test
    void oNOMEdaLINHAnaoEHnomeDEentregador() {
        // E de proposito: o `fazer` procura as entregas por lower(trim(entregador)), e nenhuma entrega
        // tem este texto como nome -- entao tentar fechar o acerto desta linha e recusado sem precisar
        // de guarda nova. Se alguem trocar por um nome plausivel, isso deixa de valer.
        assertTrue(AcertoEntregadorService.SEM_MOTOBOY.startsWith("("),
                "o nome tem que ser visivelmente um rotulo, nao um nome de pessoa");
        assertTrue(AcertoEntregadorService.SEM_MOTOBOY.contains("sem motoboy"));
    }

    // =============================================================== 6.3 o lacre das consultas

    @Test
    void asDUASconsultasDOacertoEXCLUEMpagamentoPENDENTE() throws IOException {
        // Isto esta no SQL: nenhum teste de servico com mock alcanca. Quem prova de verdade e o banco,
        // e os *IT nao rodam no mvn test nem no build do deploy -- entao o lacre de fonte roda no
        // caminho que o deploy percorre, que e o que faltou nas tres vezes em que uma mutacao de SQL
        // passou verde.
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/repository/PedidoRepository.java"),
                StandardCharsets.UTF_8);

        for (String metodo : List.of("previaAcerto", "entregasParaAcerto")) {
            int i = fonte.indexOf(" " + metodo + "(");
            assertTrue(i > 0, "nao achei o " + metodo);
            String consulta = fonte.substring(fonte.lastIndexOf("@Query", i), i);
            assertTrue(consulta.contains("p.aguardandoPagamento is null or p.aguardandoPagamento = false"),
                    "o " + metodo + " tem que excluir PIX pendente, senao o acerto conta dinheiro que "
                            + "nao entrou. A consulta foi: " + consulta);
        }
    }

    @Test
    void aCONSULTAdasORFASpedeENTREGADORvazioEpagamentoRESOLVIDO() throws IOException {
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/repository/PedidoRepository.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf(" entregasSemMotoboy(");
        assertTrue(i > 0);
        String consulta = fonte.substring(fonte.lastIndexOf("@Query", i), i);

        assertTrue(consulta.contains("p.entregador is null or trim(p.entregador) = ''"),
                "tem que ser o COMPLEMENTO da consulta por entregador, senao entrega cai nas duas ou "
                        + "em nenhuma. Foi: " + consulta);
        assertTrue(consulta.contains("p.acertoId is null"), "e so o que ainda nao foi acertado");
        assertTrue(consulta.contains("p.aguardandoPagamento is null"),
                "mesma regra do 6.3: PIX nao pago nao entra em conta nenhuma");
    }

    @Test
    void aPREVIAcontinuaSOMANDOoENTREGADORnormal() {
        // O conserto nao pode mexer no caminho que ja funcionava.
        when(pedidos.previaAcerto(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {"Joao", 5L, new BigDecimal("25.00"),
                        new BigDecimal("200.00"), new BigDecimal("300.00")}));

        var linha = previa().get(0);

        assertEquals("Joao", linha.entregador());
        assertEquals(5L, linha.qtdeEntregas());
        assertEquals(0, new BigDecimal("25.00").compareTo(linha.valorTaxas()));
        assertEquals(0, new BigDecimal("200.00").compareTo(linha.valorDinheiro()));
        assertEquals(0, new BigDecimal("100.00").compareTo(linha.valorOutras()), "300 - 200");
    }
}
