package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mensalidade que ficou com o valor errado é acertada por uma rotina.
 *
 * <h2>Item 3.8 da varredura</h2>
 * <p>Ligar o Módulo IA grava {@code loja.moduloIa = true} e <b>depois</b> tenta levar o valor novo ao
 * Asaas. Se a sincronização falhar — Asaas fora do ar, token expirado —, o recurso fica liberado e a
 * mensalidade continua no valor antigo: <b>R$ 99 por mês de graça</b>. A tela diz a verdade no momento
 * do clique (ela devolve {@code situacaoCobranca}), mas ninguém volta para conferir depois, e não
 * havia retry, fila, nem rotina que comparasse.</p>
 *
 * <p>Vale para os dois lados: desligar o módulo e falhar a sincronização deixa o cliente
 * <b>pagando</b> por algo que já foi desligado.</p>
 *
 * <p>A rotina chama o mesmo {@code sincronizarValorComMotivo} que o clique chamaria — leva ao Asaas o
 * preço que a loja já tem cadastrado. Não inventa valor, não cria cobrança, e só age quando há
 * diferença.</p>
 */
class MensalidadeErradaEhAcertadaTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AssinaturaService service;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        service = spy(new AssinaturaService(repo, lojas,
                mock(br.com.bora.repository.UsuarioRepository.class), mock(AsaasClient.class),
                mock(br.com.bora.security.AuthContext.class),
                mock(PagamentoAssinaturaRepository.class), 10));
    }

    /** Uma loja com o preço que ela deveria pagar, e a assinatura com o valor que está cobrando. */
    private Loja loja(Long id, String precoDaLoja, String valorCobrado) {
        Loja l = new Loja();
        l.id = id;
        l.setNome("Loja " + id);
        l.precoMensal = precoDaLoja == null ? null : new BigDecimal(precoDaLoja);
        l.moduloIa = false;

        Assinatura a = new Assinatura();
        a.setId(id);
        a.setLojaId(id);
        a.setStatus(StatusAssinatura.ATIVA);
        a.setValor(valorCobrado == null ? null : new BigDecimal(valorCobrado));
        when(repo.findByLojaId(id)).thenReturn(Optional.of(a));
        return l;
    }

    // =============================================================== acerta quem esta errado

    @Test
    void aLOJAcomVALORerradoEHacertada() {
        // Ela deveria pagar 298 (com o modulo) e esta cobrando 199: o modulo esta de graca.
        Loja l = loja(18L, "298.00", "199.00");
        when(lojas.findAll()).thenReturn(List.of(l));
        doReturn("ATUALIZADA").when(service).sincronizarValorComMotivo(18L);

        service.reconciliarValorDaMensalidade();

        verify(service).sincronizarValorComMotivo(18L);
    }

    @Test
    void aLOJAemDIAnaoEHtocada() {
        // Rotina que mexe em cobranca de quem esta em dia e pior que rotina que nao existe.
        Loja l = loja(18L, "199.00", "199.00");
        when(lojas.findAll()).thenReturn(List.of(l));

        service.reconciliarValorDaMensalidade();

        verify(service, never()).sincronizarValorComMotivo(anyLong());
    }

    @Test
    void oOUTROlado_oCLIENTEpagandoDEMAIS_tambemEHacertado() {
        // Desligou o modulo e a sincronizacao falhou: ele segue pagando os R$ 99.
        Loja l = loja(18L, "199.00", "298.00");
        when(lojas.findAll()).thenReturn(List.of(l));
        doReturn("ATUALIZADA").when(service).sincronizarValorComMotivo(18L);

        service.reconciliarValorDaMensalidade();

        verify(service).sincronizarValorComMotivo(18L);
    }

    @Test
    void lojaSEMassinaturaNAOeTOCADA() {
        Loja l = new Loja();
        l.id = 20L;
        l.precoMensal = new BigDecimal("199.00");
        when(repo.findByLojaId(20L)).thenReturn(Optional.empty());
        when(lojas.findAll()).thenReturn(List.of(l));

        service.reconciliarValorDaMensalidade();

        verify(service, never()).sincronizarValorComMotivo(anyLong());
    }

    @Test
    void assinaturaSEMvalorNAOeTOCADA() {
        // Sem valor nao ha com o que comparar -- e chutar seria mexer em cobranca no escuro.
        Loja l = loja(21L, "199.00", null);
        when(lojas.findAll()).thenReturn(List.of(l));

        service.reconciliarValorDaMensalidade();

        verify(service, never()).sincronizarValorComMotivo(anyLong());
    }

    @Test
    void lojaARQUIVADAnaoEHtocada() {
        // Loja fora do ar nao precisa ter a cobranca acertada -- e mexer nela confundiria o relatorio.
        Loja l = loja(22L, "298.00", "199.00");
        l.excluidaEm = java.time.OffsetDateTime.now().minusDays(10);
        when(lojas.findAll()).thenReturn(List.of(l));

        service.reconciliarValorDaMensalidade();

        verify(service, never()).sincronizarValorComMotivo(anyLong());
    }

    @Test
    void aFALHAnaoDERRUBAasOUTRASlojas() {
        // Uma loja com problema nao pode impedir o acerto das outras: a rotina roda uma vez por hora.
        Loja a = loja(18L, "298.00", "199.00");
        Loja b = loja(19L, "298.00", "199.00");
        when(lojas.findAll()).thenReturn(List.of(a, b));
        doReturn("ASAAS_INDISPONIVEL").when(service).sincronizarValorComMotivo(18L);
        doReturn("ATUALIZADA").when(service).sincronizarValorComMotivo(19L);

        assertDoesNotThrow(() -> service.reconciliarValorDaMensalidade());

        verify(service).sincronizarValorComMotivo(19L);
    }

    // =============================================================== a rotina existe e cabe no pool

    @Test
    void aROTINAehAGENDADA() throws IOException {
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/service/AssinaturaService.java"), StandardCharsets.UTF_8);
        int i = fonte.indexOf("public void reconciliarValorDaMensalidade()");
        assertTrue(i > 0);

        assertTrue(fonte.substring(Math.max(0, i - 300), i).contains("@org.springframework.scheduling.annotation.Scheduled"),
                "sem agendamento, o conserto do 3.8 nunca roda");
    }

    @Test
    void oPOOLdoAGENDADORcabeTODASasROTINAS() throws IOException {
        // Item 9.1 da varredura: eram 5 rotinas em 4 threads, e a quinta ficava na fila -- a que podia
        // ficar e a pior de ficar, o poller de marketplace, sem o qual o restaurante some do iFood.
        // Esta rotina nova e a sexta: acrescentar sem subir o pool pioraria o 9.1.
        String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        var m = java.util.regex.Pattern.compile("scheduling:\\s*\\n\\s*pool:[\\s\\S]*?size:\\s*(\\d+)")
                .matcher(yml);
        assertTrue(m.find(), "nao achei o tamanho do pool do agendador");
        int pool = Integer.parseInt(m.group(1));

        long rotinas = contarRotinas();
        assertTrue(pool >= rotinas, "o pool tem " + pool + " thread(s) para " + rotinas
                + " rotina(s) agendada(s). A que ficar na fila pode ser o poller de marketplace, e ai "
                + "a loja some do iFood. Suba o `spring.task.scheduling.pool.size`.");
    }

    /** Quantos @Scheduled existem no projeto. */
    private long contarRotinas() throws IOException {
        try (var arquivos = Files.walk(Path.of("src/main/java"))) {
            return arquivos.filter(p -> p.toString().endsWith(".java"))
                    .mapToLong(p -> {
                        try {
                            String f = Files.readString(p, StandardCharsets.UTF_8);
                            return f.split("@(org\\.springframework\\.scheduling\\.annotation\\.)?Scheduled",
                                    -1).length - 1;
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
        }
    }
}
