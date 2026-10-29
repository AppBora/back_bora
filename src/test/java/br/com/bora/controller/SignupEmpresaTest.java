package br.com.bora.controller;

import br.com.bora.dto.SignupRequest;
import br.com.bora.entity.Empresa;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.EmpresaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.FreioDeTentativas;
import br.com.bora.service.EmpresaService;
import br.com.bora.service.ProvisionamentoService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * O cadastro público não pode deixar um estranho entrar na empresa de um cliente.
 *
 * <p>O caminho que estava aberto: cadastrar uma loja informando o CNPJ de um cliente colocava a loja
 * nova na empresa dele; dali, a rede multi-lojas só confere "é da mesma empresa?" e o trocar-loja
 * entregava o painel da vítima. CNPJ está na nota fiscal e na fachada — não prova posse. Quem prova
 * é a senha de uma conta que já existe.</p>
 */
class SignupEmpresaTest {

    private static final String CNPJ_DA_VITIMA = "53.953.786/0001-28";

    private LojaRepository lojas;
    private UsuarioRepository usuarios;
    private UsuarioLojaRepository vinculos;
    private PasswordEncoder encoder;
    private EmpresaRepository empresasRepo;
    private SignupController controller;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        usuarios = mock(UsuarioRepository.class);
        vinculos = mock(UsuarioLojaRepository.class);
        encoder = mock(PasswordEncoder.class);
        empresasRepo = mock(EmpresaRepository.class);

        EmpresaService empresas = new EmpresaService(empresasRepo, lojas);
        controller = new SignupController(lojas, usuarios, vinculos, encoder,
                mock(ProvisionamentoService.class), empresas, new FreioDeTentativas(),
                new br.com.bora.security.TetoDeCadastros());

        // A empresa da vítima já existe com esse CNPJ.
        Empresa daVitima = new Empresa();
        daVitima.setId(7L);
        daVitima.setRazaoSocial("Zirá Açaíteria");
        daVitima.setCnpj("53953786000128");
        when(empresasRepo.findByCnpj("53953786000128")).thenReturn(Optional.of(daVitima));
        when(empresasRepo.save(any(Empresa.class))).thenAnswer(i -> {
            Empresa e = i.getArgument(0);
            if (e.getId() == null) e.setId(99L);
            return e;
        });
        when(lojas.save(any(Loja.class))).thenAnswer(i -> {
            Loja l = i.getArgument(0);
            if (l.id == null) l.id = 500L;
            return l;
        });
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            if (u.getId() == null) u.setId(300L);
            return u;
        });
    }

    private HttpServletRequest origem() {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn("203.0.113.9");
        return r;
    }

    @Test
    void estranhoComOCnpjDeOutraLojaEhRecusado() {
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty()); // conta nova

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.cadastrar(new SignupRequest("Loja do Golpista", CNPJ_DA_VITIMA,
                        "Golpista", "golpista@teste.local", "senha-de-oito", true), origem()));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(lojas, never()).save(any(Loja.class));
        verify(usuarios, never()).save(any(Usuario.class));
    }

    /** Conta que acerta a senha E já tem loja naquela empresa: é o dono de rede de verdade. */
    private Usuario contaQueAcertaASenha(boolean comVinculoNaEmpresaDaVitima) {
        Usuario dono = new Usuario();
        dono.setId(42L);
        dono.setEmail("dono@zira.local");
        dono.setSenhaHash("hash");
        dono.setAtivo(true);
        dono.setPapel(Papel.ADMINISTRADOR_LOJA);
        when(usuarios.findByEmail("dono@zira.local")).thenReturn(Optional.of(dono));
        when(encoder.matches("senha-de-oito", "hash")).thenReturn(true);

        Loja daVitima = new Loja();
        daVitima.id = 18L;
        daVitima.empresaId = 7L;
        when(lojas.findByEmpresaIdOrderByIdAsc(7L)).thenReturn(java.util.List.of(daVitima));
        when(vinculos.existsByUsuarioIdAndLojaId(42L, 18L)).thenReturn(comVinculoNaEmpresaDaVitima);
        return dono;
    }

    @Test
    void donoQueProvaASenhaPodeAbrirOutraLojaNaMesmaEmpresa() {
        contaQueAcertaASenha(true); // já tem loja nessa empresa

        Map<String, Object> r = controller.cadastrar(new SignupRequest("Zirá Centro", CNPJ_DA_VITIMA,
                "Dono", "dono@zira.local", "senha-de-oito", true), origem());

        assertEquals(true, r.get("vinculada"));
        verify(lojas).save(any(Loja.class));
        verify(empresasRepo, never()).save(any(Empresa.class)); // entrou na empresa que já existia
    }

    /**
     * O buraco que a primeira correção deixou aberto.
     *
     * <p>A senha prova que a CONTA é de quem cadastra — não prova nenhuma relação com o CNPJ digitado.
     * Bastava abrir uma loja qualquer (30 segundos), recadastrar com a MESMA conta informando o CNPJ de
     * um cliente, e a loja nova nascia dentro da empresa dele. Dali, pelos vínculos da rede, o painel do
     * cliente ficava a dois cliques. CNPJ está na nota fiscal e na fachada: não é segredo.</p>
     */
    @Test
    void contaPropriaComOCnpjDeOutraEmpresa_ehRecusadaComoQualquerEstranho() {
        contaQueAcertaASenha(false); // conta de verdade, mas sem nenhuma loja na empresa da vítima

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.cadastrar(new SignupRequest("Loja do Golpista", CNPJ_DA_VITIMA,
                        "Golpista", "dono@zira.local", "senha-de-oito", true), origem()));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(lojas, never()).save(any(Loja.class));
        verify(vinculos, never()).save(any(br.com.bora.entity.UsuarioLoja.class));
    }

    @Test
    void cnpjInedito_segueCriandoNormalmente() {
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(empresasRepo.findByCnpj("11222333000199")).thenReturn(Optional.empty());

        Map<String, Object> r = controller.cadastrar(new SignupRequest("Pizzaria Nova", "11.222.333/0001-99",
                "Dona", "dona@nova.local", "senha-de-oito", true), origem());

        assertEquals(500L, r.get("lojaId"));
        verify(empresasRepo).save(any(Empresa.class));
    }

    @Test
    void semAceitarOsTermos_naoCriaConta() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.cadastrar(new SignupRequest("Loja nova", "11.222.333/0001-99",
                        "Dona", "dona@nova.local", "senha-de-oito", false), origem()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(lojas, never()).save(any(Loja.class));
    }

    @Test
    void aceiteFicaRegistradoComDataEVersao() {
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(empresasRepo.findByCnpj("11222333000199")).thenReturn(Optional.empty());
        var captor = org.mockito.ArgumentCaptor.forClass(Loja.class);

        controller.cadastrar(new SignupRequest("Pizzaria Nova", "11.222.333/0001-99",
                "Dona", "dona@nova.local", "senha-de-oito", true), origem());

        verify(lojas, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        Loja criada = captor.getAllValues().get(0);
        assertNotNull(criada.termosAceitosEm, "sem data, nao ha prova de que alguem aceitou");
        assertNotNull(criada.termosVersao, "sem versao, ninguem sabe a que texto ele disse sim");
        assertEquals("203.0.113.9", criada.termosAceitosDe);
    }

    @Test
    void senhaCurtaEhRecusadaAntesDeQualquerCoisa() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.cadastrar(new SignupRequest("Loja", "11.222.333/0001-99",
                        "Alguem", "alguem@teste.local", "1234567", true), origem()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains("8"), e.getReason());
    }

    // --------------------------------------------- o teto de lojas criadas por origem

    /**
     * O endpoint nao tinha teto nenhum por origem (pente fino de 29/10/2026).
     *
     * <p>A unica trava era o {@code FreioDeTentativas}, que conta tentativa ERRADA -- e o caminho
     * que cria loja nova (e-mail que ainda nao existe) nunca erra, entao o {@code errou} nunca era
     * chamado. E a chave do freio inclui o e-mail: mudar o e-mail a cada chamada ja gerava chave
     * nova de qualquer jeito. Qualquer um criava lojas em sequencia, sem limite.</p>
     */
    @Test
    void aSEXTAlojaDAmesmaORIGEMnaHORAeBARRADA() {
        br.com.bora.security.TetoDeCadastros teto = new br.com.bora.security.TetoDeCadastros();
        final String daMesmaMaquina = "203.0.113.7";

        for (int i = 1; i <= 5; i++) {
            teto.conferir(daMesmaMaquina);   // as cinco primeiras passam
            teto.criou(daMesmaMaquina);
        }

        var e = assertThrows(ResponseStatusException.class, () -> teto.conferir(daMesmaMaquina));
        assertEquals(429, e.getStatusCode().value());
    }

    @Test
    void oDONOdeREDEcriaAScincoLOJASdeleNUMAsentada() {
        // O caso legitimo mais pesado que existe. Barrar a terceira tiraria do ar um cliente de
        // verdade para conter um abuso que ninguem viu.
        br.com.bora.security.TetoDeCadastros teto = new br.com.bora.security.TetoDeCadastros();

        for (int i = 1; i <= 5; i++) {
            teto.conferir("198.51.100.4");
            teto.criou("198.51.100.4");
        }
        // nenhuma excecao ate aqui
    }

    @Test
    void oCADASTROrecusadoNAOconta() {
        // So `criou` incrementa. Quem tentou e levou 409 (CNPJ ja usado) nao gastou a cota: senao
        // bastaria errar cinco vezes para o lojista de verdade ficar sem conseguir cadastrar.
        br.com.bora.security.TetoDeCadastros teto = new br.com.bora.security.TetoDeCadastros();

        for (int i = 1; i <= 20; i++) teto.conferir("192.0.2.9");
        teto.conferir("192.0.2.9");
        // nenhuma excecao: conferir sozinho nao conta nada
    }

    @Test
    void origensDIFERENTEStemCOTASseparadas() {
        br.com.bora.security.TetoDeCadastros teto = new br.com.bora.security.TetoDeCadastros();
        for (int i = 1; i <= 5; i++) { teto.conferir("203.0.113.1"); teto.criou("203.0.113.1"); }

        teto.conferir("203.0.113.2");   // outro lugar, outra cota

        assertThrows(ResponseStatusException.class, () -> teto.conferir("203.0.113.1"));
    }
}
