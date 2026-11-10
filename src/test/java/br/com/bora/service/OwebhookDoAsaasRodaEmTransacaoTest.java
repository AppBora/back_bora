package br.com.bora.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O caminho REAL do webhook do Asaas roda dentro de uma transação.
 *
 * <h2>O defeito que isto fecha (confirmado em 10/11/2026)</h2>
 * <p>O {@code @Transactional} do {@code processarWebhook} estava na sobrecarga de <b>5</b>
 * parâmetros, cujo próprio javadoc diz, com estas palavras, que <i>"o webhook de verdade NÃO usa esta
 * sobrecarga"</i>. O {@code AsaasWebhookController} chama a de <b>6</b>, que não tinha anotação
 * nenhuma — então o único caminho que existe em produção rodava <b>sem transação</b>.</p>
 *
 * <p>Esse método grava o pagamento, muda o status da assinatura, ativa ou suspende a loja e estende o
 * acesso. Sem transação, uma falha no meio deixa metade aplicada: pagamento registrado sem o acesso
 * estendido, ou o contrário. É dinheiro e é acesso.</p>
 *
 * <h2>Por que ESTE teste, e por que por reflexão</h2>
 * <p>Há mais de 60 chamadas a {@code processarWebhook} nos testes, e <b>nenhuma</b> podia pegar isso:
 * todas chamam o serviço direto, com {@code new AssinaturaService(...)}, e sem o proxy do Spring a
 * anotação não tem efeito nenhum — o teste se comporta igual com ela e sem ela. Subir contexto do
 * Spring só para observar um limite de transação seria caro e frágil.</p>
 *
 * <p>Então o que dá para provar, e é exatamente o que falhou, é a <b>presença da anotação na
 * assinatura que o controller usa</b>. O que escondeu o defeito foi a disposição do arquivo: a
 * anotação ficava entre dois javadoc e parecia pertencer ao método de baixo. Reflexão não se engana
 * com isso.</p>
 */
class OwebhookDoAsaasRodaEmTransacaoTest {

    /** A assinatura EXATA que o {@code AsaasWebhookController} chama. */
    @Test
    void aSobrecargaQueOcontrollerCHAMAtemTransactional() throws NoSuchMethodException {
        Method real = AssinaturaService.class.getMethod("processarWebhook",
                String.class,            // event
                String.class,            // subscriptionId
                String.class,            // paymentId
                BigDecimal.class,        // valorEstornado
                BigDecimal.class,        // valor
                OffsetDateTime.class);   // pagoEm

        assertNotNull(real.getAnnotation(Transactional.class),
                "A sobrecarga de 6 parametros e a UNICA que o webhook do Asaas chama em producao. "
                        + "Sem @Transactional nela, o pagamento, o status da assinatura e o prazo de "
                        + "acesso sao gravados fora de transacao, e uma falha no meio deixa metade "
                        + "aplicada. Nao basta a anotacao estar nas outras sobrecargas: elas delegam "
                        + "por chamada interna, que o controller nunca percorre.");
    }

    /**
     * E nenhuma sobrecarga nova pode nascer sem a anotação.
     *
     * <p>Foi assim que o defeito apareceu: alguém acrescentou um parâmetro, criou uma sobrecarga nova
     * para delegar, e a anotação ficou na antiga. Este teste reprova a próxima vez.</p>
     */
    @Test
    void TODAsobrecargaPUBLICAdeProcessarWebhookTemTransactional() {
        List<String> semAnotacao = Arrays.stream(AssinaturaService.class.getDeclaredMethods())
                .filter(m -> "processarWebhook".equals(m.getName()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getAnnotation(Transactional.class) == null)
                .map(m -> m.getParameterCount() + " parametros: " + Arrays.toString(m.getParameterTypes()))
                .toList();

        assertTrue(semAnotacao.isEmpty(),
                "Estas sobrecargas publicas de processarWebhook estao SEM @Transactional, e qualquer "
                        + "uma delas pode ser a que o controller chama: " + semAnotacao);
    }

    /** Guarda o número de sobrecargas: se nascer outra, alguém tem de olhar este arquivo. */
    @Test
    void saoTRESsobrecargas_eTODASdelegamParaAdeSEIS() {
        long quantas = Arrays.stream(AssinaturaService.class.getDeclaredMethods())
                .filter(m -> "processarWebhook".equals(m.getName()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .count();

        assertEquals(3, quantas,
                "Mudou o numero de sobrecargas de processarWebhook. Confira qual delas o "
                        + "AsaasWebhookController chama e se ela tem @Transactional -- foi exatamente "
                        + "esse descasamento que deixou o webhook sem transacao.");
    }
}
