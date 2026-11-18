package br.com.bora.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preço que ninguém soube ler NÃO vira R$ 0,00 gravado — e preço ZERO continua valendo.
 *
 * <h2>O defeito (18/11/2026)</h2>
 * <p>A importação de cardápio por foto fazia, para cada item,
 * {@code try { p.preco = new BigDecimal(String.valueOf(it.get("preco"))); } catch (Exception e) { p.preco = BigDecimal.ZERO; }}
 * e salvava em seguida. O mesmo {@code catch} estava no salvamento de complementos. Qualquer texto
 * que o {@code BigDecimal} não aceitasse virava <b>R$ 0,00 no cardápio público</b>, somado em
 * {@code produtosCriados} como se tivesse dado certo.</p>
 *
 * <h2>Por que não é hipótese</h2>
 * <p>O prompt manda "Use ponto decimal no preço" — e pedir não é garantir. O que está impresso no
 * cardápio que o lojista fotografa é <b>vírgula</b>. O primeiro teste deste arquivo mede, um por
 * um, que o {@code BigDecimal} cru estoura em cada um desses textos: é a prova de que o
 * {@code catch} era alcançável pelo caminho mais comum, e não um galho morto.</p>
 */
class OprecoQUEaIAnaoLEUnaoViraZEROTest {

    // ===================================================== a premissa: o BigDecimal cru estoura

    /**
     * A PREMISSA MEDIDA: cada um destes textos faz {@code new BigDecimal} estourar.
     *
     * <p>Sem este teste, o conserto estaria defendendo contra um caminho que eu só <i>suponho</i>
     * alcançável. Com ele, está medido: era o {@code catch} que gravava zero.</p>
     */
    @Test
    void oBIGDECIMALcruESTOURAemTUDOqueUMcardapioBRASILEIROescreve() {
        String[] comoOcardapioEscreve = { "24,90", "R$ 24,90", "R$24,90", "1.234,56", "null", "", "  " };

        for (String texto : comoOcardapioEscreve) {
            assertThrows(NumberFormatException.class, () -> new BigDecimal(texto),
                    "este texto NAO estoura, entao o catch nao era alcancavel por ele: " + texto);
        }
    }

    /** E o caso mais silencioso: preço ausente no JSON vira a string "null", que também estoura. */
    @Test
    void precoAUSENTEnoJsonVIRAaStringNullEtambemESTOURA() {
        Object ausente = null;
        assertEquals("null", String.valueOf(ausente), "e assim que o ausente chegava ao BigDecimal");
        assertThrows(NumberFormatException.class, () -> new BigDecimal(String.valueOf(ausente)));
    }

    // ===================================================== o que a leitura nova faz

    @Test
    void aVIRGULAbrasileiraEHlidaCERTO() {
        assertEquals(new BigDecimal("24.90"), PrecoDoCardapio.lerOuNulo("24,90"));
        assertEquals(new BigDecimal("24.90"), PrecoDoCardapio.lerOuNulo("R$ 24,90"));
        assertEquals(new BigDecimal("24.90"), PrecoDoCardapio.lerOuNulo("R$24,90"));
        assertEquals(new BigDecimal("50.00"), PrecoDoCardapio.lerOuNulo(" r$ 50,00 "));
    }

    @Test
    void oPONTOdecimalQUEoPROMPTpedeCONTINUAvalendo() {
        assertEquals(new BigDecimal("24.90"), PrecoDoCardapio.lerOuNulo("24.90"));
        assertEquals(new BigDecimal("12"), PrecoDoCardapio.lerOuNulo(12));
        assertEquals(new BigDecimal("16.90"), PrecoDoCardapio.lerOuNulo(new BigDecimal("16.90")));
    }

    /** Milhar nos dois formatos: o ÚLTIMO separador é o decimal. */
    @Test
    void oMILHARnosDOISformatosSAIcerto() {
        assertEquals(new BigDecimal("1234.56"), PrecoDoCardapio.lerOuNulo("1.234,56"), "formato brasileiro");
        assertEquals(new BigDecimal("1234.56"), PrecoDoCardapio.lerOuNulo("1,234.56"), "formato americano");
    }

    /**
     * ELA NÃO ADIVINHA. Texto com número dentro, mas sem um preço único, devolve nulo.
     *
     * <p>Chutar o menor número de "24,90 / 34,90" publica o cardápio mais barato do que o papel —
     * é o defeito, de novo, com cara de conserto.</p>
     */
    @Test
    void frasesCOMnumeroDENTROnaoVIRAMpreco() {
        String[] naoSaoPreco = {
                "a partir de 24.90", "24,90 / 34,90", "sob consulta", "preco no balcao",
                "12.90 a 24.90", "--", "R$", "gratis", "1.2.3"
        };
        for (String t : naoSaoPreco) {
            assertNull(PrecoDoCardapio.lerOuNulo(t), "nao era um preco e virou um: " + t);
        }
    }

    @Test
    void ausenteEvazioDEVOLVEMnuloEnaoZERO() {
        assertNull(PrecoDoCardapio.lerOuNulo(null));
        assertNull(PrecoDoCardapio.lerOuNulo(""));
        assertNull(PrecoDoCardapio.lerOuNulo("   "));
        assertNull(PrecoDoCardapio.lerOuNulo("null"), "o String.valueOf(null) do JSON sem preco");
    }

    /** O ZERO DE VERDADE é lido como zero, porque zero é um preço legítimo. */
    @Test
    void oZEROescritoEHlidoCOMOzeroEnaoCOMOilegivel() {
        assertEquals(0, PrecoDoCardapio.lerOuNulo("0").signum());
        assertEquals(0, PrecoDoCardapio.lerOuNulo("0,00").signum());
        assertEquals(0, PrecoDoCardapio.lerOuNulo("R$ 0,00").signum());
        assertEquals(0, PrecoDoCardapio.lerOuNulo(0).signum());
    }

    // ===================================================== nulo e negativo na hora de gravar

    @Test
    void precoNULOehRECUSADOcomMENSAGEMqueDIZoQUEfazer() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> PrecoDoCardapio.exigirValido(null, "Acai 500ml"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("Acai 500ml"), "o lojista precisa saber QUAL linha corrigir");
        assertTrue(e.getReason().contains("0"), "e precisa saber que 0 e permitido quando e de graca");
    }

    @Test
    void precoNEGATIVOehRECUSADOporqueDESCONTAdoTOTAL() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> PrecoDoCardapio.exigirValido(new BigDecimal("-10.00"), "Combo"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("-10.00"), "mostrar o valor recusado");
    }

    /**
     * O TESTE QUE PROTEGE AS TRÊS LOJAS DA ZIRÁ.
     *
     * <p>Medido no cardápio público em 18/11/2026: "Monte Seu Açaí" está com preço <b>0,00</b> nas
     * lojas 18, 19 e 20, e quem carrega o preço é o grupo "Recipientes", com {@code minimo: 1} e
     * opções de R$ 12,90 a R$ 50,00. "Brinde Grátis" e "Brinde Gratuito" estão a zero na loja 1.</p>
     *
     * <p>Se alguém "endurecer" esta regra para {@code signum() <= 0}, estes 5 produtos param de
     * salvar em 4 lojas reais, e este teste reprova antes de chegar ao ar.</p>
     */
    @Test
    void oZEROcontinuaPASSANDO_porqueMONTEseuACAIexisteEMproducao() {
        assertDoesNotThrow(() -> PrecoDoCardapio.exigirValido(BigDecimal.ZERO, "Monte Seu Acai"));
        assertDoesNotThrow(() -> PrecoDoCardapio.exigirValido(new BigDecimal("0.00"), "Brinde Gratis"));
    }

    @Test
    void precoPOSITIVOpassaSEMreclamar() {
        assertDoesNotThrow(() -> PrecoDoCardapio.exigirValido(new BigDecimal("0.01"), "o menor possivel"));
        assertDoesNotThrow(() -> PrecoDoCardapio.exigirValido(new BigDecimal("24.90"), "Acai 500ml"));
    }

    /** Sem nome do produto a mensagem continua legível, e não "null está sem preço". */
    @Test
    void semNOMEaMENSAGEMnaoSAIquebrada() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> PrecoDoCardapio.exigirValido(null, null));
        assertFalse(e.getReason().contains("null"), "mensagem com 'null' no meio: " + e.getReason());
    }
}
