package br.com.bora.service;

import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Saldo que mudou no meio do caminho recusa o pedido, em vez de dar desconto que ninguém pagou.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Achado por mutação em 21/10/2026: apagar o {@code if (mexeu == 0 && usado.signum() > 0)} do
 * {@code registrar} passou com a suíte inteira verde. Quem provava essa recusa era só o
 * {@code SaldoDeCashbackEhAtomicoIT} — e os {@code *IT} são excluídos do {@code mvn test}
 * (pom.xml:34) <b>e do build da imagem do deploy</b>. Ou seja: a proteção do item 1.5 podia ser
 * removida e um deploy sairia verde.</p>
 *
 * <p>O efeito de perder a recusa: o desconto já saiu do total que o cliente vai pagar quando essa
 * linha roda. Se outro pedido gastou o saldo primeiro, o banco se recusa a debitar (é o
 * {@code coalesce(cashback, 0) >= :usado} da consulta) — e sem o estouro o pedido é gravado do mesmo
 * jeito, <b>com um desconto que ninguém pagou</b>. É o defeito que o estoque já teve, agora no
 * dinheiro.</p>
 *
 * <p>A última parte da classe lê o fonte das consultas de saldo. Isso não prova comportamento nenhum —
 * quem prova é o Postgres, no {@code SaldoDeCashbackEhAtomicoIT}. Mas roda no caminho que o deploy
 * percorre, e é o que faltava nas outras três vezes que uma mutação de SQL passou verde.</p>
 */
class SaldoQueMudouRecusaOpedidoTest {

    private ClienteRepository clientes;
    private FidelidadeService fidelidade;

    private static final Long LOJA = 18L;
    private static final Long CLIENTE = 77L;

    @BeforeEach
    void montar() {
        clientes = mock(ClienteRepository.class);
        ConfiguracaoLojaRepository configs = mock(ConfiguracaoLojaRepository.class);
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.empty());
        fidelidade = new FidelidadeService(clientes, configs);
    }

    /** O banco recusou: ninguém mexeu em linha nenhuma. */
    private void bancoRecusa() {
        when(clientes.registrarVenda(eq(CLIENTE), eq(LOJA), any(), any(), any(), any())).thenReturn(0);
        when(clientes.debitarSeTiver(eq(CLIENTE), eq(LOJA), any())).thenReturn(0);
    }

    private void bancoAceita() {
        when(clientes.registrarVenda(eq(CLIENTE), eq(LOJA), any(), any(), any(), any())).thenReturn(1);
        when(clientes.debitarSeTiver(eq(CLIENTE), eq(LOJA), any())).thenReturn(1);
    }

    // =============================================================== a recusa

    @Test
    void saldoQueNAOcobreOresgate_RECUSAoPedido() {
        bancoRecusa();

        var erro = assertThrows(ResponseStatusException.class, () -> fidelidade.registrar(
                LOJA, CLIENTE, new BigDecimal("40.00"), new BigDecimal("5.00")));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
    }

    @Test
    void aMENSAGEMdizOqueOclienteTemQueFAZER() {
        // Quem le e o cliente no celular, no meio do checkout. "Conflito 409" nao serve.
        bancoRecusa();

        var erro = assertThrows(ResponseStatusException.class, () -> fidelidade.registrar(
                LOJA, CLIENTE, new BigDecimal("40.00"), new BigDecimal("5.00")));

        assertTrue(erro.getReason().contains("saldo de cashback mudou"), "foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("tente de novo"), "e diz o que fazer");
    }

    @Test
    void consumirSALDOqueNAOtem_tambemRECUSA() {
        // O outro caminho que gasta saldo: o reconsumo do PIX pago depois do cancelamento.
        bancoRecusa();

        var erro = assertThrows(ResponseStatusException.class,
                () -> fidelidade.consumir(LOJA, CLIENTE, new BigDecimal("5.00")));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
    }

    // =============================================================== e onde NAO se recusa

    @Test
    void SEMresgate_nuncaRECUSA_mesmoObancoDizendoZERO() {
        // O caso dos webhooks de pagamento e do pedido de marketplace: nao ha resgate em disputa, e
        // zero linha mexida pode ser so cliente apagado. Estourar aqui derrubaria o webhook do Asaas,
        // que reenviaria o aviso para sempre.
        bancoRecusa();

        assertDoesNotThrow(() -> fidelidade.registrar(
                LOJA, CLIENTE, new BigDecimal("40.00"), BigDecimal.ZERO));
    }

    @Test
    void saldoQUEcobre_deixaOpedidoPassar() {
        // O conserto nao pode virar "nenhum pedido com cashback passa".
        bancoAceita();

        assertDoesNotThrow(() -> fidelidade.registrar(
                LOJA, CLIENTE, new BigDecimal("40.00"), new BigDecimal("5.00")));

        verify(clientes).registrarVenda(eq(CLIENTE), eq(LOJA), eq(new BigDecimal("40.00")),
                eq(new BigDecimal("5.00")), any(), any());
    }

    @Test
    void DEVOLVERcashbackNUNCArecusa() {
        // Devolver e o inverso: a venda nao aconteceu. Recusar prenderia o dinheiro do cliente.
        fidelidade.devolver(LOJA, CLIENTE, new BigDecimal("5.00"));

        verify(clientes).creditar(CLIENTE, LOJA, new BigDecimal("5.00"));
        verify(clientes, never()).debitarSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void ESTORNARnuncaESTOURA_mesmoObancoRecusando() {
        // O estorno roda dentro do cancelamento. Se estourasse, o lojista nao conseguiria cancelar
        // pedido nenhum -- e o pedido ficaria preso para sempre.
        when(clientes.desfazerVenda(anyLong(), anyLong(), any(), any(), any(), anyInt())).thenReturn(0);

        assertDoesNotThrow(() -> fidelidade.estornar(
                LOJA, CLIENTE, new BigDecimal("40.00"), new BigDecimal("5.00"), true));
    }

    @Test
    void clienteNULOnaoEstouraEnaoChamaObanco() {
        assertDoesNotThrow(() -> fidelidade.registrar(LOJA, null, new BigDecimal("40.00"), null));

        verify(clientes, never()).registrarVenda(any(), any(), any(), any(), any(), any());
    }

    // =============================================================== o lacre das consultas de saldo

    private static final Path REPO =
            Path.of("src/main/java/br/com/bora/repository/ClienteRepository.java");

    @Test
    void asDUASconsultasQueGASTAMsaldoRECUSAMgastarOqueNAOtem() throws IOException {
        // A clausula que faz o banco recusar. Sem ela, duas requisicoes simultaneas gastam o MESMO
        // saldo duas vezes -- e o estouro testado acima nunca dispara, porque o banco sempre mexe em
        // uma linha.
        String fonte = fonte();

        assertEquals(2, contar(fonte, "coalesce(c.cashback, 0) >= :"),
                "esperava a clausula nas DUAS consultas que gastam saldo (registrarVenda e "
                        + "debitarSeTiver). Sem ela o saldo pode ser gasto duas vezes pelo mesmo "
                        + "cliente, e nenhum teste de servico acusa.");

        // A comparacao tem que terminar NO parametro. A primeira versao deste teste so procurava a
        // clausula, e uma mutacao passou verde trocando `>= :usado` por `>= :usado - 9999` -- a
        // clausula continuava escrita e nunca recusava nada. Teste de texto nao prova comportamento;
        // o minimo que ele pode fazer e cobrar a forma exata.
        String marca = "coalesce(c.cashback, 0) >= :";
        var parametros = new java.util.ArrayList<String>();
        for (int i = fonte.indexOf(marca); i >= 0; i = fonte.indexOf(marca, i + 1)) {
            String depois = fonte.substring(i + marca.length());
            for (String nome : java.util.List.of("usado", "valor")) {
                if (!depois.startsWith(nome)) continue;
                // o que vem depois do parametro tem que ser o fim do bloco de texto, nada mais
                if (depois.substring(nome.length()).stripLeading().startsWith("\"\"\"")) {
                    parametros.add(nome);
                }
            }
        }

        assertEquals(java.util.List.of("usado", "valor"), parametros,
                "as duas clausulas tem que comparar o saldo DIRETO com o parametro, sem conta "
                        + "nenhuma depois dele -- `>= :usado - 9999` deixa a clausula escrita e "
                        + "inutil. Achei: " + parametros);
    }

    @Test
    void oDESFAZERvendaNUNCAdeixaColunaNEGATIVA() throws IOException {
        // O estorno soma de volta o resgate e tira o cashback que a venda gerou. Se a loja mudou o
        // percentual entre a venda e o cancelamento, a conta pode dar negativo -- e saldo negativo
        // virava desconto impossivel na proxima compra. O `greatest(0, ...)` corta no zero, e ele vive
        // no SQL: nenhum teste de servico o alcanca.
        String fonte = fonte();

        assertEquals(3, contar(fonte, "greatest(0,"),
                "esperava o greatest nas TRES colunas que o desfazerVenda diminui (cashback, "
                        + "total_gasto, qtd_pedidos). Sem ele, cancelar pedido pode deixar numero "
                        + "negativo no cadastro do cliente.");
    }

    @Test
    void oCREDITARcontinuaSEMclausula_porqueDEVOLVERnaoSErecusa() throws IOException {
        // O contrario dos dois de cima, e de proposito: se alguem "padronizar" a clausula nesta
        // consulta, devolver cashback de venda cancelada passa a poder ser recusado.
        String fonte = fonte();
        int i = fonte.indexOf("int creditar(");
        assertTrue(i > 0, "nao achei o creditar");
        String consulta = fonte.substring(fonte.lastIndexOf("@org.springframework.data.jpa.repository.Query", i), i);

        assertFalse(consulta.contains(">="),
                "a consulta que CREDITA nao pode ter condicao de saldo: devolver dinheiro do cliente "
                        + "nao e algo que o banco possa recusar. A consulta foi: " + consulta);
    }

    private String fonte() throws IOException {
        assertTrue(Files.isRegularFile(REPO), "nao achei " + REPO.toAbsolutePath());
        return Files.readString(REPO, StandardCharsets.UTF_8);
    }

    private int contar(String texto, String pedaco) {
        int n = 0;
        for (int i = texto.indexOf(pedaco); i >= 0; i = texto.indexOf(pedaco, i + 1)) n++;
        return n;
    }
}
