package br.com.bora.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * CPF e CNPJ: normalizar e conferir o dígito verificador. (item 4 da seção 4 da varredura)
 *
 * <h2>O defeito, medido em 19/11/2026</h2>
 * <p>O cadastro público aceitava <b>qualquer texto</b> no campo de documento. Nada conferia nada:
 * {@code EmpresaService.normalizar} só tirava a pontuação, e a loja nascia com o documento que
 * viesse. Três consequências, todas reais:</p>
 * <ul>
 *   <li><b>A regra "mesmo CNPJ = mesma empresa" passava a agrupar por lixo.</b> Dois cadastros com
 *       {@code 00000000000000} viravam a MESMA empresa — duas lojas de clientes diferentes dentro de
 *       um cliente só, com a rede multi-lojas ligando uma na outra.</li>
 *   <li><b>A cobrança usa este documento.</b> {@code AssinaturaService.documentoDaCobranca} manda o
 *       que está no cadastro para o Asaas; documento inválido é fatura que não nasce, descoberta no
 *       dia do vencimento.</li>
 *   <li><b>A nota fiscal também.</b> {@code FiscalService} emite com {@code cnpj_emitente} tirado
 *       daqui.</li>
 * </ul>
 *
 * <h2>O CNPJ ALFANUMÉRICO é regra vigente, não futuro</h2>
 * <p>Desde julho de 2026 (IN RFB 2.229/2024) o CNPJ novo pode ter <b>letras</b> nas 12 primeiras
 * posições; os 2 dígitos verificadores continuam numéricos. O cálculo é o mesmo mód-11 de sempre,
 * com cada caractere valendo {@code código ASCII - 48} — então {@code '0'} vale 0 e {@code 'A'} vale
 * 17. Um validador só-de-dígitos recusaria um CNPJ legítimo de cliente novo, e um
 * {@code replaceAll("\\D","")} o <b>mutila em silêncio</b>: "12ABC34501DE35" viraria "1234501 35".</p>
 *
 * <p><b>Conferido com o exemplo oficial</b> {@code 12.ABC.345/01DE-35} (ver
 * {@code ODOCUMENTOdoCADASTROehCONFERIDOTest}): a base {@code 12ABC34501DE} tem de produzir
 * exatamente os dígitos {@code 35}. Se o cálculo aqui estiver errado, esse teste cai.</p>
 *
 * <h2>Por que {@link #normalizar} guarda letras</h2>
 * <p>Ela substitui o {@code replaceAll("\\D","")} que estava espalhado. Para todo documento que hoje
 * existe no banco — <b>todos numéricos</b> — o resultado é idêntico caractere a caractere, então a
 * chave de agrupamento das empresas que já existem não muda. A diferença só aparece no dia em que
 * entrar um CNPJ com letra, e aí a diferença é entre guardar o documento certo e guardar um pedaço
 * dele.</p>
 *
 * <h2>Documento EM BRANCO continua passando</h2>
 * <p>Quem não informa documento ganha empresa própria — é como as 23 lojas de hoje funcionam, e
 * várias não têm documento nenhum no cadastro. Esta classe recusa o que está <b>preenchido e
 * errado</b>; exigir o campo é outra decisão, de produto, e travaria o cadastro de quem ainda está
 * abrindo o CNPJ.</p>
 */
public final class DocumentoFiscal {

    private DocumentoFiscal() {}

    /**
     * Só os caracteres que valem, em maiúsculas. Devolve {@code null} para vazio/em branco.
     *
     * <p>"53.953.786/0001-28" e "53953786000128" são o mesmo documento. "12.abc.345/01de-35" e
     * "12ABC34501DE35" também.</p>
     */
    public static String normalizar(String documento) {
        if (documento == null) return null;
        String limpo = documento.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        return limpo.isBlank() ? null : limpo;
    }

    /** Em branco é válido (ver o javadoc da classe). Preenchido tem de ser CPF ou CNPJ de verdade. */
    public static boolean valido(String documento) {
        String d = normalizar(documento);
        if (d == null) return true;
        if (d.length() == 11) return cpfValido(d);
        if (d.length() == 14) return cnpjValido(d);
        return false;
    }

    /**
     * Recusa com 400 e uma mensagem que diz o que fazer. Em branco passa.
     *
     * <p>A mensagem não revela de quem é o documento nem se ele já existe no sistema: quem está
     * cadastrando com o CNPJ de outra pessoa não deve aprender nada aqui. Quem barra documento de
     * terceiro é a guarda do {@code SignupController}, com mensagem própria.</p>
     */
    public static String exigirValido(String documento) {
        if (!valido(documento)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CPF/CNPJ inválido. Confira os números — o documento entra na sua fatura e na "
                            + "nota fiscal, então ele precisa estar certo.");
        }
        return normalizar(documento);
    }

    /** É um CNPJ (14 posições), e não um CPF? Usado por quem exige CNPJ, como a nota fiscal. */
    public static boolean ehCnpj(String documento) {
        String d = normalizar(documento);
        return d != null && d.length() == 14 && cnpjValido(d);
    }

    // ------------------------------------------------------------------------------- CPF

    private static boolean cpfValido(String d) {
        for (int i = 0; i < 11; i++) {
            if (d.charAt(i) < '0' || d.charAt(i) > '9') return false;   // CPF não tem letra
        }
        // 00000000000, 11111111111... passam no mód-11 e não existem. É o caso que mais aparece em
        // formulário: quem não quer informar digita o mesmo número onze vezes.
        if (d.chars().distinct().count() == 1) return false;
        int dv1 = digitoMod11(d, 9, 10);
        int dv2 = digitoMod11(d, 10, 11);
        return d.charAt(9) - '0' == dv1 && d.charAt(10) - '0' == dv2;
    }

    /** Mód-11 do CPF: pesos decrescentes a partir de {@code pesoInicial}. */
    private static int digitoMod11(String d, int quantos, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < quantos; i++) soma += (d.charAt(i) - '0') * (pesoInicial - i);
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    // ------------------------------------------------------------------------------ CNPJ

    /** Pesos do CNPJ, da direita para a esquerda: 2..9 e de novo 2..9. */
    private static final int[] PESOS_DV1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_DV2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private static boolean cnpjValido(String d) {
        // As 12 primeiras podem ser letra ou número (CNPJ alfanumérico); as 2 últimas, nunca.
        for (int i = 0; i < 12; i++) {
            char c = d.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
            if (!ok) return false;
        }
        for (int i = 12; i < 14; i++) {
            if (d.charAt(i) < '0' || d.charAt(i) > '9') return false;
        }
        if (d.chars().distinct().count() == 1) return false;    // 00000000000000 e companhia
        int dv1 = dvCnpj(d, PESOS_DV1);
        int dv2 = dvCnpj(d, PESOS_DV2);
        return d.charAt(12) - '0' == dv1 && d.charAt(13) - '0' == dv2;
    }

    /**
     * O mód-11 do CNPJ com o valor ASCII-48 de cada caractere.
     *
     * <p>É esta linha que faz o CNPJ alfanumérico funcionar sem tabela de conversão: para dígito,
     * {@code '7' - 48 = 7}, exatamente o valor de sempre; para letra, {@code 'A' - 48 = 17}.</p>
     */
    private static int dvCnpj(String d, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) soma += (d.charAt(i) - 48) * pesos[i];
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
