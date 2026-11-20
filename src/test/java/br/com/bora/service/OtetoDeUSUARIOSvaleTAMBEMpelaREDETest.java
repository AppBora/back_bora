package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ITEM 7 DA SECAO 4 DA VARREDURA (confirmado em 19/11/2026, consertado em 20/11).
 *
 * <p>O plano UNICO da 15 usuarios por loja, e esse teto EXISTE: o {@code UsuarioService} chama
 * {@code PlanoService.checarLimiteUsuarios} em dois pontos, ao criar usuario. Mas
 * {@code RedeService.vincular} da acesso a uma loja <b>sem passar por nenhum deles</b>. A loja com
 * os 15 ativos recebia o 16o pela rede.</p>
 *
 * <p>Nao e um teto "quase cumprido": pela rede ele nao valia nada. Uma empresa com duas lojas podia
 * vincular a mesma equipe inteira as duas e operar com o dobro do que o plano vende.</p>
 *
 * <p><b>O terceiro teste e o que me obrigou a pensar na ordem.</b> A checagem tinha de entrar
 * DEPOIS do retorno de vinculo ja existente. Se entrasse antes, uma loja no teto nao conseguiria
 * nem reconfirmar um vinculo que ela JA tem -- e esse pedido e idempotente, nao acrescenta ninguem.
 * O conserto obvio (checar logo no comeco) criaria um defeito novo no lugar do antigo.</p>
 */
class OtetoDeUSUARIOSvaleTAMBEMpelaREDETest {

    private static final Long MINHA_LOJA = 18L;   // a loja de quem esta logado
    private static final Long LOJA_ALVO  = 19L;   // a outra loja da mesma rede
    private static final Long USUARIO    = 7L;

    private UsuarioLojaRepository vinculos;
    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private AuthContext ctx;
    private EmpresaService empresas;
    private PlanoService planos;
    private RedeService rede;

    @BeforeEach
    void montar() {
        vinculos = mock(UsuarioLojaRepository.class);
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        ctx = mock(AuthContext.class);
        empresas = mock(EmpresaService.class);
        planos = mock(PlanoService.class);

        rede = new RedeService(vinculos, usuarios, lojas, mock(PedidoRepository.class),
                mock(JwtService.class), ctx, empresas, planos);

        Usuario u = new Usuario();
        u.setId(USUARIO);
        u.setNome("Maria");
        u.setPapel(Papel.OPERADOR);
        u.setLojaId(MINHA_LOJA);

        Loja alvo = new Loja();
        alvo.id = LOJA_ALVO;
        alvo.nome = "Zira Centro";

        when(ctx.lojaId()).thenReturn(MINHA_LOJA);
        when(usuarios.findById(USUARIO)).thenReturn(Optional.of(u));
        when(lojas.findById(LOJA_ALVO)).thenReturn(Optional.of(alvo));
        when(empresas.mesmaEmpresa(anyLong(), anyLong())).thenReturn(true);
        when(vinculos.existsByUsuarioIdAndLojaId(USUARIO, LOJA_ALVO)).thenReturn(false);
    }

    /** Faz o PlanoService recusar, do mesmo jeito que ele recusa de verdade quando a loja esta no teto. */
    private void lojaNoTeto(Long lojaId) {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Limite de usuários do plano UNICO atingido (15). Faça upgrade."))
                .when(planos).checarLimiteUsuarios(lojaId);
    }

    // ------------------------------------------------------------------ a premissa

    // SEM ISTO, um teste que so exige recusa passaria com um vincular que nao vincula ninguem --
    // e a rede inteira pararia de funcionar sem nenhum teste ficar vermelho.
    @Test
    void premissa_abaixoDoTetoOvinculoEhCRIADO() {
        Map<String, Object> r = rede.vincular(USUARIO, LOJA_ALVO);

        assertEquals(Boolean.TRUE, r.get("criado"), "abaixo do teto o vinculo tem de ser criado");
        verify(vinculos).save(any(UsuarioLoja.class));
        verify(planos).checarLimiteUsuarios(LOJA_ALVO);
    }

    // ------------------------------------------------------------------ o que importa

    @Test
    void noTetoOvincularRECUSAeNADAehGRAVADO() {
        lojaNoTeto(LOJA_ALVO);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> rede.vincular(USUARIO, LOJA_ALVO),
                "a loja no teto do plano aceitou mais um usuario pela rede");

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        // O QUE MAIS IMPORTA: recusar sem gravar. Uma recusa depois do save deixaria o 16o usuario
        // dentro da loja e devolveria erro -- o pior dos dois mundos.
        verify(vinculos, never()).save(any(UsuarioLoja.class));
    }

    @Test
    void oTetoEhDaLojaQUEreCEBE_naoDaLojaDeQuemEstaLogado() {
        rede.vincular(USUARIO, LOJA_ALVO);

        // Se passasse ctx.lojaId(), a loja cheia receberia gente sempre que o administrador
        // estivesse logado numa loja vazia da mesma rede -- que e justamente como a rede e usada.
        verify(planos).checarLimiteUsuarios(LOJA_ALVO);
        verify(planos, never()).checarLimiteUsuarios(MINHA_LOJA);
    }

    // ------------------------------------------------------------------ a ordem

    @Test
    void vinculoQueJAexisteNAOpassaPeloTeto() {
        when(vinculos.existsByUsuarioIdAndLojaId(USUARIO, LOJA_ALVO)).thenReturn(true);
        lojaNoTeto(LOJA_ALVO);

        Map<String, Object> r = rede.vincular(USUARIO, LOJA_ALVO);

        assertEquals(Boolean.FALSE, r.get("criado"));
        verify(planos, never()).checarLimiteUsuarios(anyLong());
        verify(vinculos, never()).save(any(UsuarioLoja.class));
    }

    // ------------------------------------------------------------------ as guardas antigas ficam

    // A varredura trouxe um teto novo; ela nao pode ter levado embora o isolamento que ja existia.
    @Test
    void aGuardaDeEMPRESAcontinuaVALENDO_eVemANTESdoTeto() {
        when(empresas.mesmaEmpresa(MINHA_LOJA, LOJA_ALVO)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> rede.vincular(USUARIO, LOJA_ALVO));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode(),
                "loja de outra empresa tem de ser 403, nao 409 de teto");
        verify(planos, never()).checarLimiteUsuarios(anyLong());
    }
}
