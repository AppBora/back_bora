package br.com.bora.service;

import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A tela de Usuários da LOJA não pode deixar a loja sem administrador.
 *
 * <h2>O defeito que isto fecha, e ele estava no ar</h2>
 * <p>Até 17/11/2026 o {@code UsuarioService} só impedia o administrador de mexer na própria conta.
 * Ele podia <b>desativar</b> ou <b>rebaixar</b> o último administrador da loja — inclusive um sócio
 * — e o resultado é uma loja que <b>ninguém consegue administrar</b>: não há quem crie usuário, quem
 * redefina senha, nem quem desfaça o que acabou de ser feito. Só o {@code ADMINISTRADOR_BORA} tira
 * a loja desse estado.</p>
 *
 * <p>E não é só incômodo: {@code donosDaLoja} é a consulta que informa o e-mail do lojista na
 * cobrança do Asaas, e filtra {@code ativo = true}. Loja sem dono ativo gera cobrança <b>sem
 * e-mail</b>, e o lojista não recebe a fatura.</p>
 *
 * <p>O serviço da plataforma tinha a mesma guarda; esta tela não tinha nenhuma. 23 lojas reais
 * usavam a tela sem ela.</p>
 *
 * <h2>O que estes testes NÃO provam</h2>
 * <p>Que o {@code FOR UPDATE} de fato serializa duas chamadas simultâneas — isso é comportamento do
 * PostgreSQL e foi medido contra banco de verdade, não aqui. Aqui se prova que a consulta
 * <b>travada</b> é a chamada, que é o que pega quem trocar uma pela outra.</p>
 */
class LojaNaoPodeFicarSemAdministradorTest {

    private static final long LOJA = 7L;
    private static final long EU = 1L;

    private final UsuarioRepository repo = mock(UsuarioRepository.class);
    private final PlanoService planos = mock(PlanoService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthContext ctx = mock(AuthContext.class);
    private final UsuarioLojaRepository vinculos = mock(UsuarioLojaRepository.class);
    private final PedidoDeSenhaService pedidosDeSenha = mock(PedidoDeSenhaService.class);

    private UsuarioService servico() {
        when(ctx.atual()).thenReturn(new BoraPrincipal(EU, LOJA, "ADMINISTRADOR_LOJA", "eu@loja.test"));
        when(ctx.lojaId()).thenReturn(LOJA);
        return new UsuarioService(repo, planos, encoder, ctx, vinculos, pedidosDeSenha);
    }

    private Usuario usuario(long id, Papel papel, boolean ativo) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setPapel(papel);
        u.setLojaId(LOJA);
        u.setAtivo(ativo);
        u.setNome("Pessoa " + id);
        u.setEmail("u" + id + "@loja.test");
        when(repo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    // ------------------------------------------------------------- desativar

    @Test
    void desativarOUltimoAdministradorDaLojaEhRecusado() {
        UsuarioService s = servico();
        Usuario unico = usuario(9L, Papel.ADMINISTRADOR_LOJA, true);
        when(repo.donosDaLojaParaAtualizar(LOJA)).thenReturn(List.of(unico));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(9L, false));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("sem nenhum administrador"), e.getReason());
        verify(repo, never()).save(any());
    }

    @Test
    void desativarUmAdministradorQuandoHaOutroPassa() {
        UsuarioService s = servico();
        Usuario alvo = usuario(9L, Papel.ADMINISTRADOR_LOJA, true);
        Usuario socio = new Usuario();
        socio.setId(10L);
        socio.setPapel(Papel.ADMINISTRADOR_LOJA);
        socio.setAtivo(true);
        when(repo.donosDaLojaParaAtualizar(LOJA)).thenReturn(List.of(alvo, socio));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.definirAtivo(9L, false));
        assertFalse(alvo.getAtivo());
    }

    /** Desativar quem não é administrador não passa pela guarda — operador não administra nada. */
    @Test
    void desativarOperadorNaoConsultaDonos() {
        UsuarioService s = servico();
        usuario(9L, Papel.OPERADOR, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.definirAtivo(9L, false));
        verify(repo, never()).donosDaLojaParaAtualizar(any());
    }

    /** Reativar nunca deixa ninguém sem dono — e não pode ser barrado pela guarda. */
    @Test
    void reativarNaoPassaPelaGuarda() {
        UsuarioService s = servico();
        usuario(9L, Papel.ADMINISTRADOR_LOJA, false);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.definirAtivo(9L, true));
        verify(repo, never()).donosDaLojaParaAtualizar(any());
    }

    // ------------------------------------------------------------- rebaixar

    /**
     * Rebaixar tem o mesmo efeito de desativar, e era o caminho mais fácil de errar: a tela oferece
     * um seletor de papel, e trocar "Administrador da loja" por "Gerente" no único administrador
     * deixa a loja órfã sem nenhum aviso.
     */
    @Test
    void rebaixarOUltimoAdministradorEhRecusado() {
        UsuarioService s = servico();
        Usuario unico = usuario(9L, Papel.ADMINISTRADOR_LOJA, true);
        when(repo.donosDaLojaParaAtualizar(LOJA)).thenReturn(List.of(unico));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.atualizarPapel(9L, "GERENTE"));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void promoverAlguemAAdministradorNaoPassaPelaGuarda() {
        UsuarioService s = servico();
        usuario(9L, Papel.OPERADOR, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.atualizarPapel(9L, "ADMINISTRADOR_LOJA"));
        verify(repo, never()).donosDaLojaParaAtualizar(any());
    }

    /** Trocar o papel de um administrador POR administrador não tira ninguém: não deve barrar. */
    @Test
    void manterOpapelDeAdministradorNaoEhRebaixar() {
        UsuarioService s = servico();
        usuario(9L, Papel.ADMINISTRADOR_LOJA, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.atualizarPapel(9L, "ADMINISTRADOR_LOJA"));
        verify(repo, never()).donosDaLojaParaAtualizar(any());
    }

    // ------------------------------------------------------------- teto do plano

    /**
     * REATIVAR conta no teto do plano, igual a criar.
     *
     * <p>O limite de 15 usuários era conferido só no {@code criar}. Com a loja cheia, desativar
     * alguém e reativar outro passava para 16 e respondia 200 — o limite comercial se burlava sem
     * ninguém querer, só mexendo nos botões da tela. Reproduzido em 17/11/2026.</p>
     */
    @Test
    void reativarRespeitaOTetoDoPlano() {
        UsuarioService s = servico();
        usuario(9L, Papel.OPERADOR, false);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Limite de usuários do plano"))
                .when(planos).checarLimiteUsuarios(LOJA);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(9L, true));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(repo, never()).save(any());
    }

    /** Desativar nunca passa do teto — e não pode ser barrado por ele. */
    @Test
    void desativarNaoConsultaOTetoDoPlano() {
        UsuarioService s = servico();
        usuario(9L, Papel.OPERADOR, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.definirAtivo(9L, false));
        verify(planos, never()).checarLimiteUsuarios(any());
    }

    /** Quem já está ativo não é uma entrada nova: reenviar ativo=true não deve bater no teto. */
    @Test
    void reenviarAtivoParaQuemJaEstaAtivoNaoConsultaOTeto() {
        UsuarioService s = servico();
        usuario(9L, Papel.OPERADOR, true);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> s.definirAtivo(9L, true));
        verify(planos, never()).checarLimiteUsuarios(any());
    }

    // ------------------------------------------------------------- o lock

    /**
     * A contagem tem de sair da consulta TRAVADA. Sem o lock, dois administradores se desativando
     * ao mesmo tempo leem "o outro ainda está ativo" e passam os dois — foi assim que uma loja
     * ficou com zero administradores no teste de concorrência de 17/11.
     */
    @Test
    void aContagemUsaAConsultaTravada() {
        UsuarioService s = servico();
        Usuario alvo = usuario(9L, Papel.ADMINISTRADOR_LOJA, true);
        Usuario socio = new Usuario();
        socio.setId(10L);
        socio.setPapel(Papel.ADMINISTRADOR_LOJA);
        socio.setAtivo(true);
        when(repo.donosDaLojaParaAtualizar(LOJA)).thenReturn(List.of(alvo, socio));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        s.definirAtivo(9L, false);

        verify(repo).donosDaLojaParaAtualizar(LOJA);
        verify(repo, never()).donosDaLoja(any());
    }
}
