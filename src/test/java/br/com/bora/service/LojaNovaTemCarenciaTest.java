package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A carência do primeiro atraso vale para loja NOVA também.
 *
 * <h2>O item 3.5 da varredura</h2>
 * <p>A carência de 10 dias só era dada quando {@code acesso_ate} estava <b>nulo</b>. Essa guarda
 * existe por um bom motivo: o Asaas <b>reenvia</b> o aviso de atraso, e a fatura do mês seguinte gera
 * outro — sem guarda, quem nunca pagava ganhava 10 dias de graça todo mês, para sempre.</p>
 *
 * <p>Mas {@code acesso_ate} nulo é o estado de quem <b>já pagou</b> (quem paga tem acesso sem prazo).
 * Loja <b>nova</b> nasce com os 7 dias de cortesia gravados ali ({@code SignupController:163} e
 * {@code PlataformaController:662}) — então ela nunca entrava no {@code if}, e o primeiro atraso dela
 * não dava carência nenhuma. <b>O cliente novo, que é exatamente quem ainda está decidindo se fica,
 * era o único cortado sem prazo.</b> E o teste que garantia a carência montava a loja com
 * {@code acesso_ate} nulo — um estado que o sistema nunca cria para loja nova.</p>
 *
 * <p>A guarda agora é o <b>status de antes do aviso</b>: INADIMPLENTE já era o estado significa "este
 * atraso já foi contado". Quem pagou no meio volta para ATIVA e ganha a carência de novo no atraso
 * seguinte — o que é certo, ele pagou.</p>
 */
class LojaNovaTemCarenciaTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AssinaturaService service;

    private static final Long LOJA = 30L;
    private static final int CARENCIA = 10;

    private Assinatura assinatura;
    private Loja loja;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);

        assinatura = new Assinatura();
        assinatura.setId(1L);
        assinatura.setLojaId(LOJA);
        assinatura.setAsaasSubscriptionId("sub_nova");

        loja = new Loja();
        loja.id = LOJA;
        loja.setNome("Loja Nova");
        loja.setAtivo(true);

        when(repo.findByAsaasSubscriptionId("sub_nova")).thenReturn(Optional.of(assinatura));
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(assinatura));
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));

        service = new AssinaturaService(repo, lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(AsaasClient.class), mock(br.com.bora.security.AuthContext.class),
                mock(PagamentoAssinaturaRepository.class), CARENCIA);
    }

    private void atrasou() {
        service.processarWebhook("PAYMENT_OVERDUE", "sub_nova");
    }

    /**
     * Quantos dias de acesso a loja tem a partir de agora, ARREDONDADOS.
     *
     * <p>`DAYS.between` trunca: o prazo foi gravado alguns milissegundos antes desta leitura, entao
     * "agora + 10 dias" mede 9 dias, 23h59min59s e vira 9. A primeira versao deste teste caiu nisso
     * -- e o defeito era do teste, nao do codigo.</p>
     */
    private long diasDeAcesso() {
        assertNotNull(loja.acessoAte, "a loja ficou SEM prazo de acesso");
        return Math.round(ChronoUnit.MINUTES.between(OffsetDateTime.now(), loja.acessoAte) / 1440.0);
    }

    // =============================================================== loja nova

    @Test
    void lojaNOVAcomAcortesiaGANHAcarenciaNOprimeiroATRASO() {
        // O caso do item 3.5: ela nasceu com 7 dias de cortesia, que ja passaram ou estao acabando.
        loja.acessoAte = OffsetDateTime.now().plusDays(1);
        assinatura.setStatus(StatusAssinatura.PENDENTE);

        atrasou();

        assertEquals(StatusAssinatura.INADIMPLENTE, assinatura.getStatus());
        assertEquals(CARENCIA, diasDeAcesso(), "a loja nova tem que ganhar os 10 dias");
    }

    @Test
    void aCORTESIAmaiorQueACARENCIAnaoEHencurtada() {
        // Loja que acabou de assinar tem 7 dias; se a cortesia fosse de 30, cortar para 10 tiraria
        // dias que ela ja tinha. Vale o que esta mais longe.
        loja.acessoAte = OffsetDateTime.now().plusDays(30);
        assinatura.setStatus(StatusAssinatura.PENDENTE);

        atrasou();

        assertEquals(30, diasDeAcesso(), "o prazo nao pode encurtar por causa de um atraso");
    }

    @Test
    void quemJApagavaCONTINUAganhandoACARENCIA() {
        // Quem paga tem acesso sem prazo (acessoAte nulo). Era o unico caso que funcionava antes.
        loja.acessoAte = null;
        assinatura.setStatus(StatusAssinatura.ATIVA);

        atrasou();

        assertEquals(CARENCIA, diasDeAcesso());
    }

    // =============================================================== e a graca nao se repete

    @Test
    void oSEGUNDOavisoDOMESMOatrasoNAOestendeNADA() {
        // O Asaas REENVIA o aviso. Sem esta guarda, quem nunca paga ganha 10 dias todo mes para
        // sempre -- e era exatamente para isso que a guarda antiga existia.
        loja.acessoAte = OffsetDateTime.now().plusDays(1);
        assinatura.setStatus(StatusAssinatura.PENDENTE);
        atrasou();
        OffsetDateTime depoisDoPrimeiro = loja.acessoAte;

        atrasou();
        atrasou();

        assertEquals(depoisDoPrimeiro, loja.acessoAte, "o prazo nao pode andar a cada reenvio");
    }

    @Test
    void aFATURAdoMESseguinteNAOrenovaAGRACAdeQUEMnaoPAGOU() {
        // Mes seguinte, mesma inadimplencia: o status continua INADIMPLENTE, entao nada e estendido.
        assinatura.setStatus(StatusAssinatura.INADIMPLENTE);
        loja.acessoAte = OffsetDateTime.now().plusDays(2);
        OffsetDateTime antes = loja.acessoAte;

        atrasou();

        assertEquals(antes, loja.acessoAte);
    }

    @Test
    void quemPAGOUnoMEIOganhaACARENCIAdeNOVOnoATRASOseguinte() {
        // Pagou, voltou para ATIVA e o acesso perdeu o prazo. Atrasar de novo e um atraso NOVO -- ele
        // pagou no meio, entao merece a carencia outra vez.
        assinatura.setStatus(StatusAssinatura.INADIMPLENTE);
        loja.acessoAte = OffsetDateTime.now().plusDays(2);
        atrasou();

        service.processarWebhook("PAYMENT_RECEIVED", "sub_nova");
        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus());
        assertNull(loja.acessoAte, "quem paga nao tem prazo de acesso");

        atrasou();

        assertEquals(CARENCIA, diasDeAcesso());
    }

    // =============================================================== o que nao mudou

    @Test
    void oSTATUSvaiPARAinadimplenteDEqualquerJEITO() {
        // O corte depende do status, nao so da data. Mesmo no reenvio, o estado tem que estar certo.
        assinatura.setStatus(StatusAssinatura.ATIVA);
        loja.acessoAte = null;

        atrasou();

        assertEquals(StatusAssinatura.INADIMPLENTE, assinatura.getStatus());
    }

    @Test
    void oPAGAMENTOcontinuaLIMPANDOoPRAZO() {
        assinatura.setStatus(StatusAssinatura.INADIMPLENTE);
        loja.acessoAte = OffsetDateTime.now().plusDays(3);

        service.processarWebhook("PAYMENT_RECEIVED", "sub_nova");

        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus());
        assertNull(loja.acessoAte);
    }
}
