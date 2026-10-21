package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.Loja;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O balancete da rede numa consulta, e o cliente apagado que não deixa pedido apontando para o nada.
 *
 * <h2>Item 4.7 da varredura — as duas metades que eu consertei aqui</h2>
 * <p><b>O N+1.</b> O balancete chamava {@code somaReceita}, {@code contaPedidosValidos} e
 * {@code contaPedidosCancelados} dentro de um laço pelas lojas: numa rede de 30 lojas, 90 consultas
 * para desenhar uma tabela. Agora é uma, agrupada por loja.</p>
 *
 * <p><b>O pedido órfão.</b> {@code pedido.cliente_id} não tem chave estrangeira, então apagar o
 * cliente deixava os pedidos apontando para uma linha que não existe.</p>
 *
 * <p>Teste de serviço não prova fiação — já passou verde três vezes em 21/10/2026 com mutação dentro
 * de controller. Esta classe cobre a fiação dos dois: que a consulta nova é a que roda, e que o
 * desamarrar acontece antes do delete.</p>
 */
class BalanceteEmUmaConsultaTest {

    private PedidoRepository pedidos;
    private LojaRepository lojas;
    private AuthContext ctx;
    private RedeService rede;

    private static final Long LOJA_A = 1L;
    private static final Long LOJA_B = 2L;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        lojas = mock(LojaRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.isAdminBora()).thenReturn(true);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA_A, "ADMINISTRADOR_LOJA", "dono@bora.com"));

        when(lojas.findAll()).thenReturn(List.of(loja(LOJA_A, "Zirá"), loja(LOJA_B, "Sorveteria")));

        rede = new RedeService(mock(UsuarioLojaRepository.class), mock(UsuarioRepository.class), lojas,
                pedidos, mock(JwtService.class), ctx, mock(EmpresaService.class));
    }

    private Loja loja(Long id, String nome) {
        Loja l = new Loja();
        l.id = id;
        l.setNome(nome);
        l.setAtivo(true);
        return l;
    }

    private Object[] linha(Long lojaId, String receita, long validos, long cancelados) {
        return new Object[] {lojaId, new BigDecimal(receita), validos, cancelados};
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> balancete() {
        return (Map<String, Object>) (Map<?, ?>) rede.balancete(
                LocalDate.now().minusDays(7), LocalDate.now());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> porLoja() {
        return (List<Map<String, Object>>) balancete().get("lojas");
    }

    /**
     * A linha de uma loja, achada pelo NOME.
     *
     * <p>O balancete ordena por nome, nao por id -- "Sorveteria" vem antes de "Zira". A primeira
     * versao destes testes presumiu a ordem do id e falhou por isso. Procurar pelo nome deixa o teste
     * medir o que ele quer medir, e nao a ordenacao.</p>
     */
    private Map<String, Object> linhaDa(String nome) {
        return porLoja().stream().filter(m -> nome.equals(m.get("loja"))).findFirst()
                .orElseThrow(() -> new AssertionError("a loja \"" + nome + "\" nao apareceu no balancete"));
    }

    // =============================================================== uma consulta, nao tres por loja

    @Test
    void oBALANCETEfazUMAconsultaSO() {
        when(pedidos.balancetePorLoja(anyCollection(), any(), any()))
                .thenReturn(List.of(linha(LOJA_A, "1000.00", 10, 2), linha(LOJA_B, "500.00", 5, 1)));

        balancete();

        verify(pedidos, times(1)).balancetePorLoja(anyCollection(), any(), any());
        // E as tres antigas NAO podem mais rodar aqui: se voltarem, o N+1 volta com elas.
        verify(pedidos, never()).somaReceita(anyLong(), any(), any());
        verify(pedidos, never()).contaPedidosValidos(anyLong(), any(), any());
        verify(pedidos, never()).contaPedidosCancelados(anyLong(), any(), any());
    }

    @Test
    void aCONSULTApedeAStodasAslojasDEuMAvez() {
        when(pedidos.balancetePorLoja(anyCollection(), any(), any())).thenReturn(List.of());

        balancete();

        var cap = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(pedidos).balancetePorLoja(cap.capture(), any(), any());
        assertEquals(java.util.Set.of(LOJA_A, LOJA_B), new java.util.HashSet<>(cap.getValue()),
                "as duas lojas tem que ir na mesma consulta");
        assertEquals(2, cap.getValue().size(), "e nenhuma repetida");
    }

    // =============================================================== os numeros nao mudam

    @Test
    void osNUMEROSdeCADAlojaVEMdaCONSULTA() {
        when(pedidos.balancetePorLoja(anyCollection(), any(), any()))
                .thenReturn(List.of(linha(LOJA_A, "1000.00", 10, 2), linha(LOJA_B, "500.00", 5, 1)));

        var zira = linhaDa("Zirá");

        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) zira.get("faturamento")));
        assertEquals(10L, zira.get("pedidos"));
        assertEquals(2L, zira.get("cancelados"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) zira.get("ticketMedio")),
                "1000 / 10");
    }

    @Test
    void lojaQUEnaoVOLTAdaCONSULTAapareceCOMzero() {
        // Loja sem pedido na janela nao volta do group by. Antes, as tres consultas dela devolviam
        // zero e a loja aparecia na tabela. Se ela desaparecesse, o dono acharia que perdeu a loja.
        when(pedidos.balancetePorLoja(anyCollection(), any(), any()))
                .thenReturn(java.util.List.<Object[]>of(linha(LOJA_A, "1000.00", 10, 2)));

        assertEquals(2, porLoja().size(), "as duas lojas tem que aparecer");
        var sorveteria = linhaDa("Sorveteria");

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) sorveteria.get("faturamento")));
        assertEquals(0L, sorveteria.get("pedidos"));
        assertEquals(0L, sorveteria.get("cancelados"));
    }

    @Test
    void oTOTALeAREPRESENTATIVIDADEcontinuamFECHANDO() {
        when(pedidos.balancetePorLoja(anyCollection(), any(), any()))
                .thenReturn(List.of(linha(LOJA_A, "750.00", 10, 2), linha(LOJA_B, "250.00", 5, 1)));

        var tudo = balancete();
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) tudo.get("total");

        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) total.get("faturamento")));
        assertEquals(15L, total.get("pedidos"));
        assertEquals(0, new BigDecimal("75.00").compareTo(
                        (BigDecimal) linhaDa("Zirá").get("representatividade")),
                "750 de 1000");
    }

    @Test
    void redeSEMlojaNAOconsultaNADA() {
        // `in ()` e SQL invalido em algumas versoes do Hibernate. Nao chamar e mais seguro.
        when(lojas.findAll()).thenReturn(List.of());

        balancete();

        verify(pedidos, never()).balancetePorLoja(anyCollection(), any(), any());
    }

    // =============================================================== o cliente apagado (4.7 orfao)

    @Test
    void apagarCLIENTEdesamarraOSpedidosANTESdeAPAGAR() {
        ClienteRepository clientes = mock(ClienteRepository.class);
        AuthContext ctxCliente = mock(AuthContext.class);
        when(ctxCliente.lojaId()).thenReturn(LOJA_A);
        when(ctxCliente.atual()).thenReturn(new BoraPrincipal(7L, LOJA_A, "GERENTE", "g@zira.com"));
        Cliente c = new Cliente();
        c.id = 55L;
        c.lojaId = LOJA_A;
        c.nome = "Maria";
        when(clientes.findByIdAndLojaId(55L, LOJA_A)).thenReturn(Optional.of(c));

        new ClienteService(clientes, pedidos, ctxCliente).excluir(55L);

        // A ordem importa: apagar primeiro e desamarrar depois deixaria a janela em que o pedido
        // aponta para cliente que nao existe -- e e justamente dentro dessa janela que o webhook do
        // Asaas poderia chegar.
        InOrder ordem = inOrder(pedidos, clientes);
        ordem.verify(pedidos).desamarrarCliente(55L, LOJA_A);
        ordem.verify(clientes).delete(c);
    }

    @Test
    void oDESAMARRARusaALOJAdoCLIENTE_naoOutra() {
        ClienteRepository clientes = mock(ClienteRepository.class);
        AuthContext ctxCliente = mock(AuthContext.class);
        when(ctxCliente.lojaId()).thenReturn(LOJA_A);
        when(ctxCliente.atual()).thenReturn(new BoraPrincipal(7L, LOJA_A, "GERENTE", "g@zira.com"));
        Cliente c = new Cliente();
        c.id = 55L;
        c.lojaId = LOJA_A;
        when(clientes.findByIdAndLojaId(55L, LOJA_A)).thenReturn(Optional.of(c));

        new ClienteService(clientes, pedidos, ctxCliente).excluir(55L);

        verify(pedidos).desamarrarCliente(55L, LOJA_A);
        verify(pedidos, never()).desamarrarCliente(eq(55L), eq(LOJA_B));
    }

    @Test
    void clienteDEoutraLOJAnaoDESAMARRAnada() {
        ClienteRepository clientes = mock(ClienteRepository.class);
        AuthContext ctxCliente = mock(AuthContext.class);
        when(ctxCliente.lojaId()).thenReturn(LOJA_A);
        when(clientes.findByIdAndLojaId(999L, LOJA_A)).thenReturn(Optional.empty());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new ClienteService(clientes, pedidos, ctxCliente).excluir(999L));

        verify(pedidos, never()).desamarrarCliente(anyLong(), anyLong());
        verify(clientes, never()).delete(any(Cliente.class));
    }

    @Test
    void oAPAGARdoCLIENTEehTRANSACIONAL() {
        // Desamarrar e apagar tem que acontecer juntos. Sem transacao, um erro no meio deixaria o
        // cliente no ar com os pedidos dele ja desamarrados -- e nada diria o motivo.
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/ClienteService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        int i = fonte.indexOf("public void excluir(");
        assertTrue(i > 0);

        assertTrue(fonte.substring(Math.max(0, i - 300), i).contains("Transactional"));
    }

    @Test
    void oHISTORICOnaoEHapagadoJUNTO() {
        // A venda aconteceu e entrou no faturamento. So o ponteiro sai.
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/repository/PedidoRepository.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        int i = fonte.indexOf("int desamarrarCliente(");
        assertTrue(i > 0, "nao achei o desamarrarCliente");
        String consulta = fonte.substring(fonte.lastIndexOf("@Query", i), i);

        assertTrue(consulta.contains("set p.clienteId = null"), "tem que ZERAR o vinculo");
        assertFalse(consulta.toLowerCase().contains("delete"), "e nunca apagar o pedido");
    }
}
