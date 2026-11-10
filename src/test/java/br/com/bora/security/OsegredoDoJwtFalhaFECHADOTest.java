package br.com.bora.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sem um segredo de verdade, a API não sobe.
 *
 * <h2>O defeito que isto fecha (confirmado em 10/11/2026)</h2>
 * <p>Faltando a variável, o {@code JwtService} <b>deixava a aplicação subir</b> com o segredo
 * {@code troque-este-segredo-em-producao-...} — um valor <b>publicado no repositório</b> — e apenas
 * escrevia um {@code log.warn}. Quem conhecesse o repositório forjaria um token de qualquer usuário,
 * inclusive do admin da plataforma: o {@code validar()} aceita qualquer coisa assinada com essa
 * chave. Aviso em log não protege nada — ele some na primeira página de log e a API segue atendendo.</p>
 *
 * <p><b>E neste projeto isso não é teórico:</b> em 30/10/2026 a chave de cifragem das colunas pareceu
 * ausente porque {@code docker restart} <b>não relê o {@code --env-file}</b>. A mesma situação com o
 * JWT teria passado em silêncio, com a API no ar assinando token com a chave pública.</p>
 *
 * <p>Falhar fechado já era o padrão do resto do sistema — o {@code SegredoGuardado} recusa a subida
 * quando há credencial cifrada no banco e a chave não está definida. O JWT era a exceção.</p>
 */
class OsegredoDoJwtFalhaFECHADOTest {

    private static final String BOM = "um-segredo-de-verdade-com-mais-de-32-bytes-aqui";

    @Test
    void segredoAUSENTEnaoDEIXAsubir() {
        var e = assertThrows(IllegalStateException.class,
                () -> new JwtService("", 86_400_000L));
        assertTrue(e.getMessage().contains("BORA_JWT_SECRET"),
                "a mensagem tem de dizer QUAL variavel falta: " + e.getMessage());
        assertThrows(IllegalStateException.class, () -> new JwtService(null, 86_400_000L));
        assertThrows(IllegalStateException.class, () -> new JwtService("   ", 86_400_000L));
    }

    @Test
    void oSEGREDOpublicadoNOrepositorioNAOdeixaSUBIR() {
        // Era exatamente este o valor que a aplicacao usava sozinha, e com ele no ar qualquer pessoa
        // com acesso ao repositorio assina um token de admin da plataforma.
        var e = assertThrows(IllegalStateException.class, () -> new JwtService(
                "troque-este-segredo-em-producao-com-no-minimo-32-bytes!!", 86_400_000L));
        assertTrue(e.getMessage().contains("publicado no repositorio"), e.getMessage());

        // E qualquer variacao do mesmo valor de exemplo.
        assertThrows(IllegalStateException.class,
                () -> new JwtService("troque-isto-por-favor-antes-de-ir-para-producao", 86_400_000L));
    }

    @Test
    void segredoCURTOnaoDEIXAsubir() {
        // Abaixo de 32 bytes o HMAC-SHA256 nao se sustenta. A biblioteca ja recusava, mas com uma
        // mensagem que nao diz a quem le o que fazer.
        var e = assertThrows(IllegalStateException.class,
                () -> new JwtService("curto-demais", 86_400_000L));
        assertTrue(e.getMessage().contains("32"), e.getMessage());
    }

    @Test
    void aMENSAGEMnuncaIMPRIMEoVALOR() {
        // Regra deste projeto: mensagem e log falam o NOME da variavel, nunca o valor. Quem derruba a
        // subida com um segredo errado nao pode vazar o segredo errado no log, porque muitas vezes
        // ele e o certo de outro ambiente.
        String comCaraDeSegredo = "troque-este-segredo-em-producao-com-no-minimo-32-bytes!!";
        var e = assertThrows(IllegalStateException.class,
                () -> new JwtService(comCaraDeSegredo, 86_400_000L));
        assertFalse(e.getMessage().contains(comCaraDeSegredo),
                "a mensagem NAO pode conter o valor: " + e.getMessage());

        var curto = assertThrows(IllegalStateException.class,
                () -> new JwtService("abc123", 86_400_000L));
        assertFalse(curto.getMessage().contains("abc123"),
                "a mensagem NAO pode conter o valor: " + curto.getMessage());
    }

    @Test
    void segredoBOMsobeEoTOKENvaleNELE() {
        JwtService jwt = assertDoesNotThrow(() -> new JwtService(BOM, 86_400_000L));
        assertNotNull(jwt);
    }

    @Test
    void oLINKdeREPETIRpedidoSEGUEaMESMAregra() {
        // O RepetirPedidoService deriva a chave do MESMO segredo, e tambem so avisava no log. A regra
        // mora num lugar so para as duas nao divergirem.
        assertThrows(IllegalStateException.class, () -> JwtService.exigirSegredoDeVerdade(""));
        assertThrows(IllegalStateException.class,
                () -> JwtService.exigirSegredoDeVerdade("troque-este-segredo-em-producao-com-no-minimo-32-bytes!!"));
        assertEquals(BOM, JwtService.exigirSegredoDeVerdade(BOM));
        assertEquals(BOM, JwtService.exigirSegredoDeVerdade("  " + BOM + "  "),
                "espaco em volta de variavel de ambiente e comum, e nao e motivo para recusar");
    }
}
