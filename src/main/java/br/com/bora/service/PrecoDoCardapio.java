package br.com.bora.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * UM lugar que decide o que é um preço de venda válido, e um lugar que lê preço de texto.
 *
 * <h2>Os dois defeitos que esta classe fecha (18/11/2026)</h2>
 *
 * <p><b>1. Preço NULO virava venda de graça.</b> Nem {@code ProdutoService.criar} nem
 * {@code atualizar} conferiam o preço, e os dois caminhos que gravam venda faziam
 * {@code prod.preco == null ? ZERO : prod.preco}. Produto sem preço era vendido por R$ 0,00 e o
 * pedido ia para a cozinha com o total errado.</p>
 *
 * <p><b>2. Preço que a IA não soube ler virava ZERO, gravado e contado como sucesso.</b> A
 * importação de cardápio por foto fazia
 * {@code try { new BigDecimal(...) } catch (Exception e) { p.preco = ZERO; }}. O prompt pede ponto
 * decimal, mas pedir não é garantir: {@code "24,90"}, {@code "R$ 24,90"} e preço ausente todos
 * estouram o {@code BigDecimal} e todos viravam <b>R$ 0,00 salvos no cardápio</b>, somados em
 * {@code produtosCriados} como se tivessem dado certo. O lojista publicava o cardápio e descobria
 * pelo caixa.</p>
 *
 * <h2>ZERO É LEGÍTIMO. NULO NÃO É.</h2>
 *
 * <p>Isto não é teoria: foi <b>medido no cardápio público em 18/11/2026</b>. Entre os 182 produtos
 * ativos das 6 lojas reais, <b>nenhum</b> tem preço nulo e <b>5</b> têm preço zero de propósito:</p>
 *
 * <ul>
 *   <li><b>"Monte Seu Açaí"</b>, nas três lojas da Zirá (18, 19 e 20). Preço 0,00 porque quem
 *       carrega o preço é o complemento: o grupo "Recipientes" tem {@code minimo: 1} e toda opção
 *       custa de R$ 12,90 a R$ 50,00. O item não pode sair por zero — o mínimo não deixa.</li>
 *   <li><b>"Brinde Grátis"</b> e <b>"Brinde Gratuito"</b> na loja 1. Brinde é zero de propósito.</li>
 * </ul>
 *
 * <p>Então recusar {@code preco <= 0} teria quebrado o "Monte Seu Açaí" em três lojas reais —
 * o erro que a memória do projeto chama de <b>"zero não é vazio"</b> e que eu já cometi duas vezes
 * neste mês. Zero diz "é de graça". Nulo diz "ninguém disse", e quem não sabe o preço não vende.</p>
 */
public final class PrecoDoCardapio {

    private PrecoDoCardapio() {}

    /**
     * Recusa preço nulo ou negativo. ZERO passa — ver o javadoc da classe.
     *
     * <p>Negativo é recusado porque ele <b>subtrai</b> do total do pedido: um item de −R$ 10,00 no
     * carrinho paga parte dos outros. Não existe caso de negócio para isso.</p>
     *
     * @param ondeEstou o nome do produto, para o lojista saber qual linha corrigir
     */
    public static void exigirValido(BigDecimal preco, String ondeEstou) {
        String alvo = ondeEstou == null || ondeEstou.isBlank() ? "O produto" : "\"" + ondeEstou + "\"";
        if (preco == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    alvo + " está sem preço. Digite o preço — use 0 só se o item for de graça "
                            + "ou se o preço vier dos adicionais.");
        }
        if (preco.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    alvo + " está com preço negativo (" + preco.toPlainString()
                            + "). Preço negativo desconta do total do pedido.");
        }
    }

    /**
     * Lê um preço que veio como texto, ou devolve {@code null} quando NÃO der para ler.
     *
     * <p>Devolver nulo é o ponto: quem chama tem de decidir o que fazer com "não deu para ler", e
     * nenhuma das opções pode ser "grava zero e não conta a ninguém".</p>
     *
     * <h3>O que ela aceita, e por quê</h3>
     * <p>{@code "24,90"} e {@code "R$ 24,90"} são aceitos porque a intenção é inequívoca e é o que
     * um cardápio brasileiro escreve. Era a causa mais provável do zero silencioso: o prompt pede
     * ponto decimal, e a vírgula é o que está impresso na foto.</p>
     *
     * <h3>O que ela NÃO faz: adivinhar</h3>
     * <p>{@code "a partir de 24.90"} e {@code "24,90 / 34,90"} devolvem nulo, mesmo tendo número
     * dentro. O preço real pode ser qualquer um deles, e chutar o menor publica o cardápio mais
     * barato do que o papel. Não dar para ler é uma resposta honesta; chutar não é.</p>
     */
    public static BigDecimal lerOuNulo(Object bruto) {
        if (bruto instanceof BigDecimal b) return b;
        if (bruto instanceof Number n) return new BigDecimal(n.toString());
        if (bruto == null) return null;

        String t = String.valueOf(bruto).trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;

        // Tira moeda e espacos (inclusive o espaco fino que vem de PDF e de pagina web).
        t = t.replace("R$", "").replace("r$", "")
                .replace(" ", "").replace(" ", "").replace(" ", "")
                .trim();

        int ultimaVirgula = t.lastIndexOf(',');
        int ultimoPonto = t.lastIndexOf('.');
        if (ultimaVirgula >= 0 && ultimoPonto >= 0) {
            // Tem os dois: o ULTIMO e o separador decimal, o outro e separador de milhar.
            // "1.234,56" (Brasil) e "1,234.56" (EUA) saem os dois certos desta regra.
            int decimal = Math.max(ultimaVirgula, ultimoPonto);
            String inteira = t.substring(0, decimal).replace(".", "").replace(",", "");
            t = inteira + "." + t.substring(decimal + 1);
        } else if (ultimaVirgula >= 0) {
            // So virgula: e o separador decimal brasileiro.
            t = t.replace(',', '.');
        }

        // So numero daqui para frente: qualquer letra ou barra sobrando quer dizer que o texto nao
        // era um preco, e sim uma frase com numero dentro ("a partir de 24.90"). Nesses casos o
        // BigDecimal estouraria de qualquer jeito -- esta conferencia esta aqui para que a recusa
        // seja uma DECISAO visivel, e nao um catch generico.
        if (!t.matches("[+-]?[0-9]+([.][0-9]+)?")) return null;

        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
