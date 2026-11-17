package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * As travas da tela "Todos os usuários da plataforma" ({@link UsuariosDaPlataformaService}).
 *
 * <p>O administrador master pode mexer em qualquer conta de qualquer loja — e é justamente por isso
 * que o isolamento multi-tenant, que protege as outras telas, <b>não protege esta</b>. O que fica no
 * lugar dele são estas travas, e é o que este arquivo guarda.</p>
 *
 * <h2>A que mais importa não é zelo abstrato</h2>
 * <p>{@code UsuarioRepository.donosDaLoja} é a consulta que informa o e-mail do lojista na cobrança
 * do Asaas, e ela filtra {@code ativo = true}. Desativar ou apagar o último
 * {@code ADMINISTRADOR_LOJA} de uma loja faz a cobrança sair <b>sem e-mail nenhum</b>, e o lojista
 * não recebe a fatura. Já aconteceu neste projeto por outro caminho.</p>
 *
 * <h2>E a ordem do DELETE é FK, não preferência</h2>
 * <p>{@code usuario_loja.usuario_id} é FK <b>sem {@code ON DELETE}</b> (medido no {@code V17}).
 * Apagar o usuário antes dos vínculos estoura violação de integridade — e, numa transação do
 * Postgres, a recusa desfaz a transação inteira. Por isso há um teste só da ORDEM.</p>
 *
 * <h2>O que estes testes NÃO provam</h2>
 * <p>Que o {@code AuthContext.requireAdminBora} de verdade barra: aqui ele é mock. Isso é do
 * AuthContext. E não provam o comportamento do banco — a FK e o {@code ON DELETE CASCADE} do
 * {@code pedido_de_senha} foram conferidos contra um Postgres de verdade, não aqui.</p>
 */
class LojaNaoFicaSemDonoTest {

    private static final long EU = 1L;

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final UsuarioLojaRepository vinculos = mock(UsuarioLojaRepository.class);
    private final LojaRepository lojas = mock(LojaRepository.class);
    private final LogStatusRepository logs = mock(LogStatusRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PlanoService planos = mock(PlanoService.class);
    private final AuthContext ctx = mock(AuthContext.class);

    private UsuariosDaPlataformaService servico() {
        when(ctx.atual()).thenReturn(new BoraPrincipal(EU, null, "ADMINISTRADOR_BORA", "eu@bora.app"));
        Loja l = new Loja();
        l.setId(7L);
        l.setNome("Zirá Centro");
        when(lojas.findAll()).thenReturn(List.of(l));
        return new UsuariosDaPlataformaService(usuarios, vinculos, lojas, logs, encoder, planos, ctx);
    }

    /**
     * Monta a entidade e NÃO stuba nada.
     *
     * <p>Separado do {@link #usuario} porque Mockito recusa um {@code when(...)} aberto dentro do
     * argumento de outro {@code when(...)} — {@code UnfinishedStubbing}. Chamar o helper que stuba
     * dentro de um {@code thenReturn(List.of(...))} quebra o teste, e quebrou: três deles.</p>
     */
    private Usuario conta(long id, Papel papel, Long lojaId, boolean ativo) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setPapel(papel);
        u.setLojaId(lojaId);
        u.setAtivo(ativo);
        u.setEmail("u" + id + "@loja.test");
        u.setNome("Pessoa " + id);
        return u;
    }

    /** A entidade, já encontrável por {@code findById}. Nunca dentro de outro {@code when}. */
    private Usuario usuario(long id, Papel papel, Long lojaId, boolean ativo) {
        Usuario u = conta(id, papel, lojaId, ativo);
        when(usuarios.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    // ------------------------------------------------------------- loja sem dono

    @Test
    void desativarOUnicoDonoDaLojaEhRecusado() {
        UsuariosDaPlataformaService s = servico();
        Usuario dono = usuario(9L, Papel.ADMINISTRADOR_LOJA, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(usuarios.donosDaLoja(7L)).thenReturn(List.of(dono)); // só ele

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(9L, false));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("Zirá Centro"),
                "a mensagem tem de dizer QUAL loja ficaria sem dono: " + e.getReason());
        verify(usuarios, never()).save(any());
    }

    @Test
    void desativarUmDonoQuandoHaOutroAtivoPassa() {
        UsuariosDaPlataformaService s = servico();
        Usuario dono = usuario(9L, Papel.ADMINISTRADOR_LOJA, 7L, true);
        Usuario socio = usuario(10L, Papel.ADMINISTRADOR_LOJA, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(usuarios.donosDaLoja(7L)).thenReturn(List.of(dono, socio));

        assertDoesNotThrow(() -> s.definirAtivo(9L, false));

        assertFalse(dono.getAtivo());
        verify(usuarios).save(dono);
    }

    /**
     * Dono de rede tem UMA conta, ligada às outras lojas por {@code usuario_loja}. Se a trava olhasse
     * só a coluna {@code loja_id}, ela deixaria as lojas da rede sem dono sem reclamar.
     */
    @Test
    void aTravaOlhaTambemAsLojasQueVemPorVinculo() {
        UsuariosDaPlataformaService s = servico();
        Usuario donoDeRede = usuario(9L, Papel.ADMINISTRADOR_LOJA, 99L, true); // nasceu em outra loja
        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(9L);
        v.setLojaId(7L);
        Usuario socioDaOutra = conta(11L, Papel.ADMINISTRADOR_LOJA, 99L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of(v));
        when(usuarios.donosDaLoja(99L)).thenReturn(List.of(donoDeRede, socioDaOutra));
        when(usuarios.donosDaLoja(7L)).thenReturn(List.of(donoDeRede)); // só ele, pelo vínculo

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(9L, false));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    /** Conta já inativa não "deixa a loja sem dono": a loja já está sem ela. */
    @Test
    void reativarNaoPassaPelaTravaDeDono() {
        UsuariosDaPlataformaService s = servico();
        Usuario dono = usuario(9L, Papel.ADMINISTRADOR_LOJA, 7L, false);

        assertDoesNotThrow(() -> s.definirAtivo(9L, true));

        assertTrue(dono.getAtivo());
        verify(usuarios, never()).donosDaLoja(any());
    }

    @Test
    void excluirOUnicoDonoDaLojaEhRecusado() {
        UsuariosDaPlataformaService s = servico();
        Usuario dono = usuario(9L, Papel.ADMINISTRADOR_LOJA, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(usuarios.donosDaLoja(7L)).thenReturn(List.of(dono));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> s.excluir(9L));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(usuarios, never()).delete(any());
        verify(vinculos, never()).deleteAll(any());
    }

    // ------------------------------------------------------------- a própria conta

    @Test
    void naoDesativaASiMesmo() {
        UsuariosDaPlataformaService s = servico();
        usuario(EU, Papel.ADMINISTRADOR_BORA, null, true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(EU, false));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(usuarios, never()).save(any());
    }

    @Test
    void naoExcluiASiMesmo() {
        UsuariosDaPlataformaService s = servico();
        usuario(EU, Papel.ADMINISTRADOR_BORA, null, true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> s.excluir(EU));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(usuarios, never()).delete(any());
    }

    // ------------------------------------------------------------- último da plataforma

    @Test
    void naoDerrubaOUltimoAdminDaPlataforma() {
        UsuariosDaPlataformaService s = servico();
        Usuario outroMaster = usuario(2L, Papel.ADMINISTRADOR_BORA, null, true);
        // Eu (id 1) estou INATIVO neste cenário: sobraria zero ativo.
        Usuario euInativo = new Usuario();
        euInativo.setId(EU);
        euInativo.setPapel(Papel.ADMINISTRADOR_BORA);
        euInativo.setAtivo(false);
        when(usuarios.findByPapelParaAtualizar(Papel.ADMINISTRADOR_BORA))
                .thenReturn(List.of(euInativo, outroMaster));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(2L, false));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("último administrador ativo"), e.getReason());
        verify(usuarios, never()).save(any());
    }

    /**
     * A contagem tem de sair de {@code findByPapelParaAtualizar} (o {@code SELECT ... FOR UPDATE}) e
     * não de uma leitura solta: sem o lock, duas chamadas simultâneas leem "o outro ainda está
     * ativo", as duas passam, e a plataforma fica sem dono.
     */
    @Test
    void aContagemDeAdminsUsaALeituraTravada() {
        UsuariosDaPlataformaService s = servico();
        Usuario alvo = usuario(2L, Papel.ADMINISTRADOR_BORA, null, true);
        Usuario euAtivo = conta(EU, Papel.ADMINISTRADOR_BORA, null, true);
        when(usuarios.findByPapelParaAtualizar(Papel.ADMINISTRADOR_BORA))
                .thenReturn(List.of(euAtivo, alvo));

        assertDoesNotThrow(() -> s.definirAtivo(2L, false));

        verify(usuarios).findByPapelParaAtualizar(Papel.ADMINISTRADOR_BORA);
        verify(usuarios, never()).findByPapel(Papel.ADMINISTRADOR_BORA);
    }

    // ------------------------------------------------------------- exclusão

    /**
     * A FK {@code usuario_loja.usuario_id} não tem {@code ON DELETE}. Apagar o usuário primeiro
     * estoura violação de integridade e, no Postgres, derruba a transação inteira.
     */
    @Test
    void excluirApagaOsVinculosANTESDoUsuario() {
        UsuariosDaPlataformaService s = servico();
        Usuario operador = usuario(9L, Papel.OPERADOR, 7L, true);
        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(9L);
        v.setLojaId(7L);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of(v));
        when(logs.countByUsuarioId(9L)).thenReturn(0L);

        Map<String, Object> r = s.excluir(9L);

        InOrder ordem = inOrder(vinculos, usuarios);
        ordem.verify(vinculos).deleteAll(List.of(v));
        ordem.verify(usuarios).delete(operador);
        assertEquals(1, r.get("vinculosRemovidos"));
        assertEquals(true, r.get("excluido"));
    }

    /**
     * O histórico do pedido é do lojista, não do usuário: excluir a conta não pode apagar linha de
     * {@code log_status}. O id fica órfão — e é isso que a prévia avisa.
     */
    @Test
    void excluirNaoApagaOHistoricoDoPedido() {
        UsuariosDaPlataformaService s = servico();
        usuario(9L, Papel.OPERADOR, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(logs.countByUsuarioId(9L)).thenReturn(7L);

        Map<String, Object> r = s.excluir(9L);

        assertEquals(7L, r.get("linhasDeHistoricoSemNome"));
        verify(logs, only()).countByUsuarioId(9L); // conta, e nada mais
    }

    @Test
    void previaContaOHistoricoERecomendaDesativar() {
        UsuariosDaPlataformaService s = servico();
        usuario(9L, Papel.OPERADOR, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(vinculos.countByUsuarioId(9L)).thenReturn(1L);
        when(logs.countByUsuarioId(9L)).thenReturn(7L);

        Map<String, Object> p = s.previaDaExclusao(9L);

        assertEquals(7L, p.get("linhasDeHistoricoQuePerdemONome"));
        assertEquals(true, p.get("podeExcluir"));
        assertTrue(String.valueOf(p.get("recomendacao")).contains("DESATIVAR"),
                "a prévia tem de oferecer o caminho reversível: " + p.get("recomendacao"));
        verify(usuarios, never()).delete(any()); // prévia não altera nada
        verify(vinculos, never()).deleteAll(any());
    }

    /**
     * A PRÉVIA NÃO PODE USAR A CONSULTA COM {@code FOR UPDATE}.
     *
     * <p><b>Defeito real, achado pelo dono em produção em 17/11/2026</b>, depois de a bateria
     * inteira passar: clicar em Excluir num ADMINISTRADOR_BORA devolvia <i>"Query requires
     * transaction be in progress, but no transaction is known to be in progress"</i>. A prévia não
     * é {@code @Transactional}, e consulta com trava pessimista exige transação aberta.</p>
     *
     * <p><b>Por que a bateria não pegou:</b> aquela trava só executa quando o alvo é
     * {@code ADMINISTRADOR_BORA} — os outros papéis saem da função antes. Todos os cenários de
     * prévia que eu tinha escrito usavam operador ou admin de loja. O caminho existia e nunca foi
     * pisado.</p>
     *
     * <p><b>O que este teste prova, e o que não prova:</b> prova que a prévia pede a consulta SEM
     * trava — ou seja, pega quem trocar uma pela outra. Não prova a falha do Hibernate em si, que é
     * comportamento de transação e só aparece contra um banco de verdade.</p>
     */
    @Test
    void previaDeAdminDaPlataformaNaoUsaConsultaComTrava() {
        UsuariosDaPlataformaService s = servico();
        Usuario alvo = usuario(2L, Papel.ADMINISTRADOR_BORA, null, true);
        Usuario euAtivo = conta(EU, Papel.ADMINISTRADOR_BORA, null, true);
        when(usuarios.findByPapel(Papel.ADMINISTRADOR_BORA)).thenReturn(List.of(euAtivo, alvo));

        Map<String, Object> p = s.previaDaExclusao(2L);

        assertEquals(true, p.get("podeExcluir"));
        verify(usuarios).findByPapel(Papel.ADMINISTRADOR_BORA);
        verify(usuarios, never()).findByPapelParaAtualizar(any());
    }

    /** E a regra continua valendo pelo caminho sem trava: último ativo não some nem na prévia. */
    @Test
    void previaAcusaOUltimoAdminDaPlataforma() {
        UsuariosDaPlataformaService s = servico();
        Usuario alvo = usuario(2L, Papel.ADMINISTRADOR_BORA, null, true);
        Usuario euInativo = conta(EU, Papel.ADMINISTRADOR_BORA, null, false);
        when(usuarios.findByPapel(Papel.ADMINISTRADOR_BORA)).thenReturn(List.of(euInativo, alvo));

        Map<String, Object> p = s.previaDaExclusao(2L);

        assertEquals(false, p.get("podeExcluir"));
        @SuppressWarnings("unchecked")
        List<String> impedimentos = (List<String>) p.get("impedimentos");
        assertTrue(impedimentos.get(0).contains("último administrador ativo"), impedimentos.get(0));
    }

    @Test
    void previaDizPorqueNaoPodeQuandoATravaPega() {
        UsuariosDaPlataformaService s = servico();
        Usuario dono = usuario(9L, Papel.ADMINISTRADOR_LOJA, 7L, true);
        when(vinculos.findByUsuarioId(9L)).thenReturn(List.of());
        when(usuarios.donosDaLoja(7L)).thenReturn(List.of(dono));

        Map<String, Object> p = s.previaDaExclusao(9L);

        assertEquals(false, p.get("podeExcluir"));
        @SuppressWarnings("unchecked")
        List<String> impedimentos = (List<String>) p.get("impedimentos");
        assertEquals(1, impedimentos.size());
        assertTrue(impedimentos.get(0).contains("Zirá Centro"), impedimentos.get(0));
    }

    // ------------------------------------------------------------- criar

    @Test
    void emailQueJaExisteEhRecusadoNuncaPromovido() {
        UsuariosDaPlataformaService s = servico();
        Usuario jaExiste = conta(50L, Papel.OPERADOR, 7L, true);
        when(usuarios.findByEmail("ja@existe.test")).thenReturn(Optional.of(jaExiste));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar("Clone", "ja@existe.test", "senha-longa-o-bastante",
                        "ADMINISTRADOR_BORA", null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(usuarios, never()).save(any());
    }

    @Test
    void contaDeLojaSemLojaEhRecusada() {
        UsuariosDaPlataformaService s = servico();
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar("Alguém", "novo@loja.test", "senha-longa-o-bastante", "GERENTE", null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(usuarios, never()).save(any());
    }

    @Test
    void contaDePlataformaComLojaEhRecusada() {
        UsuariosDaPlataformaService s = servico();
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar("Alguém", "novo@bora.app", "senha-longa-o-bastante",
                        "ADMINISTRADOR_BORA", 7L));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(usuarios, never()).save(any());
    }

    @Test
    void senhaCurtaEhRecusadaAntesDeOlharOBanco() {
        UsuariosDaPlataformaService s = servico();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar("Alguém", "novo@loja.test", "1234", "GERENTE", 7L));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(usuarios, never()).findByEmail(anyString());
    }

    /** O vínculo é o que faz a pessoa aparecer na tela da loja e contar no limite do plano. */
    @Test
    void criarEmLojaGravaOVinculo() {
        UsuariosDaPlataformaService s = servico();
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("hash");
        when(lojas.findById(7L)).thenReturn(Optional.of(new Loja()));
        when(usuarios.save(any())).thenAnswer(inv -> {
            Usuario u = inv.getArgument(0);
            u.setId(60L);
            return u;
        });
        when(usuarios.contarAtivosDaLoja(7L)).thenReturn(3L);
        when(planos.plano(7L)).thenReturn(br.com.bora.entity.Plano.UNICO);

        Map<String, Object> r = s.criar("Novo", "NOVO@Loja.test ", "senha-longa-o-bastante",
                "GERENTE", 7L);

        assertEquals("novo@loja.test", r.get("email"), "o e-mail tem de ser normalizado: o login "
                + "procura por minúscula e sem espaço, e gravar diferente tranca a pessoa fora");
        verify(vinculos).save(any(UsuarioLoja.class));
        assertNull(r.get("aviso"), "3 de 15 não passa do teto do plano");
    }

    /** Passar do teto do plano não bloqueia o master — mas não pode passar calado. */
    @Test
    void passarDoTetoDoPlanoAvisaSemBloquear() {
        UsuariosDaPlataformaService s = servico();
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("hash");
        when(lojas.findById(7L)).thenReturn(Optional.of(new Loja()));
        when(usuarios.save(any())).thenAnswer(inv -> {
            Usuario u = inv.getArgument(0);
            u.setId(61L);
            return u;
        });
        when(usuarios.contarAtivosDaLoja(7L)).thenReturn(16L); // teto do plano único é 15
        when(planos.plano(7L)).thenReturn(br.com.bora.entity.Plano.UNICO);

        Map<String, Object> r = s.criar("Novo", "novo2@loja.test", "senha-longa-o-bastante",
                "OPERADOR", 7L);

        assertNotNull(r.get("aviso"), "passar do teto tem de aparecer, senão é furo de receita calado");
        assertTrue(String.valueOf(r.get("aviso")).contains("16"), String.valueOf(r.get("aviso")));
    }

    // ------------------------------------------------------------- autorização

    @Test
    void soAdminDaPlataformaAlcancaQualquerUmDosCaminhos() {
        UsuariosDaPlataformaService s = servico();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(ctx).requireAdminBora();

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> s.listar(null, null, false)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> s.criar("a", "a@a.test", "senha-longa-o-bastante", "OPERADOR", 7L)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(9L, false)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> s.previaDaExclusao(9L)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> s.excluir(9L)).getStatusCode());
    }

    // ------------------------------------------------------------- listagem

    /**
     * Conta de plataforma tem {@code loja_id} nulo e é exatamente a que não aparece em tela de loja
     * nenhuma. Se a listagem do master também a escondesse, a tela não teria razão de existir.
     */
    @Test
    void aListagemMostraAContaSemLojaEOsInativos() {
        UsuariosDaPlataformaService s = servico();
        Usuario master = new Usuario();
        master.setId(1L); master.setPapel(Papel.ADMINISTRADOR_BORA);
        master.setAtivo(true); master.setEmail("m@bora.app"); master.setNome("Master");
        Usuario desativado = new Usuario();
        desativado.setId(2L); desativado.setPapel(Papel.OPERADOR); desativado.setLojaId(7L);
        desativado.setAtivo(false); desativado.setEmail("x@loja.test"); desativado.setNome("Antigo");
        when(usuarios.findAll()).thenReturn(List.of(master, desativado));
        when(vinculos.findAll()).thenReturn(List.of());

        List<Map<String, Object>> tudo = s.listar(null, null, false);
        assertEquals(2, tudo.size());
        assertNull(tudo.get(0).get("lojaId"), "a conta de plataforma vem primeiro e sem loja");
        assertEquals("Zirá Centro", tudo.get(1).get("lojaNome"));

        assertEquals(1, s.listar(null, null, true).size(), "ativos=true esconde o desativado");
        assertEquals(1, s.listar("antigo", null, false).size(), "a busca pega pelo nome");
        assertEquals(1, s.listar("x@loja", null, false).size(), "e pelo e-mail");
    }
}
