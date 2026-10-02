package br.com.bora.controller;

import br.com.bora.dto.AssinaturaView;
import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Plano;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Loja que nunca assinou precisa conseguir assinar.
 *
 * <p>Este endpoint devolvia null quando não havia assinatura, o que vira <b>200 com corpo vazio</b>.
 * O navegador estourava ao ler aquilo como JSON, a tela de planos morria no erro e o botão "Ativar
 * assinatura" nunca era desenhado — exatamente o estado em que as lojas da Zirá estavam.</p>
 */
class AssinaturaStatusTest {

    private AssinaturaController controller(Assinatura retorno) {
        AssinaturaService service = mock(AssinaturaService.class);
        when(service.status()).thenReturn(retorno);
        return new AssinaturaController(service);
    }

    @Test
    void semAssinatura_responde204_eNao200Vazio() {
        ResponseEntity<AssinaturaView> r = controller(null).status();

        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode(),
                "200 com corpo vazio e o que quebrava a tela de planos");
        assertNull(r.getBody());
    }

    @Test
    void comAssinatura_respondeNormalmente() {
        Assinatura a = new Assinatura();
        a.setId(7L);
        a.setPlano(Plano.UNICO);
        a.setStatus(StatusAssinatura.ATIVA);
        a.setValor(new BigDecimal("199.00"));

        ResponseEntity<AssinaturaView> r = controller(a).status();

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertNotNull(r.getBody());
        assertEquals("ATIVA", r.getBody().status());
        assertEquals(0, new BigDecimal("199.00").compareTo(r.getBody().valor()));
    }
}
