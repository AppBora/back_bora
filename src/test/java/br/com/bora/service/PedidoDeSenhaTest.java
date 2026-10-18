package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PedidoDeSenha;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoDeSenhaRepository;
import br.com.bora.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Esqueci minha senha": o pedido chega a quem pode resolver.
 *
 * <p><b>O estado real em 18/10/2026:</b> o sistema não envia e-mail nenhum, e o envio por WhatsApp
 * depende da Cloud API da Meta. Perguntei à Meta com as credenciais que estão no banco: a da loja 1
 * respondeu "Session has expired on 08-Jul-26" e a da loja 18 tem 9 caracteres — é um texto de
 * exemplo. <b>Nenhuma loja envia mensagem hoje.</b> Por isso o que garante o aviso é o registro no
 * banco, que a tela de Usuários mostra; o WhatsApp é tentativa, e {@code avisadoEm} conta se saiu.</p>
 */
class PedidoDeSenhaTest {

    private static final Long LOJA = 18L;
    private static final Long USUARIO = 34L;

    private PedidoDeSenhaRepository pedidos;
    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private IntegracaoCanalRepository integracoes;
    private WhatsAppSender whatsapp;
    private PedidoDeSenhaService service;
    private Usuario ana;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoDeSenhaRepository.class);
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        integracoes = mock(IntegracaoCanalRepository.class);
        whatsapp = mock(WhatsAppSender.class);

        ana = new Usuario();
        ana.setId(USUARIO);
        ana.setNome("Ana");
        ana.setEmail("anazira@borahapp.com.br");
        ana.setLojaId(LOJA);
        ana.setAtivo(true);

        when(usuarios.findByEmail("anazira@borahapp.com.br")).thenReturn(Optional.of(ana));
        when(pedidos.findFirstByUsuarioIdAndAtendidoEmIsNull(USUARIO)).thenReturn(Optional.empty());
        when(pedidos.countByUsuarioIdAndCriadoEmAfter(anyLong(), any())).thenReturn(0L);
        when(pedidos.save(any(PedidoDeSenha.class))).thenAnswer(i -> i.getArgument(0));
        when(integracoes.findByLojaIdAndCanal(anyLong(), anyString())).thenReturn(Optional.empty());

        service = new PedidoDeSenhaService(pedidos, usuarios, lojas, integracoes, whatsapp, "");
    }

    private PedidoDeSenha gravado() {
        ArgumentCaptor<PedidoDeSenha> c = ArgumentCaptor.forClass(PedidoDeSenha.class);
        verify(pedidos, atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    @Test
    void oPedidoFicaGravadoParaOAdministradorVER() {
        service.registrar("anazira@borahapp.com.br");

        PedidoDeSenha p = gravado();
        assertEquals(USUARIO, p.usuarioId);
        assertEquals(LOJA, p.lojaId, "o aviso aparece na tela de Usuarios DESSA loja");
        assertNull(p.atendidoEm, "nasce em aberto");
        assertNull(p.avisadoEm, "sem WhatsApp conectado, ninguem foi avisado por mensagem");
    }

    @Test
    void emailQueNAOexisteNaoGravaNada_eNaoDenunciaQueNaoExiste() {
        // O endpoint e publico. Se gravasse so para e-mail que existe E respondesse diferente, a tela
        // de login viraria um verificador de clientes. Quem responde sempre igual e o controller; aqui
        // a garantia e nao sobrar rastro.
        when(usuarios.findByEmail("naoexiste@nada.com")).thenReturn(Optional.empty());

        service.registrar("naoexiste@nada.com");

        verify(pedidos, never()).save(any());
    }

    @Test
    void usuarioDESATIVADOnaoAbrePedido() {
        ana.setAtivo(false);

        service.registrar("anazira@borahapp.com.br");

        verify(pedidos, never()).save(any());
    }

    @Test
    void emailComEspacoEMAIUSCULAacha() {
        service.registrar("  AnaZira@BoraHapp.com.BR  ");

        verify(usuarios).findByEmail("anazira@borahapp.com.br");
        verify(pedidos, atLeastOnce()).save(any());
    }

    @Test
    void clicarDeNOVOnaoVIRAoutraLinha() {
        // Tres cliques na mesma pessoa deixariam o administrador sem saber se sao tres pessoas.
        PedidoDeSenha aberto = new PedidoDeSenha();
        aberto.id = 7L;
        aberto.usuarioId = USUARIO;
        when(pedidos.findFirstByUsuarioIdAndAtendidoEmIsNull(USUARIO)).thenReturn(Optional.of(aberto));

        service.registrar("anazira@borahapp.com.br");

        verify(pedidos, never()).save(argThat(p -> p.id == null));
    }

    @Test
    void muitosPedidosNaMESMAhoraParamDeGravar() {
        when(pedidos.countByUsuarioIdAndCriadoEmAfter(eq(USUARIO), any())).thenReturn(3L);

        service.registrar("anazira@borahapp.com.br");

        verify(pedidos, never()).save(any());
    }

    @Test
    void semWhatsAppCONECTADOnaoTentaEnviar() {
        // O estado de hoje em toda loja. O pedido vale do mesmo jeito: o painel e o canal que funciona.
        service.registrar("anazira@borahapp.com.br");

        verify(whatsapp, never()).enviar(any(), anyString(), anyString());
        assertNull(gravado().avisadoEm);
    }

    @Test
    void comWhatsAppCONECTADOavisaODonoDaLoja() {
        IntegracaoCanal zap = new IntegracaoCanal();
        zap.lojaId = LOJA;
        zap.canal = "WHATSAPP";
        zap.ativo = true;
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));
        Loja l = new Loja();
        l.setId(LOJA);
        l.whatsappDono = "5515981034318";
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(whatsapp.enviar(eq(zap), eq("5515981034318"), anyString())).thenReturn(true);

        service.registrar("anazira@borahapp.com.br");

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(whatsapp).enviar(eq(zap), eq("5515981034318"), texto.capture());
        assertTrue(texto.getValue().contains("Ana"), texto.getValue());
        assertTrue(texto.getValue().contains("Usuarios"), "a mensagem diz ONDE resolver: " + texto.getValue());
        assertNotNull(gravado().avisadoEm, "saiu de verdade, entao fica registrado que saiu");
    }

    @Test
    void whatsAppCONECTADOmasRECUSADOnaoMenteQueAvisou() {
        IntegracaoCanal zap = new IntegracaoCanal();
        zap.lojaId = LOJA;
        zap.canal = "WHATSAPP";
        zap.ativo = true;
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));
        Loja l = new Loja();
        l.setId(LOJA);
        l.whatsappDono = "5515981034318";
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(whatsapp.enviar(any(), anyString(), anyString())).thenReturn(false);   // token vencido

        service.registrar("anazira@borahapp.com.br");

        assertNull(gravado().avisadoEm,
                "token vencido e exatamente o estado real hoje: a tela NAO pode dizer que avisou");
    }

    @Test
    void integracaoWHATSAPPdesligadaContaComoSemWhatsApp() {
        IntegracaoCanal zap = new IntegracaoCanal();
        zap.lojaId = LOJA;
        zap.canal = "WHATSAPP";
        zap.ativo = false;                 // e o estado da loja 18 hoje
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));

        service.registrar("anazira@borahapp.com.br");

        verify(whatsapp, never()).enviar(any(), anyString(), anyString());
    }

    @Test
    void oSuporteDaPlataformaTambemRecebeQuandoConfigurado() {
        IntegracaoCanal zap = new IntegracaoCanal();
        zap.lojaId = LOJA;
        zap.canal = "WHATSAPP";
        zap.ativo = true;
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));
        when(lojas.findById(LOJA)).thenReturn(Optional.empty());   // loja sem whatsapp do dono
        when(whatsapp.enviar(any(), eq("5515998602332"), anyString())).thenReturn(true);

        new PedidoDeSenhaService(pedidos, usuarios, lojas, integracoes, whatsapp, "5515998602332")
                .registrar("anazira@borahapp.com.br");

        verify(whatsapp).enviar(eq(zap), eq("5515998602332"), anyString());
    }

    @Test
    void redefinirASenhaFECHAoPedido() {
        PedidoDeSenha aberto = new PedidoDeSenha();
        aberto.usuarioId = USUARIO;
        when(pedidos.findByUsuarioIdAndAtendidoEmIsNull(USUARIO)).thenReturn(List.of(aberto));

        service.atendido(USUARIO);

        assertNotNull(aberto.atendidoEm, "o aviso some pela acao que resolve, nao por um botao a parte");
        verify(pedidos).saveAll(anyList());
    }

    @Test
    void semPedidoAberto_redefinirSenhaNaoGravaNada() {
        when(pedidos.findByUsuarioIdAndAtendidoEmIsNull(USUARIO)).thenReturn(List.of());

        service.atendido(USUARIO);

        verify(pedidos, never()).saveAll(anyList());
    }

    @Test
    void lojaNulaNaoQuebra_eNaoListaNada() {
        // ADMINISTRADOR_BORA nao tem loja.
        assertTrue(service.emAberto(null).isEmpty());
        verify(pedidos, never()).findByLojaIdAndAtendidoEmIsNullOrderByCriadoEmDesc(any());
    }

    @Test
    void aListaDoAdministradorVemDaLojaDELE() {
        PedidoDeSenha p = new PedidoDeSenha();
        p.usuarioId = USUARIO;
        p.criadoEm = OffsetDateTime.now();
        when(pedidos.findByLojaIdAndAtendidoEmIsNullOrderByCriadoEmDesc(LOJA)).thenReturn(List.of(p));

        assertEquals(1, service.emAberto(LOJA).size());
    }
}
