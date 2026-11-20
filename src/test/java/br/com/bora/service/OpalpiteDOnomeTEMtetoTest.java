package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.security.FreioDeTentativas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ITEM 2 DA SECAO 4 DA VARREDURA (confirmado em 19/11/2026, consertado em 20/11).
 *
 * <h2>O item estava certo no diagnostico e incompleto na causa</h2>
 * <p>Ele dizia: "o cashback e protegido por telefone + primeiro nome, e telefone nao e segredo".
 * Verdade. Mas o que fazia disso um furo <b>explorável</b> era outra coisa, e nao estava escrita: o
 * palpite do nome <b>nao tinha teto nenhum</b>, em nenhum dos dois caminhos. Com o telefone na mao,
 * dava para martelar "joao", "maria", "jose"... quantas vezes quisesse -- na consulta de saldo e no
 * proprio checkout -- ate acertar. Primeiro nome e um espaco de algumas dezenas de palpites: sem
 * freio, isso nao e protecao, e so uma pergunta facil.</p>
 *
 * <h2>Por que nao pedi um codigo por WhatsApp</h2>
 * <p>Era a alternativa obvia, e ela <b>bloquearia todos os clientes de hoje</b>: enviar depende da
 * integracao WHATSAPP da loja (conta Meta), e nenhuma das lojas tem essa conta ligada --
 * {@code WhatsAppSender} exige {@code clientId}/{@code clientSecret} da integracao. Um conserto que
 * desliga o recurso para todo mundo nao e conserto. O freio nao pede nada do cliente legitimo: ele
 * digita o nome dele, acerta na primeira, e nem sabe que existe.</p>
 */
class OpalpiteDOnomeTEMtetoTest {

    private static final Long LOJA = 18L;
    private static final String TELEFONE = "11988887777";
    private static final String OUTRO_TELEFONE = "11977776666";

    private ClienteRepository clientes;
    private FreioDeTentativas freio;
    private FidelidadeService fidelidade;

    @BeforeEach
    void montar() {
        clientes = mock(ClienteRepository.class);
        freio = new FreioDeTentativas();   // o de verdade: o placar e o que esta sendo medido
        fidelidade = new FidelidadeService(clientes, mock(ConfiguracaoLojaRepository.class), freio);

        Cliente maria = cliente(42L, "Maria Eduarda", "25.00", TELEFONE);
        when(clientes.findFirstByLojaIdAndTelefone(LOJA, TELEFONE)).thenReturn(Optional.of(maria));
        when(clientes.findByIdAndLojaId(42L, LOJA)).thenReturn(Optional.of(maria));

        Cliente joao = cliente(43L, "Joao", "10.00", OUTRO_TELEFONE);
        when(clientes.findFirstByLojaIdAndTelefone(LOJA, OUTRO_TELEFONE)).thenReturn(Optional.of(joao));
        when(clientes.findByIdAndLojaId(43L, LOJA)).thenReturn(Optional.of(joao));
    }

    private Cliente cliente(Long id, String nome, String saldo, String telefone) {
        Cliente c = new Cliente();
        c.id = id;
        c.nome = nome;
        c.telefone = telefone;
        c.cashback = new BigDecimal(saldo);
        return c;
    }

    // =================================================================== a premissa

    // SEM ISTO, todo teste abaixo passaria com um freio que barra sempre -- e nenhum cliente
    // conseguiria ver nem gastar o proprio cashback, com a suite verde.
    @Test
    void premissa_oDONOdoSALDOpassaDEprimeira() {
        assertEquals(new BigDecimal("25.00"), fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "Maria"),
                "o dono tem de ver o saldo dele");
        assertDoesNotThrow(() -> fidelidade.exigirDonoDoSaldo(LOJA, 42L, TELEFONE, "maria"),
                "o dono tem de poder GASTAR o saldo dele");
    }

    // =================================================================== o teto

    @Test
    void depoisDeCINCOpalpitesERRADOSaCONSULTAeh429() {
        for (int i = 1; i <= 5; i++) {
            final int tentativa = i;
            assertDoesNotThrow(() -> fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute" + tentativa),
                    "o palpite " + tentativa + " ainda pode passar (e responder zero)");
        }
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute6"),
                "o sexto palpite tem de ser barrado -- era aqui que dava para martelar sem limite");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void oCHECKOUTtambemTEMteto() {
        for (int i = 1; i <= 5; i++) {
            final int tentativa = i;
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> fidelidade.exigirDonoDoSaldo(LOJA, 42L, TELEFONE, "chute" + tentativa));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(),
                    "nome errado e 400 enquanto houver palpite sobrando");
        }
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> fidelidade.exigirDonoDoSaldo(LOJA, 42L, TELEFONE, "chute6"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode(),
                "o checkout era o caminho SEM freio nenhum: dava para chutar o nome ali mesmo");
    }

    /** O que torna o freio um freio: um placar so para as duas portas. */
    @Test
    void oPLACARehCOMPARTILHADOentreAconsultaEoCHECKOUT() {
        for (int i = 1; i <= 5; i++) {
            final int tentativa = i;
            assertDoesNotThrow(() -> fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute" + tentativa));
        }
        // Estourou na consulta; o checkout tem de estar fechado tambem. Com um placar por caminho,
        // quem estourasse numa ponta seguiria chutando na outra -- dez palpites em vez de cinco.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> fidelidade.exigirDonoDoSaldo(LOJA, 42L, TELEFONE, "chute6"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    // =================================================================== quem nao pode ser punido

    @Test
    void oNOMEcertoZERAoPLACAR() {
        // O dono nao fica preso ao placar de quem chutou o telefone dele: ele digita o nome certo e
        // segue. E a metade do freio que protege o cliente legitimo, nao a loja.
        for (int i = 1; i <= 4; i++) {
            final int tentativa = i;
            assertDoesNotThrow(() -> fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute" + tentativa));
        }
        assertEquals(new BigDecimal("25.00"), fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "Maria"));

        // Zerado: ha cinco palpites novos, e nenhum deles estoura.
        for (int i = 5; i <= 9; i++) {
            final int tentativa = i;
            assertDoesNotThrow(() -> fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute" + tentativa),
                    "o acerto nao zerou o placar");
        }
    }

    @Test
    void oTELEFONEdeOUTROcontinuaLIVRE() {
        // A chave e loja+telefone. Com um balde unico, cinco pessoas errando o nome trancariam o
        // cashback de todos os clientes da loja.
        for (int i = 1; i <= 6; i++) {
            final int tentativa = i;
            try { fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "chute" + tentativa); } catch (RuntimeException ignorado) { }
        }
        assertEquals(new BigDecimal("10.00"), fidelidade.saldoPeloTelefone(LOJA, OUTRO_TELEFONE, "Joao"),
                "o freio de um telefone travou o de outro");
    }

    @Test
    void telefoneSEMcadastroNAOcontaPALPITE() {
        // Nao existe saldo para adivinhar num telefone sem cadastro. Contar aqui daria para travar o
        // freio de telefones ao acaso -- e, pior, nao protegeria nada.
        when(clientes.findFirstByLojaIdAndTelefone(eq(LOJA), eq("11900000000"))).thenReturn(Optional.empty());
        for (int i = 1; i <= 10; i++) {
            final int tentativa = i;
            assertDoesNotThrow(() -> fidelidade.saldoPeloTelefone(LOJA, "11900000000", "chute" + tentativa));
        }
    }

    // =================================================================== o que a resposta conta

    @Test
    void aRESPOSTAdoNOMEerradoEHigualAdeTELEFONEsemCADASTRO() {
        // As duas respondem ZERO. Se o nome errado respondesse diferente ("existe cadastro, voce
        // errou o nome"), o endpoint confirmaria para quem chuta que aquele telefone tem saldo --
        // e o freio seria um aviso de que ha algo ali.
        when(clientes.findFirstByLojaIdAndTelefone(eq(LOJA), eq("11900000000"))).thenReturn(Optional.empty());
        assertEquals(BigDecimal.ZERO, fidelidade.saldoPeloTelefone(LOJA, "11900000000", "Maria"));
        assertEquals(BigDecimal.ZERO, fidelidade.saldoPeloTelefone(LOJA, TELEFONE, "Beatriz"));
    }
}
