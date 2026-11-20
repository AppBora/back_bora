package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O cliente que paga por PIX no cardápio NÃO é cliente de marketplace.
 *
 * <h2>O defeito, achado na varredura de 18/11/2026</h2>
 * <p>{@code NotificacaoClienteService} saía fora para <b>qualquer</b> {@code canalExterno}
 * preenchido. O pedido do cardápio pago por PIX recebe {@code canalExterno = "PIX_ASAAS"} só para
 * guardar o id da cobrança do Asaas ({@code PublicController}). Resultado: <b>o cliente que pagou
 * por PIX nunca recebia o WhatsApp de acompanhamento</b> — recurso do Módulo IA, que o lojista paga
 * a <b>R$ 99/mês</b>.</p>
 *
 * <p>E o mesmo defeito tirava dele o <b>botão de repetir o pedido</b>:
 * {@code PedidoRepository.ultimoPedidoRepetivelPorCliente} filtrava
 * {@code p.canalExterno is null}.</p>
 *
 * <p>O que tornava isso indefensável: <b>o próprio código já sabia disso em outro lugar.</b>
 * {@code DesempenhoService.geraCashback} escrevia {@code && !"PIX_ASAAS".equals(p.canalExterno)}
 * exatamente para não confundir PIX com marketplace. A mesma regra em dois lugares, e o lado errado
 * era o que o lojista pagava.</p>
 *
 * <p>O conserto foi tirar a regra dos dois lugares e pôr <b>uma só</b> em
 * {@link Pedido#ehDeMarketplace()} — o mesmo padrão do {@code boraEhVenda} no frontend.</p>
 *
 * <p>Antes deste arquivo <b>não existia nenhum teste</b> do serviço de notificação.</p>
 */
class OclienteQuePAGAporPIXnaoEHmarketplaceTest {

    private static final Long LOJA = 18L;

    private final LojaRepository lojas = mock(LojaRepository.class);
    private final ClienteRepository clientes = mock(ClienteRepository.class);
    private final IntegracaoCanalRepository integracoes = mock(IntegracaoCanalRepository.class);
    private final WhatsAppSender whats = mock(WhatsAppSender.class);
    private final RepetirPedidoService repetir = mock(RepetirPedidoService.class);

    private final NotificacaoClienteService svc =
            new NotificacaoClienteService(lojas, clientes, integracoes, whats, repetir);

    /** A loja com o add-on pago e o WhatsApp conectado — tudo pronto para o aviso sair. */
    private void lojaPagaEconectada() {
        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome("Zirá Açaí");
        l.moduloIa = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        IntegracaoCanal zap = new IntegracaoCanal();
        zap.ativo = true;
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));
        when(repetir.token(anyLong(), anyLong())).thenReturn("tok");
    }

    private Pedido pedido(String canalExterno) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.codigo = "CD-500";
        p.valorTotal = new BigDecimal("42.00");
        p.clienteTelefone = "11988887777";   // celular de PESSOA, não central de marketplace
        p.canalExterno = canalExterno;
        return p;
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: com o pedido normal do cardápio, o aviso SAI.
     *
     * <p>Sem isto, um teste que passasse porque o serviço nunca manda nada (loja sem add-on, mock mal
     * montado, telefone recusado) pareceria prova de que o PIX foi consertado.</p>
     */
    @Test
    void aPREMISSAoPEDIDOnormalDOcardapioRECEBEaviso() {
        lojaPagaEconectada();

        svc.notificarFase(pedido(null), StatusPedido.CONFIRMADO);

        verify(whats).enviar(any(), eq("11988887777"), contains("confirmado"));
    }

    // ----------------------------------------------------------------- o que importa

    /** O DEFEITO: o pedido pago por PIX tem de receber o aviso igual ao do cardápio. */
    @Test
    void oPEDIDOpagoPORpixRECEBEoAVISO() {
        lojaPagaEconectada();

        svc.notificarFase(pedido(Pedido.CANAL_PIX), StatusPedido.CONFIRMADO);

        verify(whats).enviar(any(), eq("11988887777"), contains("confirmado"));
    }

    /** E em todas as fases, não só na confirmação — o recurso é o acompanhamento inteiro. */
    @Test
    void oPIXrecebeEMtodasASfases() {
        lojaPagaEconectada();
        for (StatusPedido s : new StatusPedido[]{StatusPedido.CONFIRMADO, StatusPedido.EM_PREPARO,
                StatusPedido.PRONTO, StatusPedido.SAIU_PARA_ENTREGA, StatusPedido.ENTREGUE}) {
            svc.notificarFase(pedido(Pedido.CANAL_PIX), s);
        }
        verify(whats, times(5)).enviar(any(), eq("11988887777"), anyString());
    }

    // ----------------------------------------------------------------- o controle

    /**
     * O CONTROLE, e ele não é detalhe: o pedido de marketplace continua SEM aviso.
     *
     * <p>A razão é real — no iFood o telefone do pedido é a central (0800 + localizador), não o
     * celular da pessoa. Se o conserto do PIX tivesse sido "tirar a guarda", este teste cairia.</p>
     */
    @Test
    void oCONTROLEoPEDIDOdeIFOODcontinuaSEMaviso() {
        lojaPagaEconectada();

        svc.notificarFase(pedido("IFOOD"), StatusPedido.CONFIRMADO);

        verify(whats, never()).enviar(any(), anyString(), anyString());
    }

    @Test
    void oCONTROLEoPEDIDOde99FOODcontinuaSEMaviso() {
        lojaPagaEconectada();

        svc.notificarFase(pedido("99FOOD"), StatusPedido.CONFIRMADO);

        verify(whats, never()).enviar(any(), anyString(), anyString());
    }

    /** Sem o add-on pago, nada sai — o recurso é vendido, não cortesia. */
    @Test
    void semOaddONpagoNADAsai() {
        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome("Zirá Açaí");
        l.moduloIa = false;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        svc.notificarFase(pedido(Pedido.CANAL_PIX), StatusPedido.CONFIRMADO);

        verify(whats, never()).enviar(any(), anyString(), anyString());
    }

    // ----------------------------------------------------------------- a regra, sozinha

    @Test
    void aREGRAunicaRESPONDEoQUEseESPERA() {
        assertTrue(pedido("IFOOD").ehDeMarketplace(), "iFood é marketplace");
        assertTrue(pedido("99FOOD").ehDeMarketplace(), "99Food é marketplace");
        assertFalse(pedido(Pedido.CANAL_PIX).ehDeMarketplace(),
                "PIX_ASAAS é forma de pagamento do NOSSO cardápio, não marketplace");
        assertFalse(pedido(null).ehDeMarketplace(), "pedido do balcão/cardápio não é marketplace");
        assertFalse(pedido("   ").ehDeMarketplace(), "canal em branco não é canal");
    }

    // ----------------------------------------------------------------- a guarda contra deriva

    /**
     * O literal da consulta JPQL tem de bater com a constante.
     *
     * <p>{@code PedidoRepository.ultimoPedidoRepetivelPorCliente} repete o texto {@code 'PIX_ASAAS'}
     * porque JPQL não chama método Java. São dois lugares com o mesmo valor, e dois lugares derivam.
     * Este teste lê o <b>arquivo</b> e exige que o literal seja exatamente
     * {@link Pedido#CANAL_PIX} — se alguém mudar a constante e esquecer a consulta, o cliente do PIX
     * perde o botão de repetir de novo, em silêncio.</p>
     */
    @Test
    void oLITERALdaCONSULTAbateComAconstante() throws Exception {
        Path arq = Path.of("src/main/java/br/com/bora/repository/PedidoRepository.java");
        assertTrue(Files.exists(arq), "não achei o PedidoRepository: " + arq.toAbsolutePath());
        String fonte = Files.readString(arq, StandardCharsets.UTF_8);

        int i = fonte.indexOf("ultimoPedidoRepetivelPorCliente");
        assertTrue(i > 0, "não achei o método da consulta");
        String consulta = fonte.substring(Math.max(0, i - 900), i);

        assertTrue(consulta.contains("p.canalExterno = '" + Pedido.CANAL_PIX + "'"),
                "a consulta de pedido repetível não aceita mais o canal '" + Pedido.CANAL_PIX
                        + "'. Se a constante mudou, a consulta tem de mudar junto — senão o cliente "
                        + "que paga por PIX volta a perder o botão de repetir o pedido.");
    }
}
