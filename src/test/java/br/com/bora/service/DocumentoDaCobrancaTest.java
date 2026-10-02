package br.com.bora.service;

import br.com.bora.entity.Loja;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O documento que vai para a cobrança não pode depender só do que alguém digita.
 *
 * <p>A tela de planos pede o CPF/CNPJ num campo vazio. Em branco, o cliente nascia no Asaas sem
 * documento; errado, nascia errado — foi assim que uma loja foi parar lá com CNPJ de teste. O cadastro
 * da loja já tem o documento certo.</p>
 */
class DocumentoDaCobrancaTest {

    private Loja loja(String documento) {
        Loja l = new Loja();
        l.id = 18L;
        l.nome = "Zirá Açaíteria";
        l.documento = documento;
        return l;
    }

    @Test
    void campoEmBranco_usaOcnpjDoCadastro() {
        assertEquals("53953786000128",
                AssinaturaService.documentoDaCobranca(loja("53953786000128"), null));
        assertEquals("53953786000128",
                AssinaturaService.documentoDaCobranca(loja("53953786000128"), "   "));
    }

    @Test
    void quemInformaManda_oResponsavelPodeSerOutraPessoa() {
        assertEquals("12345678909",
                AssinaturaService.documentoDaCobranca(loja("53953786000128"), " 12345678909 "));
    }

    @Test
    void semDocumentoEmLugarNenhum_recusaEmVezDeCriarClienteVazio() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> AssinaturaService.documentoDaCobranca(loja(null), null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());

        assertThrows(ResponseStatusException.class,
                () -> AssinaturaService.documentoDaCobranca(loja("  "), ""));
    }
}
