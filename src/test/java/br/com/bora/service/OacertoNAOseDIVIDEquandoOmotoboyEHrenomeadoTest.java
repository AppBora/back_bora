package br.com.bora.service;

import br.com.bora.dto.AcertoLinha;
import br.com.bora.dto.FazerAcertoRequest;
import br.com.bora.entity.AcertoEntregador;
import br.com.bora.entity.Entregador;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.AcertoEntregadorRepository;
import br.com.bora.repository.EntregadorRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ITEM 3 DA SECAO 4 DA VARREDURA (confirmado em 19/11/2026, consertado em 20/11).
 *
 * <h2>E dinheiro de motoboy</h2>
 * <p>{@code pedido.entregador} era TEXTO livre, e o acerto agrupava por {@code lower(trim(...))}.
 * Renomear o motoboy no cadastro no meio do periodo -- corrigir "Jose" para "Jose Carlos", tirar um
 * apelido, arrumar um acento -- dividia as corridas dele em DUAS linhas na tela de acerto: as de
 * antes com o nome velho, as de depois com o novo. <b>Quem fecha o acerto paga uma e nao a outra</b>,
 * e a que fica para tras nao aparece como pendencia em lugar nenhum: ela virou "outro entregador".</p>
 *
 * <p>O cadastro de motoboy JA EXISTIA (tabela {@code entregador}, com tela, desde a V9). O pedido e
 * que nunca o apontou. A V80 criou {@code pedido.entregador_id}, fez o back-fill e o acerto passou a
 * agrupar por ele.</p>
 *
 * <p><b>O que este arquivo NAO consegue provar:</b> que a entrega com cadastro nao aparece TAMBEM na
 * linha legada, somando o mesmo dinheiro duas vezes. Isso esta no filtro {@code entregadorId is null}
 * das consultas, e quem responde por ele e o banco -- esta em
 * {@code OacertoNAOcontaDUASvezesOmesmoMOTOBOYIT}.</p>
 */
class OacertoNAOseDIVIDEquandoOmotoboyEHrenomeadoTest {

    private static final Long LOJA = 18L;
    private static final Long MOTOBOY = 77L;

    private PedidoRepository pedidos;
    private AcertoEntregadorRepository acertos;
    private EntregadorRepository entregadores;
    private AcertoEntregadorService service;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        acertos = mock(AcertoEntregadorRepository.class);
        entregadores = mock(EntregadorRepository.class);
        when(pedidos.previaAcerto(anyLong(), any(), any())).thenReturn(List.of());
        when(pedidos.previaAcertoPorCadastro(anyLong(), any(), any())).thenReturn(List.of());
        when(pedidos.entregasSemMotoboy(anyLong(), any(), any())).thenReturn(List.of());
        when(acertos.save(any(AcertoEntregador.class))).thenAnswer(i -> {
            AcertoEntregador a = i.getArgument(0);
            if (a.id == null) a.id = 4242L;
            return a;
        });

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "eu@loja.com"), null, List.of()));
        service = new AcertoEntregadorService(pedidos, acertos, new AuthContext(), entregadores);
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    /** O que a consulta por cadastro devolve: id, nome gravado no pedido, qtd, taxas, dinheiro, total. */
    private Object[] linhaPorCadastro(Long id, String nomeNoPedido, long qtd, String taxas,
                                      String dinheiro, String total) {
        return new Object[]{id, nomeNoPedido, qtd, new BigDecimal(taxas), new BigDecimal(dinheiro),
                new BigDecimal(total)};
    }

    private Entregador cadastro(String nome) {
        Entregador e = new Entregador();
        e.id = MOTOBOY;
        e.lojaId = LOJA;
        e.nome = nome;
        return e;
    }

    private List<AcertoLinha> previa() {
        return service.previa(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
    }

    // =================================================================== a premissa

    // SEM ISTO, todo teste abaixo passaria com uma previa que devolve lista vazia -- e a tela de
    // acerto ficaria em branco, com a suite verde, sem ninguem para pagar.
    @Test
    void premissa_aLINHAporCADASTROapareceNAprevia() {
        when(pedidos.previaAcertoPorCadastro(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(linhaPorCadastro(MOTOBOY, "Jose", 4, "20.00", "60.00", "150.00")));
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA)).thenReturn(Optional.of(cadastro("Jose")));

        List<AcertoLinha> linhas = previa();

        assertEquals(1, linhas.size());
        assertEquals("Jose", linhas.get(0).entregador());
        assertEquals(4, linhas.get(0).qtdeEntregas());
        assertEquals(0, new BigDecimal("20.00").compareTo(linhas.get(0).valorTaxas()));
        assertEquals(0, new BigDecimal("90.00").compareTo(linhas.get(0).valorOutras()),
                "outras formas = total - dinheiro, e e o que o lojista confere contra a gaveta");
        assertEquals(MOTOBOY, linhas.get(0).entregadorId(), "a linha tem de levar o id de volta");
    }

    // =================================================================== o conserto

    @Test
    void oMOTOBOYrenomeadoAPARECEemUMAlinhaSOhComOnomeNOVO() {
        // O cenario exato do item: as entregas do periodo foram gravadas com "Jose" (o nome de
        // entao), e o cadastro hoje diz "Jose Carlos". Agrupado por id, e UMA linha; e o nome que o
        // lojista le e o de AGORA -- se fosse o do pedido, ele veria um nome que nao existe mais na
        // lista de motoboys e nao saberia que e a mesma pessoa.
        when(pedidos.previaAcertoPorCadastro(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(linhaPorCadastro(MOTOBOY, "Jose", 7, "35.00", "0.00", "280.00")));
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA))
                .thenReturn(Optional.of(cadastro("Jose Carlos")));

        List<AcertoLinha> linhas = previa();

        assertEquals(1, linhas.size(), "o renomeado voltou a aparecer em duas linhas");
        assertEquals("Jose Carlos", linhas.get(0).entregador(),
                "a previa mostrou o nome velho, gravado no pedido, em vez do nome do cadastro");
        assertEquals(7, linhas.get(0).qtdeEntregas(), "as 7 corridas tem de estar na mesma linha");
    }

    @Test
    void semCADASTROnoBANCOaPREVIAcaiNOnomeGRAVADOnoPEDIDO() {
        // A FK da V80 e ON DELETE SET NULL, mas uma previa aberta em outra aba pode ter o id em
        // maos. Cair no nome gravado e melhor do que mostrar linha sem nome.
        when(pedidos.previaAcertoPorCadastro(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(linhaPorCadastro(MOTOBOY, "Jose", 2, "10.00", "0.00", "50.00")));
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA)).thenReturn(Optional.empty());

        assertEquals("Jose", previa().get(0).entregador());
    }

    @Test
    void aLINHAlegadaCONTINUAaparecendo_eSEMid() {
        // Entrega gravada antes da V80, ou nome que nao casou com um cadastro unico: o texto continua
        // sendo a chave, como sempre foi. O item nao podia ser consertado tirando essas entregas da
        // tela -- elas sao dinheiro que alguem tem de receber.
        when(pedidos.previaAcerto(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"Antigo", 3L, new BigDecimal("15.00"),
                        new BigDecimal("0.00"), new BigDecimal("90.00")}));

        List<AcertoLinha> linhas = previa();

        assertEquals(1, linhas.size());
        assertEquals("Antigo", linhas.get(0).entregador());
        assertNull(linhas.get(0).entregadorId(), "linha legada nao tem cadastro, e a tela fecha por nome");
    }

    @Test
    void asDUASlinhasVEMjuntasEordenadasPORnome() {
        // Com as listas concatenadas sem ordenar, a tela mostrava os motoboys com cadastro em cima e
        // os legados embaixo, sem explicacao nenhuma para o lojista.
        when(pedidos.previaAcertoPorCadastro(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(linhaPorCadastro(MOTOBOY, "Zeca", 1, "5.00", "0.00", "20.00")));
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA)).thenReturn(Optional.of(cadastro("Zeca")));
        when(pedidos.previaAcerto(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"Ana", 1L, new BigDecimal("5.00"),
                        new BigDecimal("0.00"), new BigDecimal("20.00")}));

        List<AcertoLinha> linhas = previa();

        assertEquals(List.of("Ana", "Zeca"), linhas.stream().map(AcertoLinha::entregador).toList());
    }

    @Test
    void aLINHAsemMOTOBOYficaPORultimo_eSEMid() {
        when(pedidos.previaAcertoPorCadastro(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(linhaPorCadastro(MOTOBOY, "Zeca", 1, "5.00", "0.00", "20.00")));
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA)).thenReturn(Optional.of(cadastro("Zeca")));
        when(pedidos.entregasSemMotoboy(eq(LOJA), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, new BigDecimal("8.00"), new BigDecimal("70.00")}));

        List<AcertoLinha> linhas = previa();

        assertEquals(2, linhas.size());
        assertEquals(AcertoEntregadorService.SEM_MOTOBOY, linhas.get(1).entregador(),
                "a linha de aviso tem de ficar no fim, depois dos motoboys");
        assertNull(linhas.get(1).entregadorId());
    }

    // =================================================================== fechar o acerto

    @Test
    void fecharPELOidLEvaASentregasDOcadastro_eGRAVAoNOMEdeAGORA() {
        Pedido p = entrega("30.00", "5.00", "DINHEIRO");
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA))
                .thenReturn(Optional.of(cadastro("Jose Carlos")));
        when(pedidos.entregasParaAcertoPorCadastro(eq(LOJA), eq(MOTOBOY), any(), any()))
                .thenReturn(List.of(p));
        when(pedidos.amarrarAoAcerto(anyLong(), any())).thenReturn(1);

        AcertoEntregador a = service.fazer(new FazerAcertoRequest("Jose", MOTOBOY,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null, null));

        assertEquals(MOTOBOY, a.entregadorId);
        assertEquals("Jose Carlos", a.entregador,
                "o recibo saiu com o nome que a tela mandou, nao com o do cadastro");
        assertEquals(1, a.qtdeEntregas);
        // E NAO foi pela consulta de texto: ela traria as entregas de quem nao tem cadastro.
        verify(pedidos, never()).entregasParaAcerto(anyLong(), any(), any(), any());
    }

    @Test
    void oIDdeOUTRAlojaEHrecusado() {
        // O id vem do corpo da requisicao. Sem esta guarda, mandar o id de um motoboy de outra loja
        // somaria as entregas de outra pessoa -- e `entregasParaAcertoPorCadastro` filtra por loja,
        // entao o resultado seria um acerto vazio com o nome de alguem de fora no recibo.
        when(entregadores.findByIdAndLojaId(MOTOBOY, LOJA)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.fazer(new FazerAcertoRequest("Jose", MOTOBOY,
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null, null)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(acertos, never()).save(any(AcertoEntregador.class));
    }

    @Test
    void fecharSEMidCONTINUAfuncionandoPELOtexto() {
        Pedido p = entrega("40.00", "6.00", "PIX");
        when(pedidos.entregasParaAcerto(eq(LOJA), eq("Antigo"), any(), any())).thenReturn(List.of(p));
        when(pedidos.amarrarAoAcerto(anyLong(), any())).thenReturn(1);

        AcertoEntregador a = service.fazer(new FazerAcertoRequest("Antigo", null,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null, null));

        assertEquals("Antigo", a.entregador);
        assertNull(a.entregadorId);
        verify(pedidos, never()).entregasParaAcertoPorCadastro(anyLong(), any(), any(), any());
    }

    @Test
    void semIDeSEMnomeEHrecusado() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.fazer(new FazerAcertoRequest("  ", null,
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    private Pedido entrega(String total, String taxa, String forma) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.status = StatusPedido.ENTREGUE;
        p.valorTotal = new BigDecimal(total);
        p.taxaEntrega = new BigDecimal(taxa);
        p.formaPagamento = forma;
        p.entregadorId = MOTOBOY;
        p.entregador = "Jose";
        p.entregueEm = OffsetDateTime.now();
        return p;
    }
}
