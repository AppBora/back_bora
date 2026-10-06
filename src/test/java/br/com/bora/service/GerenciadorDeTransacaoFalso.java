package br.com.bora.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gerenciador de transacao de mentira, para os testes que montam o service na mao.
 *
 * <p>O {@code TransactionTemplate} exige um gerenciador de verdade so para abrir e fechar; nos testes
 * nao ha banco, entao basta devolver um status vazio. Sem isto, cada teste que constroi
 * {@code PedidoService} ou {@code CobradorDePixService} precisaria repetir estas quatro linhas.</p>
 */
public final class GerenciadorDeTransacaoFalso {

    private GerenciadorDeTransacaoFalso() {}

    public static PlatformTransactionManager novo() {
        PlatformTransactionManager g = mock(PlatformTransactionManager.class);
        when(g.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return g;
    }
}
