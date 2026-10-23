package br.com.bora.service;

import java.util.Set;

/**
 * Quais chaves de {@code config_plataforma} podem ter o VALOR mostrado na tela.
 *
 * <p><b>Por que isto existe.</b> A listagem de configurações da plataforma devolvia
 * {@code chave -> valor} para toda chave que não começasse com {@code credencial.}. Era lista
 * NEGRA: protegia o que alguém lembrou de proibir, e deixava passar tudo o que fosse criado
 * depois.</p>
 *
 * <p>O caso concreto que isso abre: {@code fiscal.focus-token} é o token da API fiscal, lido em
 * {@code FiscalService}. Ele não começa com {@code credencial.}, então sairia <b>em claro</b> na
 * resposta no dia em que o módulo fiscal for ligado. A coluna é cifrada em repouso, mas o JPA
 * decifra na leitura — a cifra protege o dump, não a resposta.</p>
 *
 * <p>Agora é lista BRANCA: só sai o valor de quem está aqui, e tudo o que é criado depois nasce
 * protegido por padrão. Segredo novo não precisa que ninguém se lembre de proibir.</p>
 *
 * <p>Para as demais chaves a tela ainda precisa saber se estão <i>definidas</i> — e saber disso não
 * exige ver o valor. É o que {@link #MARCA_DE_DEFINIDA} serve.</p>
 */
public final class ChavesDaPlataforma {

    private ChavesDaPlataforma() {}

    /**
     * O valor destas chaves pode ir para a tela: são decisões de operação, não segredos.
     *
     * <p>Antes de acrescentar uma chave aqui, a pergunta é: "se isto aparecer impresso num
     * screenshot, alguém consegue usar?". Se sim, não entra.</p>
     */
    private static final Set<String> VALOR_PODE_SAIR = Set.of(
            "fiscal.habilitado"   // "true"/"false": liga a emissão de NFC-e. Não dá acesso a nada.
    );

    /** O que a tela recebe no lugar do valor quando ele não pode sair. */
    public static final String MARCA_DE_DEFINIDA = "(definida)";

    /** O valor desta chave pode ser mostrado? Lista branca: na dúvida, não. */
    public static boolean valorPodeSair(String chave) {
        return chave != null && VALOR_PODE_SAIR.contains(chave);
    }

    /**
     * O que mostrar para esta chave: o valor, quando é seguro, ou só o aviso de que está definida.
     *
     * @return nunca o valor de uma chave fora da lista branca
     */
    public static String paraMostrar(String chave, String valor) {
        if (valorPodeSair(chave)) return valor;
        return valor == null || valor.isBlank() ? "" : MARCA_DE_DEFINIDA;
    }
}
