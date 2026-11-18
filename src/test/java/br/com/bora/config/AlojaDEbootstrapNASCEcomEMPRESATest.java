package br.com.bora.config;

import br.com.bora.entity.Empresa;
import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.service.EmpresaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A loja que o primeiro start cria nasce <b>com empresa</b>.
 *
 * <h2>O defeito que isto fecha, e por que ele não aparecia em produção</h2>
 * <p>Três caminhos deste sistema criam loja. Dois preenchem {@code empresa_id}:
 * {@code PlataformaController.criarLoja} (o painel) e {@code SignupController} (o cadastro
 * público). O terceiro é este — o bootstrap do primeiro start — e ele criava a "Loja Demo"
 * <b>sem empresa nenhuma</b>.</p>
 *
 * <p>Em produção isso nunca doeu, e por um motivo que explica por que ninguém viu: o bootstrap só
 * cria loja quando <b>não existe nenhuma</b>. Medido no dump de 18/11/2026, as 8 lojas de produção
 * estão todas com empresa. O defeito ficava inteiro na frente de uma instalação nova.</p>
 *
 * <h2>Por que isso passou a importar em 18/11/2026</h2>
 * <p>Porque a fila de eventos do ERP tira a empresa do evento da LOJA
 * ({@code PublicadorDeEventos}). Numa instalação que nascesse por este caminho, <b>todo evento
 * sairia sem empresa, para sempre</b> — e sem erro nenhum, porque a coluna é opcional de propósito.
 * Preencher a coluna na fila sem fechar este caminho seria consertar metade.</p>
 *
 * <h2>Por que sem CNPJ</h2>
 * <p>Aqui não há documento para consultar. {@code paraDocumento(null, ...)} cria empresa própria em
 * vez de adivinhar dona — a mesma regra do resto do sistema: "não sei" responde não, e nunca junta
 * clientes diferentes debaixo da mesma empresa.</p>
 */
class AlojaDEbootstrapNASCEcomEMPRESATest {

    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private UsuarioLojaRepository vinculos;
    private EmpresaService empresas;

    @BeforeEach
    void montar() {
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        vinculos = mock(UsuarioLojaRepository.class);
        empresas = mock(EmpresaService.class);

        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(usuarios.count()).thenReturn(0L);          // banco vazio: é o caso do bootstrap
        when(usuarios.findAll()).thenReturn(List.of());
        when(usuarios.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(inv -> {
            Loja l = inv.getArgument(0);
            l.id = 1L;
            return l;
        });

        Empresa e = new Empresa();
        e.setId(77L);
        e.setRazaoSocial("Loja Demo");
        when(empresas.paraDocumento(any(), anyString())).thenReturn(e);
    }

    private BootstrapAdmin bootstrap() {
        return new BootstrapAdmin(usuarios, lojas, new BCryptPasswordEncoder(), vinculos, empresas,
                "admin@bora.app", "uma-senha-que-nao-e-a-padrao", "", "");
    }

    /**
     * O teste que mata o defeito: com a linha {@code l.empresaId = ...} removida, ele falha.
     *
     * <p>A asserção é sobre o objeto que foi <b>gravado</b>, capturado no {@code save} — não sobre
     * o que o {@code EmpresaService} devolveu. É a diferença entre "pedi a empresa" e "gravei a
     * empresa na loja": o primeiro passaria mesmo se o valor fosse jogado fora.</p>
     */
    @Test
    void semNENHUMAlojaObootstrapCRIAaDEMOjaCOMempresa() {
        when(lojas.findAll()).thenReturn(List.of());

        bootstrap().run();

        org.mockito.ArgumentCaptor<Loja> gravada = org.mockito.ArgumentCaptor.forClass(Loja.class);
        verify(lojas).save(gravada.capture());
        assertEquals("Loja Demo", gravada.getValue().getNome());
        assertEquals(77L, gravada.getValue().empresaId,
                "a loja de bootstrap tem de nascer com empresa: sem ela, TODO evento da fila desta "
                        + "instalacao sairia sem empresa e sem erro nenhum");
    }

    /**
     * E quando JÁ existe loja, o bootstrap não cria nada nem inventa empresa.
     *
     * <p>Este é o caso da produção de hoje, e é o controle do teste de cima: sem ele, um bootstrap
     * que criasse loja sempre também passaria na asserção principal — e passaria criando uma loja
     * a mais em cada start.</p>
     */
    @Test
    void comLOJAjaEXISTENTEnaoCRIAnadaENAOmexeEMempresa() {
        Loja jaExiste = new Loja();
        jaExiste.id = 9L;
        jaExiste.setNome("Loja de verdade");
        jaExiste.empresaId = 3L;
        when(lojas.findAll()).thenReturn(List.of(jaExiste));

        bootstrap().run();

        verify(lojas, never()).save(any(Loja.class));
        verifyNoInteractions(empresas);
        assertEquals(3L, jaExiste.empresaId, "a loja que ja existia fica exatamente como estava");
    }
}
