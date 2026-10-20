package br.com.bora.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O freio não pode deixar o atacante escolher a própria chave.
 *
 * <h2>Correção de um erro meu, registrada aqui de propósito</h2>
 * <p>Em 18/10/2026 eu afirmei que isto era uma falha aberta em produção. <b>Não era.</b> Eu tinha
 * medido que o {@code X-Forwarded-For} forjado chegava ao <b>Caddy</b> (vi no log dele) e concluí,
 * sem medir, que o Caddy repassava. Em 19/10 subi um caddy:2.11.4 — a mesma versão da produção — com
 * {@code reverse_proxy} e sem {@code trusted_proxies}, e medi o que a aplicação recebe:</p>
 * <pre>
 *   curl -H "X-Forwarded-For: 1.2.3.4"              -&gt; app recebeu '172.18.0.1'
 *   curl -H "X-Forwarded-For: 203.0.113.99, 9.9.9.9" -&gt; app recebeu '172.18.0.1'
 * </pre>
 * <p>O Caddy <b>descarta</b> o que o cliente manda e escreve só o IP que ele viu no socket. A lista
 * chega com um item, e ele é confiável. O freio sempre funcionou.</p>
 *
 * <h2>Então por que estes testes existem</h2>
 * <p>Porque a garantia vem da <b>configuração do proxy</b>, não do código — e configuração muda.
 * Um {@code trusted_proxies} no Caddyfile, um CDN na frente, ou a troca do proxy fazem a lista passar
 * a conter o valor do cliente. Nesse dia, ler o primeiro item transforma cada chamada numa chave
 * nova, e o freio deixa de frear: a trava de 5 senhas erradas em 15 minutos do login e a de 10
 * pedidos por minuto do cardápio somem juntas — e cada pedido do cardápio baixa estoque, consome
 * cashback e abre cobrança no Asaas da loja.</p>
 *
 * <p>Estes testes travam o comportamento seguro antes de isso acontecer. Nenhum deles prova um
 * defeito de hoje.</p>
 */
class FreioNaoConfiaNoCabecalhoDoClienteTest {

    private static final String IP_REAL = "177.45.197.230";

    private HttpServletRequest chamada(String xff) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(r.getRemoteAddr()).thenReturn("172.18.0.2");   // o IP do container do Caddy
        return r;
    }

    @Test
    void cabecalhoFORJADOnaoVIRAaChave() {
        // Com o Caddy de hoje esta lista nao chega assim (ele descarta o que o cliente manda).
        // Chegaria com trusted_proxies, com CDN na frente, ou com outro proxy.
        assertEquals(IP_REAL, FreioDeTentativas.origem(chamada("1.2.3.4, " + IP_REAL)));
    }

    @Test
    void trocarOcabecalhoACADAchamadaNaoGeraChaveNOVA() {
        // O estrago, se a topologia mudar, não é "o IP sai errado": é cada tentativa cair num balde
        // diferente, e aí o contador nunca chega a cinco.
        String a = FreioDeTentativas.origem(chamada("9.9.9.9, " + IP_REAL));
        String b = FreioDeTentativas.origem(chamada("8.8.8.8, " + IP_REAL));
        String c = FreioDeTentativas.origem(chamada("evil, " + IP_REAL));
        assertEquals(a, b);
        assertEquals(b, c);
        assertEquals(IP_REAL, a);
    }

    @Test
    void chamadaHONESTAcontinuaSendoSeparadaPorIP() {
        // O motivo de olhar o cabeçalho continua valendo: sem isso, cinco pessoas errando a senha
        // trancariam umas às outras, e um atacante trancaria o lojista de proposito.
        assertNotEquals(FreioDeTentativas.origem(chamada("200.1.1.1")),
                        FreioDeTentativas.origem(chamada("200.2.2.2")));
    }

    @Test
    void semCabecalhoUsaOsocket() {
        assertEquals("172.18.0.2", FreioDeTentativas.origem(chamada(null)));
        assertEquals("172.18.0.2", FreioDeTentativas.origem(chamada("   ")));
    }

    @Test
    void listaComItemVAZIOnoFimNaoViraBaldeUNICO() {
        // "1.2.3.4, , " ou um proxy que escreve vírgula sobrando: cair em "" juntaria o mundo
        // inteiro numa chave só, e aí UM atacante trancaria TODOS os lojistas.
        assertEquals(IP_REAL, FreioDeTentativas.origem(chamada("1.2.3.4, " + IP_REAL + ", ")));
        assertFalse(FreioDeTentativas.origem(chamada("1.2.3.4, , ")).isEmpty());
    }

    @Test
    void oFreioREALMENTEtrancaQuemTrocaOcabecalho() {
        // Prova de ponta a ponta, com o freio de verdade: 6 tentativas trocando o cabeçalho a cada
        // uma, no formato que uma topologia com proxy confiável produziria.
        FreioDeTentativas freio = new FreioDeTentativas();
        for (int i = 0; i < 5; i++) {
            String chave = FreioDeTentativas.chave("lojista@loja.com",
                    FreioDeTentativas.origem(chamada("10.0.0." + i + ", " + IP_REAL)));
            freio.conferir(chave);
            freio.errou(chave);
        }
        String sexta = FreioDeTentativas.chave("lojista@loja.com",
                FreioDeTentativas.origem(chamada("10.0.0.99, " + IP_REAL)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> freio.conferir(sexta),
                "a sexta tentativa tem que ser barrada mesmo com o cabecalho trocado");
    }

    @Test
    void acertarASENHAzeraOplacarDaquelaChave() {
        FreioDeTentativas freio = new FreioDeTentativas();
        String chave = FreioDeTentativas.chave("lojista@loja.com",
                FreioDeTentativas.origem(chamada("1.1.1.1, " + IP_REAL)));
        for (int i = 0; i < 4; i++) { freio.conferir(chave); freio.errou(chave); }
        freio.acertou(chave);
        for (int i = 0; i < 5; i++) { freio.conferir(chave); freio.errou(chave); }
        // Se o acerto nao tivesse zerado, a 5a daqui ja teria estourado.
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> freio.conferir(chave));
    }
}
